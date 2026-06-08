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

    private static Object resolveFieldValue(Field f, MatchResult match, CassiniHttpExchange request) {
        return resolveFieldValue(f, match, request, true);
    }

    private static Object resolveFieldValue(Field f, MatchResult match, CassiniHttpExchange request, boolean injectParams) {
        Context ctx = f.getAnnotation(Context.class);
        if (ctx != null) return resolveContext(f.getType(), match, request);
        if (!injectParams) return null;

        BeanParam bp = f.getAnnotation(BeanParam.class);
        if (bp != null) {
            try {
                Object nested = f.getType().getDeclaredConstructor().newInstance();
                inject(nested, match, request);
                return nested;
            } catch (ReflectiveOperationException e) {
                throw new WebApplicationException("Failed to instantiate @BeanParam field "
                        + f.getName() + ": " + e.getMessage(), 500);
            }
        }

        String def = defaultValue(f);
        boolean encoded = f.getAnnotation(jakarta.ws.rs.Encoded.class) != null
                || f.getDeclaringClass().getAnnotation(jakarta.ws.rs.Encoded.class) != null;
        PathParam pp = f.getAnnotation(PathParam.class);
        if (pp != null) {
            List<String> raws = match.pathParams().getOrDefault(pp.value(), List.of());
            if (raws.isEmpty()) return coerce(f, emptyOrDef(def));
            List<String> vals = encoded ? raws : raws.stream().map(FieldInjector::decodePath).toList();
            return coerce(f, vals);
        }
        QueryParam qp = f.getAnnotation(QueryParam.class);
        if (qp != null) {
            List<String> raws = parsedQueryParams(request, encoded).getOrDefault(qp.value(), List.of());
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        HeaderParam hp = f.getAnnotation(HeaderParam.class);
        if (hp != null) {
            List<String> raws = request.headers(hp.value());
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        CookieParam cp = f.getAnnotation(CookieParam.class);
        if (cp != null) {
            String raw = cookie(request, cp.value());
            return coerce(f, raw == null ? emptyOrDef(def) : List.of(raw));
        }
        MatrixParam mp = f.getAnnotation(MatrixParam.class);
        if (mp != null) {
            List<String> raws = matrix(request, mp.value(), encoded);
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        FormParam fp = f.getAnnotation(FormParam.class);
        if (fp != null) {
            Map<String, List<String>> form = readForm(request, encoded);
            List<String> raws = form.getOrDefault(fp.value(), List.of());
            return coerce(f, raws.isEmpty() ? emptyOrDef(def) : raws);
        }
        return null;
    }

    /** Internal: delegated to by {@code InjectionSupportImpl} to keep a single source of truth. */
    public static Object resolveContext(Class<?> type, MatchResult match, CassiniHttpExchange request) {
        if (type == UriInfo.class) return new CassiniUriInfo(request, request.contextPath(),
                match.pathParams(), match.method() == null ? null : match.method().path());
        if (type == HttpHeaders.class) return new CassiniHttpHeaders(request);
        if (type == jakarta.ws.rs.core.Request.class) return new CassiniRequest(request);
        if (type == SecurityContext.class) {
            Object filterSc = request.getAttribute(
                    io.vidocq.cassini.internal.filter.CassiniRequestContext.ATTR_SECURITY_CONTEXT);
            return filterSc != null ? filterSc : new CassiniSecurityContext(request);
        }
        if (type == jakarta.ws.rs.ext.Providers.class) return ParamExtractor.currentProviders();
        if (type == jakarta.ws.rs.core.Application.class) return new jakarta.ws.rs.core.Application();
        if (type == jakarta.ws.rs.container.ResourceInfo.class) {
            var route = match.method();
            return new jakarta.ws.rs.container.ResourceInfo() {
                @Override public java.lang.reflect.Method getResourceMethod() { return route.javaMethod(); }
                @Override public Class<?> getResourceClass() { return route.beanClass(); }
            };
        }
        if (type == jakarta.ws.rs.container.ContainerRequestContext.class) {
            var rctx = new io.vidocq.cassini.internal.filter.CassiniRequestContext(
                    request, new CassiniUriInfo(request, request.contextPath(), match.pathParams()));
            rctx.markPostMatching();
            rctx.markPostResource();
            return rctx;
        }
        if (type == jakarta.ws.rs.container.ResourceContext.class) {
            // §6.5.2 stub minimal : getResource(class) instancie via constructor
            // no-arg + injection de fields (per-request).
            return new jakarta.ws.rs.container.ResourceContext() {
                @Override public <T> T getResource(Class<T> resourceClass) {
                    try {
                        T r = resourceClass.getDeclaredConstructor().newInstance();
                        FieldInjector.inject(r, match, request);
                        return r;
                    } catch (ReflectiveOperationException e) {
                        return null;
                    }
                }
                @Override public <T> T initResource(T resource) {
                    FieldInjector.inject(resource, match, request);
                    return resource;
                }
            };
        }
        if (type == jakarta.ws.rs.core.Configuration.class) {
            // Stub minimal §10.1 : pas de Properties / Features dynamiques.
            return new jakarta.ws.rs.core.Configuration() {
                @Override public jakarta.ws.rs.RuntimeType getRuntimeType() {
                    return jakarta.ws.rs.RuntimeType.SERVER;
                }
                @Override public java.util.Map<String, Object> getProperties() { return java.util.Map.of(); }
                @Override public Object getProperty(String name) { return null; }
                @Override public java.util.Collection<String> getPropertyNames() { return java.util.List.of(); }
                @Override public boolean isEnabled(jakarta.ws.rs.core.Feature feature) { return false; }
                @Override public boolean isEnabled(Class<? extends jakarta.ws.rs.core.Feature> featureClass) { return false; }
                @Override public boolean isRegistered(Object component) { return false; }
                @Override public boolean isRegistered(Class<?> componentClass) { return false; }
                @Override public java.util.Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
                    return java.util.Map.of();
                }
                @Override public java.util.Set<Class<?>> getClasses() { return java.util.Set.of(); }
                @Override public java.util.Set<Object> getInstances() { return java.util.Set.of(); }
            };
        }
        return null;
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
        try { f.setAccessible(true); f.set(target, v); }
        catch (IllegalAccessException e) { throw new RuntimeException(e); }
    }

    private static List<String> emptyOrDef(String def) { return def == null ? List.of() : List.of(def); }
    private static String defaultValue(Field f) {
        DefaultValue d = f.getAnnotation(DefaultValue.class);
        return d == null ? null : d.value();
    }

    private static String decodePath(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
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
            if (header == null) continue;
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String n = pair.substring(0, eq).trim();
                if (n.equals(name)) {
                    String v = pair.substring(eq + 1).trim();
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length() >= 2) {
                        v = v.substring(1, v.length() - 1);
                    }
                    return v;
                }
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
            if (semi < 0) continue;
            for (String pair : seg.substring(semi + 1).split(";")) {
                int eq = pair.indexOf('=');
                String n = eq < 0 ? pair : pair.substring(0, eq);
                if (URLDecoder.decode(n, StandardCharsets.UTF_8).equals(name)) {
                    if (eq < 0) out.add("");
                    else {
                        String v = pair.substring(eq + 1);
                        out.add(encoded ? v : URLDecoder.decode(v, StandardCharsets.UTF_8));
                    }
                }
            }
        }
        return out;
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
    public static void clearFormCache() {}
}
