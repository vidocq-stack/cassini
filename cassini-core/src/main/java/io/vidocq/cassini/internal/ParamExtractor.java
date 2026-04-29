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
 * Résout les arguments d'appel d'une méthode de ressource à partir d'une
 * {@link Request} Chappe + d'un {@link MatchResult} routeur.
 *
 * <p>M2b : support de {@link PathParam}, {@link QueryParam}, {@link HeaderParam},
 * {@link CookieParam}, {@link FormParam}, {@link MatrixParam},
 * {@link DefaultValue}. Les paramètres sans annotation JAX-RS sont
 * considérés comme le corps de la requête — leur index est exposé via
 * {@link ResolvedArgs#bodyIndex} et sera rempli par l'Invoker via un
 * {@link MessageBodyRegistry}.</p>
 */
public final class ParamExtractor {

    public record ResolvedArgs(Object[] args, int bodyIndex) {}

    private static ThreadLocal<Providers> CURRENT_PROVIDERS = new ThreadLocal<>();
    // InheritableThreadLocal : hérité par les virtual threads créés dans l'adapter (M2h).
    private static final ThreadLocal<jakarta.ws.rs.core.Application> CURRENT_APPLICATION =
            new InheritableThreadLocal<>();
    public static void setApplication(jakarta.ws.rs.core.Application app) { CURRENT_APPLICATION.set(app); }
    public static void clearApplication() { CURRENT_APPLICATION.remove(); }
    public static jakarta.ws.rs.core.Application currentApplication() { return CURRENT_APPLICATION.get(); }

    /** Permet à l'Invoker d'exposer un Providers au resolveContext pour la durée d'une requête. */
    public static void setProviders(Providers p) { CURRENT_PROVIDERS.set(p); }
    public static void clearProviders() { CURRENT_PROVIDERS.remove(); }
    public static Providers currentProviders() { return CURRENT_PROVIDERS.get(); }

    private static final ThreadLocal<java.util.List<jakarta.ws.rs.ext.ParamConverterProvider>> CURRENT_PCPS =
            new ThreadLocal<>();
    public static void setParamConverterProviders(java.util.List<jakarta.ws.rs.ext.ParamConverterProvider> ps) {
        CURRENT_PCPS.set(ps);
    }
    public static void clearParamConverterProviders() { CURRENT_PCPS.remove(); }

    /** §11.1 (SSE) : sink+sse partagés pour la durée de l'invocation
     *  d'une méthode resource avec @Produces text/event-stream. */
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
     * Résout les arguments d'un constructeur de ressource (§3.1.1).
     * Les paramètres sans annotation JAX-RS reçoivent leur valeur par défaut
     * (un constructeur ne peut pas consommer le body).
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
            // PathSegment : on veut les segments avec matrix params (rawPathParams).
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
            // §8.2 : @Suspended AsyncResponse — injecté depuis l'attribut exchange
            // posé par l'Invoker avant l'extraction des params.
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

    /** Vrai si le paramètre n'a aucune annotation JAX-RS reconnue → candidat body. */
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
        if (type == SecurityContext.class) return new CassiniSecurityContext(request);
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
            // §9.4 : l'Application est l'instance user-level si l'harness
            // (ou l'intégration) en a publié une via setCurrentApplication()
            // — sinon on retourne une Application minimale.
            jakarta.ws.rs.core.Application app = CURRENT_APPLICATION.get();
            if (app != null) {
                // §9.2 : @Context fields de la sous-classe Application doivent
                // exposer le contexte courant (proxy injection per-request).
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
            // Stub minimal §6.5.2 : per-request injection sur instance neuve.
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
            // Pas de sink courant : §11.1 attend qu'on en construise un nouveau.
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
        if (type == CassiniHttpExchange.class) return request; // SPI HTTP exchange (utile pour tests)
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
        // §6.1.4 : laisser les ParamConverterProvider applicatifs agir sur le
        // type avant le fallback ParamValueConverter (constructeurs, valueOf,…).
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

    /** Construit une Response stub sans RuntimeDelegate pour conserver le
     *  statut cible. Utilisé par coerce() car {@code new WebApplicationException(cause, status)}
     *  nécessite une Response pour joindre la cause. */
    private static jakarta.ws.rs.core.Response javax404Response(int status) {
        return jakarta.ws.rs.core.Response.status(status).build();
    }

    /** Sentinelle : aucun ParamConverter applicatif ne gère ce type. */
    private static final Object USE_FALLBACK = new Object();

    /** §6.1.4 : interroge les ParamConverterProvider enregistrés et délègue la
     *  conversion s'ils retournent un ParamConverter compatible.
     *  Retourne {@link #USE_FALLBACK} si aucun converter applicable, sinon
     *  l'objet converti (ou null si raws est vide et le converter accepte ""). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object tryUserParamConverter(Class<?> raw, Class<?> element, Parameter p, List<String> raws) {
        java.util.List<jakarta.ws.rs.ext.ParamConverterProvider> pcps = CURRENT_PCPS.get();
        if (pcps == null || pcps.isEmpty()) return USE_FALLBACK;
        // §6.1.4 : on offre d'abord le type "raw" (collection ou direct) puis
        // l'element type (pour List<X> avec un converter pour X).
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
            // collection-like : essayer sur l'élément
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

    /** Décode %XX de path params comme {@link URLDecoder} mais sans remplacer
     *  '+' par espace (path ≠ form-urlencoded). */
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
        // On a deux caches distincts selon le mode (decoded vs @Encoded) — stockés en attributs exchange (M2h).
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
