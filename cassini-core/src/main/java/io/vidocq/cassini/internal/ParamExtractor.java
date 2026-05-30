package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.context.CassiniHttpHeaders;
import io.vidocq.cassini.internal.context.CassiniProviders;
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
import jakarta.ws.rs.ext.Providers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
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

    public record ResolvedArgs(Object[] args, int bodyIndex) {}

    private static ThreadLocal<Providers> CURRENT_PROVIDERS = new ThreadLocal<>();
    // InheritableThreadLocal: inherited by the virtual threads created in the adapter (M2h).
    private static final ThreadLocal<jakarta.ws.rs.core.Application> CURRENT_APPLICATION =
            new InheritableThreadLocal<>();
    public static void setApplication(jakarta.ws.rs.core.Application app) { CURRENT_APPLICATION.set(app); }
    public static void clearApplication() { CURRENT_APPLICATION.remove(); }
    public static jakarta.ws.rs.core.Application currentApplication() { return CURRENT_APPLICATION.get(); }

    /** Allows the Invoker to expose a Providers to resolveContext for the duration of a request. */
    public static void setProviders(Providers p) { CURRENT_PROVIDERS.set(p); }
    public static void clearProviders() { CURRENT_PROVIDERS.remove(); }
    public static Providers currentProviders() { return CURRENT_PROVIDERS.get(); }

    private static final ThreadLocal<java.util.List<jakarta.ws.rs.ext.ParamConverterProvider>> CURRENT_PCPS =
            new ThreadLocal<>();
    public static void setParamConverterProviders(java.util.List<jakarta.ws.rs.ext.ParamConverterProvider> ps) {
        CURRENT_PCPS.set(ps);
    }
    public static void clearParamConverterProviders() { CURRENT_PCPS.remove(); }

    /** §11.1 (SSE): shared sink+sse for the duration of the invocation
     *  of a resource method with @Produces text/event-stream. */
    private static final ThreadLocal<io.vidocq.cassini.internal.sse.CassiniSseEventSink> CURRENT_SINK =
            new ThreadLocal<>();
    public static void setCurrentSink(io.vidocq.cassini.internal.sse.CassiniSseEventSink s) {
        CURRENT_SINK.set(s);
    }
    public static io.vidocq.cassini.internal.sse.CassiniSseEventSink currentSink() {
        return CURRENT_SINK.get();
    }
    public static void clearCurrentSink() { CURRENT_SINK.remove(); }

    private ParamExtractor() {}

    /**
     * Resolves the arguments of a resource constructor (§3.1.1).
     * Parameters without a JAX-RS annotation receive their default value
     * (a constructor cannot consume the body).
     */
    public static Object[] resolveConstructorArgs(Parameter[] params, MatchResult match, CassiniHttpExchange request) {
        Object[] args = new Object[params.length];
        for (int i = 0; i < params.length; i++) {
            args[i] = resolveInjectedParam(params[i], match, request);
        }
        return args;
    }

    private static Object resolveInjectedParam(Parameter p, MatchResult match, CassiniHttpExchange request) {
        String def = defaultValue(p);
        boolean encoded = p.getAnnotation(jakarta.ws.rs.Encoded.class) != null;
        Context context = p.getAnnotation(Context.class);
        if (context != null) return resolveContext(p.getType(), match, request);

        if (p.getAnnotation(jakarta.ws.rs.container.Suspended.class) != null) {
            return request.getAttribute(CassiniAsyncResponseImpl.ATTR_KEY);
        }

        BeanParam beanParam = p.getAnnotation(BeanParam.class);
        if (beanParam != null) return instantiateBeanParam(p.getType(), match, request);

        PathParam pathParam = p.getAnnotation(PathParam.class);
        if (pathParam != null) {
            // PathSegment: we want segments with matrix params (rawPathParams).
            boolean needsRaw = jakarta.ws.rs.core.PathSegment.class.isAssignableFrom(p.getType());
            List<String> raws = (needsRaw ? match.rawPathParams() : match.pathParams())
                    .getOrDefault(pathParam.value(), List.of());
            if (raws.isEmpty()) return coerce(p, emptyOrDefault(def));
            List<String> vals = encoded ? raws : raws.stream().map(ParamExtractor::decodePath).toList();
            return coerce(p, vals);
        }
        QueryParam queryParam = p.getAnnotation(QueryParam.class);
        if (queryParam != null) {
            List<String> raws = parsedQueryFromRequest(request, encoded).getOrDefault(queryParam.value(), List.of());
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        HeaderParam headerParam = p.getAnnotation(HeaderParam.class);
        if (headerParam != null) {
            List<String> raws = request.headers(headerParam.value());
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        CookieParam cookieParam = p.getAnnotation(CookieParam.class);
        if (cookieParam != null) {
            String raw = cookie(request, cookieParam.value());
            return coerce(p, raw == null ? emptyOrDefault(def) : List.of(raw));
        }
        MatrixParam matrixParam = p.getAnnotation(MatrixParam.class);
        if (matrixParam != null) {
            List<String> raws = matrix(request, matrixParam.value(), encoded);
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        FormParam formParam = p.getAnnotation(FormParam.class);
        if (formParam != null) {
            List<String> raws = readForm(request, encoded).getOrDefault(formParam.value(), List.of());
            return coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
        }
        return ParamValueConverter.defaultForType(p.getType());
    }

    public static ResolvedArgs resolve(ResourceMethod route, MatchResult match, CassiniHttpExchange request) {
        Parameter[] params = route.javaMethod().getParameters();
        Object[] args = new Object[params.length];
        int bodyIndex = -1;
        Map<String, List<String>> formCache = null;
        Map<String, List<String>> queryCache = null;
        Map<String, List<String>> formCacheEncoded = null;
        Map<String, List<String>> queryCacheEncoded = null;
        boolean methodEncoded = route.javaMethod().getAnnotation(jakarta.ws.rs.Encoded.class) != null
                || route.beanClass().getAnnotation(jakarta.ws.rs.Encoded.class) != null;

        for (int i = 0; i < params.length; i++) {
            Parameter p = params[i];
            String def = defaultValue(p);
            boolean encoded = methodEncoded || p.getAnnotation(jakarta.ws.rs.Encoded.class) != null;

            PathParam pathParam = p.getAnnotation(PathParam.class);
            QueryParam queryParam = p.getAnnotation(QueryParam.class);
            HeaderParam headerParam = p.getAnnotation(HeaderParam.class);
            CookieParam cookieParam = p.getAnnotation(CookieParam.class);
            FormParam formParam = p.getAnnotation(FormParam.class);
            MatrixParam matrixParam = p.getAnnotation(MatrixParam.class);
            Context context = p.getAnnotation(Context.class);
            BeanParam beanParam = p.getAnnotation(BeanParam.class);

            if (context != null) {
                args[i] = resolveContext(p.getType(), match, request);
                continue;
            }
            // §8.2: @Suspended AsyncResponse — injected from the exchange attribute
            // set by the Invoker before parameter extraction.
            if (p.getAnnotation(jakarta.ws.rs.container.Suspended.class) != null) {
                args[i] = request.getAttribute(CassiniAsyncResponseImpl.ATTR_KEY);
                continue;
            }
            if (beanParam != null) {
                args[i] = instantiateBeanParam(p.getType(), match, request);
                continue;
            }
            if (pathParam != null) {
                boolean needsRaw = jakarta.ws.rs.core.PathSegment.class.isAssignableFrom(p.getType());
                List<String> raws = (needsRaw ? match.rawPathParams() : match.pathParams())
                        .getOrDefault(pathParam.value(), List.of());
                if (raws.isEmpty()) { args[i] = coerce(p, emptyOrDefault(def)); }
                else {
                    List<String> vals = encoded ? raws : raws.stream().map(ParamExtractor::decodePath).toList();
                    args[i] = coerce(p, vals);
                }
            } else if (queryParam != null) {
                Map<String, List<String>> cache;
                if (encoded) {
                    if (queryCacheEncoded == null) queryCacheEncoded = parsedQueryFromRequest(request, true);
                    cache = queryCacheEncoded;
                } else {
                    if (queryCache == null) queryCache = parsedQueryFromRequest(request, false);
                    cache = queryCache;
                }
                List<String> raws = cache.getOrDefault(queryParam.value(), List.of());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (headerParam != null) {
                List<String> raws = request.headers(headerParam.value());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (cookieParam != null) {
                String raw = cookie(request, cookieParam.value());
                args[i] = coerce(p, raw == null ? emptyOrDefault(def) : List.of(raw));
            } else if (formParam != null) {
                Map<String, List<String>> cache;
                if (encoded) {
                    if (formCacheEncoded == null) formCacheEncoded = readForm(request, true);
                    cache = formCacheEncoded;
                } else {
                    if (formCache == null) formCache = readForm(request, false);
                    cache = formCache;
                }
                List<String> raws = cache.getOrDefault(formParam.value(), List.of());
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (matrixParam != null) {
                List<String> raws = matrix(request, matrixParam.value(), encoded);
                args[i] = coerce(p, raws.isEmpty() ? emptyOrDefault(def) : raws);
            } else if (isBodyCandidate(p)) {
                if (bodyIndex < 0) bodyIndex = i;
                args[i] = ParamValueConverter.defaultForType(p.getType());
            } else {
                args[i] = ParamValueConverter.defaultForType(p.getType());
            }
        }
        return new ResolvedArgs(args, bodyIndex);
    }

    /** True if the parameter has no recognized JAX-RS annotation → body candidate. */
    private static boolean isBodyCandidate(Parameter p) {
        for (Annotation a : p.getAnnotations()) {
            Class<? extends Annotation> t = a.annotationType();
            if (t == PathParam.class || t == QueryParam.class || t == HeaderParam.class
                    || t == CookieParam.class || t == FormParam.class || t == MatrixParam.class
                    || t == DefaultValue.class || t == Context.class || t == BeanParam.class) {
                return false;
            }
            if (t.getName().startsWith("jakarta.ws.rs.")) return false;
        }
        return true;
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

    private static Object resolveContext(Class<?> type, MatchResult match, CassiniHttpExchange request) {
        if (type == UriInfo.class) return new CassiniUriInfo(request, request.contextPath(),
                match.pathParams(), match.method() == null ? null : match.method().path());
        if (type == HttpHeaders.class) return new CassiniHttpHeaders(request);
        if (type == jakarta.ws.rs.core.Request.class) return new CassiniRequest(request);
        if (type == SecurityContext.class) {
            Object filterSc = request.getAttribute(
                    io.vidocq.cassini.internal.filter.CassiniRequestContext.ATTR_SECURITY_CONTEXT);
            return filterSc != null ? filterSc : new CassiniSecurityContext(request);
        }
        if (type == Providers.class) {
            Providers p = CURRENT_PROVIDERS.get();
            if (p != null) return p;
        }
        if (type == jakarta.ws.rs.container.ContainerRequestContext.class) {
            var rctx = new io.vidocq.cassini.internal.filter.CassiniRequestContext(
                    request, new CassiniUriInfo(request, request.contextPath(), match.pathParams()));
            rctx.markPostMatching();
            rctx.markPostResource();
            return rctx;
        }
        if (type == jakarta.ws.rs.container.ResourceInfo.class) {
            var route = match.method();
            return new jakarta.ws.rs.container.ResourceInfo() {
                @Override public java.lang.reflect.Method getResourceMethod() { return route.javaMethod(); }
                @Override public Class<?> getResourceClass() { return route.beanClass(); }
            };
        }
        if (type == jakarta.ws.rs.core.Application.class) {
            // §9.4: Application is the user-level instance if the harness
            // (or integration) published one via setCurrentApplication()
            // — otherwise we return a minimal Application.
            jakarta.ws.rs.core.Application app = CURRENT_APPLICATION.get();
            if (app != null) {
                // §9.2: @Context fields on the Application subclass must
                // expose the current context (per-request proxy injection).
                try { io.vidocq.cassini.internal.FieldInjector.inject(app, match, request); }
                catch (RuntimeException ignored) {}
                return app;
            }
            return new jakarta.ws.rs.core.Application();
        }
        if (type == jakarta.ws.rs.ext.ContextResolver.class) {
            Providers p = CURRENT_PROVIDERS.get();
            if (p != null) return p;
        }
        if (type == jakarta.ws.rs.container.ResourceContext.class) {
            // Minimal stub §6.5.2: per-request injection on a fresh instance.
            return new jakarta.ws.rs.container.ResourceContext() {
                @Override public <T> T getResource(Class<T> resourceClass) {
                    try {
                        T r = resourceClass.getDeclaredConstructor().newInstance();
                        io.vidocq.cassini.internal.FieldInjector.inject(r, match, request);
                        return r;
                    } catch (ReflectiveOperationException e) { return null; }
                }
                @Override public <T> T initResource(T resource) {
                    io.vidocq.cassini.internal.FieldInjector.inject(resource, match, request);
                    return resource;
                }
            };
        }
        if (type == jakarta.ws.rs.sse.Sse.class) {
            return new io.vidocq.cassini.internal.sse.CassiniSse();
        }
        if (type == jakarta.ws.rs.sse.SseEventSink.class) {
            var s = CURRENT_SINK.get();
            if (s != null) return s;
            // No current sink: §11.1 expects us to construct a new one.
            return new io.vidocq.cassini.internal.sse.CassiniSseEventSink(null);
        }
        if (type == jakarta.ws.rs.core.Configuration.class) {
            return new jakarta.ws.rs.core.Configuration() {
                @Override public jakarta.ws.rs.RuntimeType getRuntimeType() { return jakarta.ws.rs.RuntimeType.SERVER; }
                @Override public java.util.Map<String, Object> getProperties() { return java.util.Map.of(); }
                @Override public Object getProperty(String name) { return null; }
                @Override public java.util.Collection<String> getPropertyNames() { return java.util.List.of(); }
                @Override public boolean isEnabled(jakarta.ws.rs.core.Feature feature) { return false; }
                @Override public boolean isEnabled(Class<? extends jakarta.ws.rs.core.Feature> featureClass) { return false; }
                @Override public boolean isRegistered(Object component) { return false; }
                @Override public boolean isRegistered(Class<?> componentClass) { return false; }
                @Override public java.util.Map<Class<?>, Integer> getContracts(Class<?> componentClass) { return java.util.Map.of(); }
                @Override public java.util.Set<Class<?>> getClasses() { return java.util.Set.of(); }
                @Override public java.util.Set<Object> getInstances() { return java.util.Set.of(); }
            };
        }
        if (type == CassiniHttpExchange.class) return request; // SPI HTTP exchange (useful for tests)
        throw new WebApplicationException("Unsupported @Context type: " + type.getName(), 500);
    }

    private static Object coerce(Parameter p, List<String> raws) {
        Class<?> raw = p.getType();
        Class<?> element = ParamValueConverter.isListLike(raw)
                ? genericElementType(p.getParameterizedType())
                : raw;
        boolean notFoundParam = p.getAnnotation(PathParam.class) != null
                || p.getAnnotation(MatrixParam.class) != null
                || p.getAnnotation(QueryParam.class) != null;
        // §6.1.4: let application ParamConverterProviders act on the
        // type before the ParamValueConverter fallback (constructors, valueOf, ...).
        Object userConverted = tryUserParamConverter(raw, element, p, raws);
        if (userConverted != USE_FALLBACK) return userConverted;
        try {
            return ParamValueConverter.coerce(raw, element, raws);
        } catch (WebApplicationException w) {
            // §3.2 : @PathParam/@QueryParam/@MatrixParam → 404 ; @HeaderParam/@CookieParam → 400.
            if (notFoundParam && w.getResponse() != null && w.getResponse().getStatus() == 400) {
                throw new WebApplicationException(w.getMessage(), w.getCause(), javax404Response(404));
            }
            throw w;
        } catch (RuntimeException e) {
            throw new WebApplicationException("Invalid value for parameter "
                    + p.getName() + ": " + e.getMessage(), e,
                    javax404Response(notFoundParam ? 404 : 400));
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
    private static Object tryUserParamConverter(Class<?> raw, Class<?> element, Parameter p, List<String> raws) {
        java.util.List<jakarta.ws.rs.ext.ParamConverterProvider> pcps = CURRENT_PCPS.get();
        if (pcps == null || pcps.isEmpty()) return USE_FALLBACK;
        // §6.1.4: we first offer the 'raw' type (collection or direct) then
        // the element type (for List<X> with a converter for X).
        Class<?> targetType = raw;
        java.lang.reflect.Type targetGeneric = p.getParameterizedType();
        java.lang.annotation.Annotation[] anns = p.getAnnotations();
        jakarta.ws.rs.ext.ParamConverter conv = null;
        for (var pcp : pcps) {
            try {
                conv = pcp.getConverter(targetType, targetGeneric, anns);
                if (conv != null) break;
            } catch (RuntimeException ignored) {}
        }
        if (conv == null && element != raw) {
            // collection-like: try on the element
            for (var pcp : pcps) {
                try {
                    conv = pcp.getConverter(element, element, anns);
                    if (conv != null) break;
                } catch (RuntimeException ignored) {}
            }
            if (conv != null) {
                java.util.List<Object> out = new java.util.ArrayList<>();
                for (String r : raws) {
                    try { out.add(conv.fromString(r)); }
                    catch (RuntimeException e) {
                        throw new WebApplicationException("Invalid value for parameter "
                                + p.getName() + ": " + e.getMessage(), e, javax404Response(400));
                    }
                }
                if (raw == java.util.Set.class) return new java.util.LinkedHashSet<>(out);
                if (raw == java.util.SortedSet.class) return new java.util.TreeSet<>((java.util.List) out);
                return out;
            }
            return USE_FALLBACK;
        }
        if (conv == null) return USE_FALLBACK;
        String value = raws.isEmpty() ? null : raws.get(0);
        try {
            return conv.fromString(value);
        } catch (RuntimeException e) {
            throw new WebApplicationException("Invalid value for parameter "
                    + p.getName() + ": " + e.getMessage(), e, javax404Response(400));
        }
    }

    private static List<String> emptyOrDefault(String def) {
        return def == null ? List.of() : List.of(def);
    }

    private static String defaultValue(Parameter p) {
        DefaultValue d = p.getAnnotation(DefaultValue.class);
        return d == null ? null : d.value();
    }

    private static Class<?> genericElementType(Type t) {
        if (t instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
            return c;
        }
        return String.class;
    }

    /** Decodes %XX in path params like {@link URLDecoder} but without replacing
     *  '+' with a space (path ≠ form-urlencoded). */
    private static String decodePath(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try { return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8); }
        catch (Exception e) { return s; }
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

    private static List<String> matrix(CassiniHttpExchange request, String name, boolean encoded) {
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

    private static Map<String, List<String>> readForm(CassiniHttpExchange request) {
        return readForm(request, false);
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
