/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.context.CassiniHttpHeaders;
import io.vidocq.cassini.internal.context.CassiniRequest;
import io.vidocq.cassini.internal.context.CassiniSecurityContext;
import io.vidocq.cassini.internal.context.CassiniUriInfo;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Injects into the fields of a resource instance the values of
 * {@link PathParam}/{@link QueryParam}/{@link HeaderParam}/{@link CookieParam}/
 * {@link FormParam}/{@link MatrixParam} as well as {@link Context} injections
 * (UriInfo, HttpHeaders, Request, SecurityContext) declared at the field
 * level (§3.2 / §9).
 *
 * <p>For each request the declared fields are iterated and their values set
 * via reflection. Since JAX-RS instances are typically {@code @RequestScoped}
 * (i.e. a new backing instance per request on the CDI side), this
 * re-injection is safe.</p>
 */
public final class FieldInjector {

    private FieldInjector() {}

    public static void inject(Object target, MatchResult match, CassiniHttpExchange request) {
        inject(target, match, request, true);
    }

    /** §3.4.1 / JAXRS:SPEC:4 : "Objects returned by sub-resource locators are
     *  expected to be initialized by their creator and field and bean
     *  properties are not modified by the implementation runtime." For
     *  sub-resources returned by a locator, only @Context fields are injected
     *  (injectParams=false) — @*Param fields keep their initial
     *  value (typically null). */
    public static void inject(Object target, MatchResult match, CassiniHttpExchange request, boolean injectParams) {
        if (target == null) return;
        Class<?> cls = target.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                Object value = resolveFieldValue(f, match, request, injectParams);
                if (value != null) setField(target, f, value);
            }
            cls = cls.getSuperclass();
        }
    }

    private static Object resolveFieldValue(Field f, MatchResult match, CassiniHttpExchange request, boolean injectParams) {
        if (f.getAnnotation(Context.class) != null) return resolveContext(f.getType(), match, request);
        if (!injectParams) return null;
        if (f.getAnnotation(BeanParam.class) != null) return beanParam(f, match, request);
        return paramValue(f, match, request);
    }

    private static Object beanParam(Field f, MatchResult match, CassiniHttpExchange request) {
        try {
            Object nested = f.getType().getDeclaredConstructor().newInstance();
            inject(nested, match, request);
            return nested;
        } catch (ReflectiveOperationException e) {
            throw new WebApplicationException("Failed to instantiate @BeanParam field "
                    + f.getName() + ": " + e.getMessage(), 500);
        }
    }

    /** The value of a {@code @*Param} field (§3.2), or null when the field has none of them. */
    private static Object paramValue(Field f, MatchResult match, CassiniHttpExchange request) {
        String def = defaultValue(f);
        boolean encoded = f.getAnnotation(jakarta.ws.rs.Encoded.class) != null
                || f.getDeclaringClass().getAnnotation(jakarta.ws.rs.Encoded.class) != null;
        PathParam pp = f.getAnnotation(PathParam.class);
        if (pp != null) return pathValue(f, match.pathParams().getOrDefault(pp.value(), List.of()), def, encoded);
        QueryParam qp = f.getAnnotation(QueryParam.class);
        if (qp != null) return coerce(f, orDefault(parsedQueryParams(request, encoded).getOrDefault(qp.value(), List.of()), def));
        HeaderParam hp = f.getAnnotation(HeaderParam.class);
        if (hp != null) return coerce(f, orDefault(request.headers(hp.value()), def));
        CookieParam cp = f.getAnnotation(CookieParam.class);
        if (cp != null) {
            String raw = cookie(request, cp.value());
            return coerce(f, raw == null ? emptyOrDef(def) : List.of(raw));
        }
        MatrixParam mp = f.getAnnotation(MatrixParam.class);
        if (mp != null) return coerce(f, orDefault(matrix(request, mp.value(), encoded), def));
        FormParam fp = f.getAnnotation(FormParam.class);
        if (fp != null) return coerce(f, orDefault(readForm(request, encoded).getOrDefault(fp.value(), List.of()), def));
        return null;
    }

    private static Object pathValue(Field f, List<String> raws, String def, boolean encoded) {
        if (raws.isEmpty()) return coerce(f, emptyOrDef(def));
        if (encoded) return coerce(f, raws);
        List<String> decoded = new ArrayList<>(raws.size());
        for (String raw : raws) decoded.add(decodePath(raw));
        return coerce(f, decoded);
    }

    /** {@code raws}, or the {@code @DefaultValue} when there is none. */
    private static List<String> orDefault(List<String> raws, String def) {
        return raws.isEmpty() ? emptyOrDef(def) : raws;
    }

    /** Resolves the {@code @Context} value of one type, or null when there is none here. */
    @FunctionalInterface
    private interface ContextValue {
        Object resolve(MatchResult match, CassiniHttpExchange request);
    }

    /** Stub minimal §10.1: no dynamic properties or features. */
    private static final jakarta.ws.rs.core.Configuration SERVER_CONFIGURATION = new jakarta.ws.rs.core.Configuration() {
        @Override public jakarta.ws.rs.RuntimeType getRuntimeType() { return jakarta.ws.rs.RuntimeType.SERVER; }
        @Override public Map<String, Object> getProperties() { return Map.of(); }
        @Override public Object getProperty(String name) { return null; }
        @Override public java.util.Collection<String> getPropertyNames() { return List.of(); }
        @Override public boolean isEnabled(jakarta.ws.rs.core.Feature feature) { return false; }
        @Override public boolean isEnabled(Class<? extends jakarta.ws.rs.core.Feature> featureClass) { return false; }
        @Override public boolean isRegistered(Object component) { return false; }
        @Override public boolean isRegistered(Class<?> componentClass) { return false; }
        @Override public Map<Class<?>, Integer> getContracts(Class<?> componentClass) { return Map.of(); }
        @Override public java.util.Set<Class<?>> getClasses() { return java.util.Set.of(); }
        @Override public java.util.Set<Object> getInstances() { return java.util.Set.of(); }
    };

    /** The {@code @Context} types a field may declare (§9.2), by exact type. */
    private static final Map<Class<?>, ContextValue> CONTEXT_VALUES = Map.ofEntries(
            Map.entry(UriInfo.class, (match, request) -> new CassiniUriInfo(request, request.contextPath(),
                    match.pathParams(), match.method() == null ? null : match.method().path())),
            Map.entry(HttpHeaders.class, (_, request) -> new CassiniHttpHeaders(request)),
            Map.entry(jakarta.ws.rs.core.Request.class, (_, request) -> new CassiniRequest(request)),
            Map.entry(SecurityContext.class, (_, request) -> securityContext(request)),
            Map.entry(jakarta.ws.rs.ext.Providers.class, (_, _) -> ParamExtractor.currentProviders()),
            Map.entry(jakarta.ws.rs.core.Application.class, (_, _) -> new jakarta.ws.rs.core.Application()),
            Map.entry(jakarta.ws.rs.container.ResourceInfo.class, (match, _) -> resourceInfo(match.method())),
            Map.entry(jakarta.ws.rs.container.ContainerRequestContext.class, FieldInjector::requestContext),
            Map.entry(jakarta.ws.rs.container.ResourceContext.class, FieldInjector::resourceContext),
            Map.entry(jakarta.ws.rs.core.Configuration.class, (_, _) -> SERVER_CONFIGURATION));

    /** Internal: delegated to by {@code InjectionSupportImpl} to keep a single source of truth.
     *  Null for a type that has no {@code @Context} value here. */
    public static Object resolveContext(Class<?> type, MatchResult match, CassiniHttpExchange request) {
        ContextValue value = CONTEXT_VALUES.get(type);
        return value == null ? null : value.resolve(match, request);
    }

    private static Object securityContext(CassiniHttpExchange request) {
        Object filterSc = request.getAttribute(
                io.vidocq.cassini.internal.filter.CassiniRequestContext.ATTR_SECURITY_CONTEXT);
        return filterSc != null ? filterSc : new CassiniSecurityContext(request);
    }

    private static jakarta.ws.rs.container.ResourceInfo resourceInfo(ResourceMethod route) {
        return new jakarta.ws.rs.container.ResourceInfo() {
            @Override public java.lang.reflect.Method getResourceMethod() { return route.javaMethod(); }
            @Override public Class<?> getResourceClass() { return route.beanClass(); }
        };
    }

    private static Object requestContext(MatchResult match, CassiniHttpExchange request) {
        var rctx = new io.vidocq.cassini.internal.filter.CassiniRequestContext(
                request, new CassiniUriInfo(request, request.contextPath(), match.pathParams()));
        rctx.markPostMatching();
        rctx.markPostResource();
        return rctx;
    }

    /** §6.5.2 minimal stub: getResource(class) instantiates through the no-arg constructor and
     *  injects the fields, per request. */
    private static jakarta.ws.rs.container.ResourceContext resourceContext(MatchResult match, CassiniHttpExchange request) {
        return new jakarta.ws.rs.container.ResourceContext() {
            @Override public <T> T getResource(Class<T> resourceClass) {
                try {
                    T r = resourceClass.getDeclaredConstructor().newInstance();
                    FieldInjector.inject(r, match, request);
                    return r;
                } catch (ReflectiveOperationException _) {
                    return null;
                }
            }
            @Override public <T> T initResource(T resource) {
                FieldInjector.inject(resource, match, request);
                return resource;
            }
        };
    }

    private static Object coerce(Field f, List<String> raws) {
        Class<?> raw = f.getType();
        Class<?> element = ParamValueConverter.isListLike(raw)
                ? genericElementType(f.getGenericType()) : raw;
        boolean notFound = f.getAnnotation(PathParam.class) != null
                || f.getAnnotation(MatrixParam.class) != null
                || f.getAnnotation(QueryParam.class) != null;
        try { return ParamValueConverter.coerce(raw, element, raws); }
        catch (WebApplicationException wae) {
            // §3.2 : @PathParam/@QueryParam/@MatrixParam conversion failure → 404.
            if (notFound && wae.getResponse() != null && wae.getResponse().getStatus() == 400) {
                throw new WebApplicationException(wae.getMessage(), wae.getCause(),
                        jakarta.ws.rs.core.Response.status(404).build());
            }
            throw wae;
        }
        catch (RuntimeException e) {
            throw new WebApplicationException("Invalid value for field "
                    + f.getName() + ": " + e.getMessage(), e,
                    jakarta.ws.rs.core.Response.status(notFound ? 404 : 400).build());
        }
    }

    private static Class<?> genericElementType(Type t) {
        if (t instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) return c;
        return String.class;
    }

    private static void setField(Object target, Field f, Object v) {
        try {
            // Injecting the declared, often private, fields of a resource is the point (§9.2).
            f.setAccessible(true); // NOSONAR java:S3011 - field injection of the resource (§3.2, §9.2)
            f.set(target, v); // NOSONAR java:S3011 - same field, made accessible above
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(e);
        }
    }

    private static List<String> emptyOrDef(String def) { return def == null ? List.of() : List.of(def); }
    private static String defaultValue(Field f) {
        DefaultValue d = f.getAnnotation(DefaultValue.class);
        return d == null ? null : d.value();
    }

    private static String decodePath(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException _) { return s; }
    }

    private static Map<String, List<String>> parseQuery(String raw, boolean encoded) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        return FormDecoder.parse(raw, !encoded);
    }

    /** Internal: used by {@code InjectionSupportImpl}. */
    public static Map<String, List<String>> parsedQueryParams(CassiniHttpExchange request, boolean encoded) {
        String raw = request.requestUri() == null ? null : request.requestUri().getRawQuery();
        return parseQuery(raw, encoded);
    }

    /** Internal: used by {@code InjectionSupportImpl}. */
    public static String cookie(CassiniHttpExchange request, String name) {
        for (String header : request.headers("Cookie")) {
            String value = header == null ? null : cookieIn(header, name);
            if (value != null) return value;
        }
        return null;
    }

    /** The value of the cookie {@code name} in one Cookie header, unquoted, or null. */
    private static String cookieIn(String header, String name) {
        for (String pair : header.split(";")) {
            int eq = pair.indexOf('=');
            if (eq >= 0 && pair.substring(0, eq).trim().equals(name)) {
                String v = pair.substring(eq + 1).trim();
                boolean quoted = v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"");
                return quoted ? v.substring(1, v.length() - 1) : v;
            }
        }
        return null;
    }

    /** Internal: used by {@code InjectionSupportImpl}. */
    public static List<String> matrix(CassiniHttpExchange request, String name, boolean encoded) {
        List<String> out = new ArrayList<>();
        String path = request.requestUri() == null ? null : request.requestUri().getRawPath();
        if (path == null) return out;
        for (String seg : path.split("/")) {
            int semi = seg.indexOf(';');
            if (semi >= 0) addMatrixValues(seg.substring(semi + 1), name, encoded, out);
        }
        return out;
    }

    /** Adds the values of {@code name} found in the {@code ;}-separated parameters of one segment. */
    private static void addMatrixValues(String parameters, String name, boolean encoded, List<String> out) {
        for (String pair : parameters.split(";")) {
            int eq = pair.indexOf('=');
            String n = eq < 0 ? pair : pair.substring(0, eq);
            if (URLDecoder.decode(n, StandardCharsets.UTF_8).equals(name)) {
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                out.add(encoded || eq < 0 ? v : URLDecoder.decode(v, StandardCharsets.UTF_8));
            }
        }
    }

    // Exchange attribute keys for per-request caches (M2h — replaces ThreadLocals).
    static final String ATTR_FORM_CACHE         = "cassini.form_cache";
    static final String ATTR_FORM_CACHE_ENCODED = "cassini.form_cache_encoded";
    /** Exchange key for the body bytes cache (shared between @FormParam and MBR). */
    public static final String ATTR_BODY_CACHE  = "cassini.body_cache";

    /** Internal: used by {@code InjectionSupportImpl}. */
    @SuppressWarnings("unchecked")
    public static Map<String, List<String>> readForm(CassiniHttpExchange request, boolean encoded) {
        if (encoded) {
            Map<String, List<String>> enc =
                    (Map<String, List<String>>) request.getAttribute(ATTR_FORM_CACHE_ENCODED);
            if (enc != null) return enc;
        } else {
            Map<String, List<String>> cached =
                    (Map<String, List<String>>) request.getAttribute(ATTR_FORM_CACHE);
            if (cached != null) return cached;
        }
        try {
            byte[] bytes = (byte[]) request.getAttribute(ATTR_BODY_CACHE);
            if (bytes == null) {
                var body = request.requestBody();
                bytes = body == null ? new byte[0] : body.readAllBytes();
                request.setAttribute(ATTR_BODY_CACHE, bytes);
            }
            if (encoded) {
                Map<String, List<String>> parsed = FormDecoder.parse(
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8), false);
                request.setAttribute(ATTR_FORM_CACHE_ENCODED, parsed);
                return parsed;
            } else {
                Map<String, List<String>> parsed = bytes.length == 0
                        ? new LinkedHashMap<>() : FormDecoder.decode(bytes);
                request.setAttribute(ATTR_FORM_CACHE, parsed);
                return parsed;
            }
        } catch (Exception e) {
            throw new WebApplicationException("Failed to read form body: " + e.getMessage(), 400);
        }
    }

    /** No-op since M2h: caches live in the exchange attributes. */
    public static void clearFormCache() {
        // Nothing to clear: the caches are exchange attributes, gone with the exchange.
    }
}
