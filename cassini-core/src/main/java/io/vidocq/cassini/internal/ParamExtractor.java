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
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;

import java.lang.reflect.Parameter;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the invocation arguments of a resource method from a
 * Chappe {@link Request} + router {@link MatchResult}.
 *
 * <p>M2b: supports {@link PathParam}, {@link QueryParam}, {@link HeaderParam},
 * {@link CookieParam}, {@link FormParam}, {@link MatrixParam},
 * {@link DefaultValue}. Parameters without a JAX-RS annotation are
 * considered to be the request body — their index is exposed through
 * {@link ResolvedArgs#bodyIndex} and will be filled by the Invoker through a
 * {@link MessageBodyRegistry}.</p>
 */
public final class ParamExtractor {

    public record ResolvedArgs(Object[] args, int bodyIndex) {
        @Override
        public boolean equals(Object o) {
            return o instanceof ResolvedArgs(Object[] otherArgs, int otherBodyIndex)
                    && bodyIndex == otherBodyIndex && java.util.Arrays.equals(args, otherArgs);
        }

        @Override
        public int hashCode() {
            return 31 * java.util.Arrays.hashCode(args) + bodyIndex;
        }

        @Override
        public String toString() {
            return "ResolvedArgs[args=" + java.util.Arrays.toString(args) + ", bodyIndex=" + bodyIndex + "]";
        }
    }

    // M2h: all per-request slots live in the RequestScope binding (one per
    // dispatch, virtual-thread-safe). The static accessors below keep their
    // historical signatures and are no-ops / return null outside a dispatch.

    public static void setApplication(jakarta.ws.rs.core.Application app) {
        var s = RequestScope.current();
        if (s != null) s.application = app;
    }
    public static void clearApplication() { setApplication(null); }
    public static jakarta.ws.rs.core.Application currentApplication() {
        var s = RequestScope.current();
        return s == null ? null : s.application;
    }

    /** Allows the Invoker to expose a Providers to resolveContext for the duration of a request. */
    public static void setProviders(Providers p) {
        var s = RequestScope.current();
        if (s != null) s.providers = p;
    }
    public static void clearProviders() { setProviders(null); }
    public static Providers currentProviders() {
        var s = RequestScope.current();
        return s == null ? null : s.providers;
    }

    public static void setParamConverterProviders(java.util.List<jakarta.ws.rs.ext.ParamConverterProvider> ps) {
        var s = RequestScope.current();
        if (s != null) s.paramConverterProviders = ps;
    }
    public static void clearParamConverterProviders() { setParamConverterProviders(null); }

    /** §11.1 (SSE): shared sink+sse for the duration of the invocation
     *  of a resource method with @Produces text/event-stream. */
    public static void setCurrentSink(io.vidocq.cassini.internal.sse.CassiniSseEventSink s) {
        var scope = RequestScope.current();
        if (scope != null) scope.sink = s;
    }
    public static io.vidocq.cassini.internal.sse.CassiniSseEventSink currentSink() {
        var s = RequestScope.current();
        return s == null ? null : s.sink;
    }
    public static void clearCurrentSink() { setCurrentSink(null); }

    private ParamExtractor() {}

    /**
     * Resolves the arguments of a sub-resource locator (§3.4.1) or a resource constructor (§3.1.1).
     * Parameters without a JAX-RS annotation receive their default value
     * (neither can consume the body).
     */
    public static Object[] resolveConstructorArgs(Parameter[] params, MatchResult match, CassiniHttpExchange request) {
        if (params.length == 0) return new Object[0];
        MethodParameters declared = MethodParameters.of(params[0].getDeclaringExecutable());
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            MethodParameters.Param p = declared.get(i);
            args[i] = resolveParam(p, p.encoded(), match, request, null);
        }
        return args;
    }

    public static ResolvedArgs resolve(ResourceMethod route, MatchResult match, CassiniHttpExchange request) {
        MethodParameters declared = MethodParameters.of(route.javaMethod());
        Object[] args = new Object[declared.size()];
        int bodyIndex = -1;
        boolean methodEncoded = route.javaMethod().getAnnotation(jakarta.ws.rs.Encoded.class) != null
                || route.beanClass().getAnnotation(jakarta.ws.rs.Encoded.class) != null;
        RequestValues values = null;

        for (int i = 0; i < args.length; i++) {
            MethodParameters.Param p = declared.get(i);
            MethodParameters.Source source = p.source();
            if (source == MethodParameters.Source.ENTITY && bodyIndex < 0) bodyIndex = i;
            if (source == MethodParameters.Source.QUERY && values == null) values = new RequestValues(request);
            args[i] = resolveParam(p, methodEncoded || p.encoded(), match, request, values);
        }
        return new ResolvedArgs(args, bodyIndex);
    }

    /** The value of one parameter, from where its annotations say it comes from (§3.2). The query
     *  is parsed at most once per request, through {@code values}: when it is null, it is parsed
     *  for this parameter alone. The form is cached on the exchange by {@link #readForm}. */
    private static Object resolveParam(MethodParameters.Param p, boolean encoded, MatchResult match,
                                       CassiniHttpExchange request, RequestValues values) {
        return switch (p.source()) {
            case CONTEXT -> resolveContext(p.type(), match, request);
            // §8.2: @Suspended AsyncResponse — injected from the exchange attribute
            // set by the Invoker before parameter extraction.
            case SUSPENDED -> request.getAttribute(CassiniAsyncResponseImpl.ATTR_KEY);
            case BEAN -> instantiateBeanParam(p.type(), match, request);
            case PATH -> pathValue(p, match, encoded);
            case QUERY -> listValue(p, (values == null ? parsedQueryFromRequest(request, encoded) : values.query(encoded))
                    .getOrDefault(p.name(), List.of()));
            case HEADER -> listValue(p, request.headers(p.name()));
            case COOKIE -> {
                String raw = cookie(request, p.name());
                yield coerce(p, raw == null ? emptyOrDefault(p.defaultValue()) : List.of(raw));
            }
            case FORM -> listValue(p, readForm(request, encoded).getOrDefault(p.name(), List.of()));
            case MATRIX -> listValue(p, matrix(request, p.name(), encoded));
            case ENTITY, NONE -> ParamValueConverter.defaultForType(p.type());
        };
    }

    private static Object pathValue(MethodParameters.Param p, MatchResult match, boolean encoded) {
        // PathSegment: we want segments with matrix params (rawPathParams).
        List<String> raws = (p.wantsPathSegments() ? match.rawPathParams() : match.pathParams())
                .getOrDefault(p.name(), List.of());
        if (raws.isEmpty()) return coerce(p, emptyOrDefault(p.defaultValue()));
        return coerce(p, encoded ? raws : decodePaths(raws));
    }

    /** {@code raws}, or the {@code @DefaultValue} when there is none, converted to the parameter type. */
    private static Object listValue(MethodParameters.Param p, List<String> raws) {
        return coerce(p, raws.isEmpty() ? emptyOrDefault(p.defaultValue()) : raws);
    }

    /** The query of one request, parsed on first use, decoded or not (§3.2 {@code @Encoded}). */
    private static final class RequestValues {
        private final CassiniHttpExchange request;
        private Map<String, List<String>> query;
        private Map<String, List<String>> queryEncoded;

        RequestValues(CassiniHttpExchange request) {
            this.request = request;
        }

        Map<String, List<String>> query(boolean encoded) {
            if (encoded) {
                if (queryEncoded == null) queryEncoded = parsedQueryFromRequest(request, true);
                return queryEncoded;
            }
            if (query == null) query = parsedQueryFromRequest(request, false);
            return query;
        }
    }

    private static Object instantiateBeanParam(Class<?> type, MatchResult match, CassiniHttpExchange request) {
        try {
            Object instance = type.getDeclaredConstructor().newInstance();
            FieldInjector.inject(instance, match, request);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new WebApplicationException("Failed to instantiate @BeanParam "
                    + type.getName() + ": " + e.getMessage(), 500);
        }
    }

    /** The server-side {@code @Context Configuration}: nothing registered, nothing enabled. */
    private static final Configuration SERVER_CONFIGURATION = new Configuration() {
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

    /** Resolves the {@code @Context} value of one type, or null when there is none here. */
    @FunctionalInterface
    private interface ContextValue {
        Object resolve(MatchResult match, CassiniHttpExchange request);
    }

    /** The {@code @Context} types a parameter may declare (§9.2), by exact type. */
    private static final Map<Class<?>, ContextValue> CONTEXT_VALUES = Map.ofEntries(
            Map.entry(UriInfo.class, (match, request) -> new CassiniUriInfo(request, request.contextPath(),
                    match.pathParams(), match.method() == null ? null : match.method().path())),
            Map.entry(HttpHeaders.class, (_, request) -> new CassiniHttpHeaders(request)),
            Map.entry(jakarta.ws.rs.core.Request.class, (_, request) -> new CassiniRequest(request)),
            Map.entry(SecurityContext.class, (_, request) -> securityContext(request)),
            Map.entry(Providers.class, (_, _) -> currentProviders()),
            Map.entry(ContainerRequestContext.class, ParamExtractor::requestContext),
            Map.entry(ResourceInfo.class, (match, _) -> resourceInfo(match.method())),
            Map.entry(Application.class, ParamExtractor::application),
            Map.entry(ContextResolver.class, (_, _) -> currentProviders()),
            Map.entry(ResourceContext.class, ParamExtractor::resourceContext),
            Map.entry(Sse.class, (_, _) -> new io.vidocq.cassini.internal.sse.CassiniSse()),
            Map.entry(SseEventSink.class, (_, _) -> currentSinkOrNew()),
            Map.entry(Configuration.class, (_, _) -> SERVER_CONFIGURATION),
            // The SPI HTTP exchange (useful for tests).
            Map.entry(CassiniHttpExchange.class, (_, request) -> request));

    private static Object resolveContext(Class<?> type, MatchResult match, CassiniHttpExchange request) {
        ContextValue value = CONTEXT_VALUES.get(type);
        Object resolved = value == null ? null : value.resolve(match, request);
        if (resolved == null) {
            throw new WebApplicationException("Unsupported @Context type: " + type.getName(), 500);
        }
        return resolved;
    }

    /** The security context a request filter set, or the one of the exchange. */
    private static Object securityContext(CassiniHttpExchange request) {
        Object filterSc = request.getAttribute(
                io.vidocq.cassini.internal.filter.CassiniRequestContext.ATTR_SECURITY_CONTEXT);
        return filterSc != null ? filterSc : new CassiniSecurityContext(request);
    }

    private static Object requestContext(MatchResult match, CassiniHttpExchange request) {
        var rctx = new io.vidocq.cassini.internal.filter.CassiniRequestContext(
                request, new CassiniUriInfo(request, request.contextPath(), match.pathParams()));
        rctx.markPostMatching();
        rctx.markPostResource();
        return rctx;
    }

    private static ResourceInfo resourceInfo(ResourceMethod route) {
        return new ResourceInfo() {
            @Override public java.lang.reflect.Method getResourceMethod() { return route.javaMethod(); }
            @Override public Class<?> getResourceClass() { return route.beanClass(); }
        };
    }

    /** §9.4: the application instance the harness or the integration published, otherwise a
     *  minimal Application. */
    private static Application application(MatchResult match, CassiniHttpExchange request) {
        Application app = currentApplication();
        if (app == null) return new Application();
        // §9.2: @Context fields on the Application subclass must
        // expose the current context (per-request proxy injection).
        try {
            io.vidocq.cassini.internal.FieldInjector.inject(app, match, request);
        } catch (RuntimeException _) {
            // An Application whose fields cannot be injected is still the application.
        }
        return app;
    }

    /** Minimal stub §6.5.2: per-request injection on a fresh instance. */
    private static ResourceContext resourceContext(MatchResult match, CassiniHttpExchange request) {
        return new ResourceContext() {
            @Override public <T> T getResource(Class<T> resourceClass) {
                try {
                    T r = resourceClass.getDeclaredConstructor().newInstance();
                    io.vidocq.cassini.internal.FieldInjector.inject(r, match, request);
                    return r;
                } catch (ReflectiveOperationException _) { return null; }
            }
            @Override public <T> T initResource(T resource) {
                io.vidocq.cassini.internal.FieldInjector.inject(resource, match, request);
                return resource;
            }
        };
    }

    /** The sink of the current SSE invocation; without one, §11.1 expects a new one. */
    private static Object currentSinkOrNew() {
        var s = currentSink();
        return s != null ? s : new io.vidocq.cassini.internal.sse.CassiniSseEventSink(null);
    }

    private static Object coerce(MethodParameters.Param p, List<String> raws) {
        Class<?> raw = p.type();
        Class<?> element = p.element();
        // §6.1.4: let application ParamConverterProviders act on the
        // type before the ParamValueConverter fallback (constructors, valueOf, ...).
        Object userConverted = tryUserParamConverter(raw, element, p, raws);
        if (userConverted != USE_FALLBACK) return userConverted;
        try {
            return ParamValueConverter.coerce(raw, element, raws);
        } catch (WebApplicationException w) {
            // §3.2 : @PathParam/@QueryParam/@MatrixParam → 404 ; @HeaderParam/@CookieParam → 400.
            if (p.notFoundWhenInvalid() && w.getResponse() != null && w.getResponse().getStatus() == 400) {
                throw new WebApplicationException(w.getMessage(), w.getCause(), javax404Response(404));
            }
            throw w;
        } catch (RuntimeException e) {
            throw new WebApplicationException("Invalid value for parameter "
                    + p.parameter().getName() + ": " + e.getMessage(), e,
                    javax404Response(p.notFoundWhenInvalid() ? 404 : 400));
        }
    }

    /** Builds a stub Response without RuntimeDelegate to preserve the
     *  target status. Used by coerce() because {@code new WebApplicationException(cause, status)}
     *  requires a Response to attach the cause. */
    private static jakarta.ws.rs.core.Response javax404Response(int status) {
        return jakarta.ws.rs.core.Response.status(status).build();
    }

    /** Sentinel: no application ParamConverter handles this type. */
    private static final Object USE_FALLBACK = new Object();

    /** §6.1.4: queries the registered ParamConverterProviders and delegates
     *  conversion if they return a compatible ParamConverter.
     *  Returns {@link #USE_FALLBACK} if no applicable converter exists, otherwise
     *  the converted object (or null if raws is empty and the converter accepts ""). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object tryUserParamConverter(Class<?> raw, Class<?> element, MethodParameters.Param p, List<String> raws) {
        var scope = RequestScope.current();
        List<ParamConverterProvider> pcps = scope == null ? null : scope.paramConverterProviders;
        if (pcps == null || pcps.isEmpty()) return USE_FALLBACK;
        // §6.1.4: we first offer the 'raw' type (collection or direct) then
        // the element type (for List<X> with a converter for X).
        ParamConverter<?> conv = converterFor(pcps, raw, p.genericType(), p.annotations());
        if (conv != null) return fromString(conv, raws.isEmpty() ? null : raws.get(0), p);
        if (element == raw) return USE_FALLBACK;
        conv = converterFor(pcps, element, element, p.annotations());
        if (conv == null) return USE_FALLBACK;
        List<Object> out = new ArrayList<>(raws.size());
        for (String r : raws) out.add(fromString(conv, r, p));
        if (raw == java.util.Set.class) return new java.util.LinkedHashSet<>(out);
        if (raw == java.util.SortedSet.class) return new java.util.TreeSet<>((List) out);
        return out;
    }

    /** The converter of the first provider that offers one for {@code type}, or null. */
    private static ParamConverter<?> converterFor(List<ParamConverterProvider> pcps, Class<?> type,
                                                  java.lang.reflect.Type genericType,
                                                  java.lang.annotation.Annotation[] annotations) {
        for (var pcp : pcps) {
            try {
                ParamConverter<?> conv = pcp.getConverter(type, genericType, annotations);
                if (conv != null) return conv;
            } catch (RuntimeException _) {
                // A provider that fails on this type does not convert it: ask the next one.
            }
        }
        return null;
    }

    private static Object fromString(ParamConverter<?> conv, String value, MethodParameters.Param p) {
        try {
            return conv.fromString(value);
        } catch (RuntimeException e) {
            throw new WebApplicationException("Invalid value for parameter "
                    + p.parameter().getName() + ": " + e.getMessage(), e, javax404Response(400));
        }
    }

    private static List<String> emptyOrDefault(String def) {
        return def == null ? List.of() : List.of(def);
    }

    /** Decodes %XX in path params like {@link URLDecoder} but without replacing
     *  '+' with a space (path ≠ form-urlencoded). */
    private static String decodePath(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException _) { return s; }
    }

    /** {@code raws} decoded with {@link #decodePath}: the same list when none needs it. */
    private static List<String> decodePaths(List<String> raws) {
        for (int i = 0; i < raws.size(); i++) {
            String raw = raws.get(i);
            if (raw != null && raw.indexOf('%') >= 0) {
                List<String> decoded = new ArrayList<>(raws.size());
                for (String r : raws) decoded.add(decodePath(r));
                return decoded;
            }
        }
        return raws;
    }

    private static Map<String, List<String>> parseQuery(String raw, boolean encoded) {
        if (raw == null || raw.isEmpty()) return new LinkedHashMap<>();
        return FormDecoder.parse(raw, !encoded);
    }

    private static Map<String, List<String>> parsedQueryFromRequest(CassiniHttpExchange request, boolean encoded) {
        String raw = request.requestUri() == null ? null : request.requestUri().getRawQuery();
        return parseQuery(raw, encoded);
    }

    private static String cookie(CassiniHttpExchange request, String name) {
        for (String header : request.headers("Cookie")) {
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

    /** The values of the matrix parameter {@code name} in every segment of the request path (§3.2). */
    private static List<String> matrix(CassiniHttpExchange request, String name, boolean encoded) {
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
            if (!URLDecoder.decode(n, StandardCharsets.UTF_8).equals(name)) continue;
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.add(encoded || eq < 0 ? v : URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> readForm(CassiniHttpExchange request, boolean encoded) {
        // We have two distinct caches depending on the mode (decoded vs @Encoded) — stored as exchange attributes (M2h).
        if (encoded) {
            Map<String, List<String>> enc =
                    (Map<String, List<String>>) request.getAttribute(FieldInjector.ATTR_FORM_CACHE_ENCODED);
            if (enc != null) return enc;
        } else {
            Map<String, List<String>> cached =
                    (Map<String, List<String>>) request.getAttribute(FieldInjector.ATTR_FORM_CACHE);
            if (cached != null) return cached;
        }
        try {
            byte[] bytes = (byte[]) request.getAttribute(FieldInjector.ATTR_BODY_CACHE);
            if (bytes == null) {
                var body = request.requestBody();
                bytes = body == null ? new byte[0] : body.readAllBytes();
                request.setAttribute(FieldInjector.ATTR_BODY_CACHE, bytes);
            }
            if (encoded) {
                String s = new String(bytes, StandardCharsets.UTF_8);
                Map<String, List<String>> parsed = FormDecoder.parse(s, false);
                request.setAttribute(FieldInjector.ATTR_FORM_CACHE_ENCODED, parsed);
                return parsed;
            } else {
                Map<String, List<String>> parsed = bytes.length == 0
                        ? new LinkedHashMap<>() : FormDecoder.decode(bytes);
                request.setAttribute(FieldInjector.ATTR_FORM_CACHE, parsed);
                return parsed;
            }
        } catch (Exception e) {
            throw new WebApplicationException("Failed to read form body: " + e.getMessage(), 400);
        }
    }
}
