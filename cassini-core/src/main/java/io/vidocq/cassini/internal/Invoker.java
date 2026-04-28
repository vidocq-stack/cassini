package io.vidocq.cassini.internal;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import io.vidocq.cassini.internal.context.CassiniUriInfo;
import io.vidocq.cassini.internal.filter.CassiniReaderInterceptorContext;
import io.vidocq.cassini.internal.filter.CassiniRequestContext;
import io.vidocq.cassini.internal.filter.CassiniResponseContext;
import io.vidocq.cassini.internal.filter.CassiniWriterInterceptorContext;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Exécute une {@link ResourceMethod} : content-negotiation, lecture du
 * corps via {@link MessageBodyReader}, invocation, sérialisation via
 * {@link MessageBodyWriter}.
 */
public final class Invoker {

    /** Contexte exposé pendant l'appel {@link #resolver} pour permettre à des
     *  résolveurs (ex. harness TCK) de créer des instances avec injection
     *  constructeur §3.1.1. */
    public static final ThreadLocal<MatchResult> CURRENT_MATCH = new ThreadLocal<>();
    public static final ThreadLocal<Request> CURRENT_REQUEST = new ThreadLocal<>();
    /** §6.5 (UriInfo.getMatchedResources) : chaîne d'instances de ressource
     *  matchées pour la requête courante (root → la plus profonde via locators). */
    public static final ThreadLocal<java.util.List<Object>> CURRENT_MATCHED_RESOURCES =
            ThreadLocal.withInitial(java.util.ArrayList::new);

    private final Function<Class<?>, Object> resolver;
    private final MessageBodyRegistry registry;
    private final ExceptionMapperRegistry exceptionMappers;
    private FilterRegistry filters = new FilterRegistry();

    public void setFilters(FilterRegistry f) { this.filters = f == null ? new FilterRegistry() : f; }
    public FilterRegistry filters() { return filters; }

    public Invoker(Function<Class<?>, Object> resolver) {
        this(resolver, new MessageBodyRegistry(), new ExceptionMapperRegistry());
    }

    public Invoker(Function<Class<?>, Object> resolver, MessageBodyRegistry registry) {
        this(resolver, registry, new ExceptionMapperRegistry());
    }

    public Invoker(Function<Class<?>, Object> resolver, MessageBodyRegistry registry,
                   ExceptionMapperRegistry exceptionMappers) {
        this.resolver = resolver;
        this.registry = registry;
        this.exceptionMappers = exceptionMappers;
    }

    public MessageBodyRegistry registry() { return registry; }
    public ExceptionMapperRegistry exceptionMappers() { return exceptionMappers; }

    public static Invoker forBeanManager(BeanManager bm) {
        return forBeanManager(bm, new MessageBodyRegistry());
    }

    public static Invoker forBeanManager(BeanManager bm, MessageBodyRegistry registry) {
        return forBeanManager(bm, registry, ExceptionMapperRegistry.discover(bm));
    }

    /** §3.1.1 : instancie une classe @Path sans bean CDI via l'API standard
     *  {@code BeanManager.getInjectionTargetFactory(AnnotatedType)} — produce
     *  + inject + postConstruct. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object instantiateWithInjection(BeanManager bm, Class<?> type) {
        try {
            jakarta.enterprise.inject.spi.AnnotatedType at = bm.createAnnotatedType(type);
            jakarta.enterprise.inject.spi.InjectionTargetFactory factory = bm.getInjectionTargetFactory(at);
            jakarta.enterprise.inject.spi.InjectionTarget it = factory.createInjectionTarget(null);
            jakarta.enterprise.context.spi.CreationalContext cc = bm.createCreationalContext(null);
            Object instance = it.produce(cc);
            it.inject(instance, cc);
            it.postConstruct(instance);
            return instance;
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to instantiate " + type.getName(), e);
        }
    }

    public static Invoker forBeanManager(BeanManager bm, MessageBodyRegistry registry,
                                         ExceptionMapperRegistry exMappers) {
        AnnotationLiteral<Any> any = new AnnotationLiteral<Any>() {};
        return new Invoker(type -> {
            Set<Bean<?>> beans = bm.getBeans(type, any);
            Bean<?> bean = bm.resolve(beans);
            if (bean != null) {
                var cc = bm.createCreationalContext(bean);
                return bm.getReference(bean, type, cc);
            }
            // §3.1.1 : une classe @Path sans bean CDI → on instancie per-request
            // via InjectionTargetFactory (constructeur + @Inject fields).
            return instantiateWithInjection(bm, type);
        }, registry, exMappers);
    }

    public Response invoke(MatchResult match, Request request) throws Exception {
        return invoke(java.util.List.of(match), request);
    }

    /** Résout la meilleure route parmi les candidats en fonction des Accept/
     *  Content-Type de la requête (§3.7.2). Passe à invoke(MatchResult, Request)
     *  canonique avec le gagnant. */
    public Response invoke(java.util.List<MatchResult> candidates, Request request) throws Exception {
        if (candidates.isEmpty()) throw new IllegalArgumentException("no candidates");
        MatchResult match = pickBestMatch(candidates, request);
        ResourceMethod route = match.method();
        ParamExtractor.setProviders(new io.vidocq.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        ParamExtractor.setParamConverterProviders(filters.paramConverterProviders());
        try {
            try {
                java.net.URI u = request.uri();
                String scheme = u != null && u.getScheme() != null ? u.getScheme()
                        : (request.isSecure() ? "https" : "http");
                String authority = u != null && u.getAuthority() != null ? u.getAuthority() : null;
                if (authority == null) {
                    String host = request.headers().firstOrNull("Host");
                    if (host != null && !host.isEmpty()) authority = host;
                }
                if (authority == null) authority = "127.0.0.1";
                String ctx = request.contextPath();
                if (ctx == null) ctx = "";
                String basePath = ctx.isEmpty() ? "/" : ctx + "/";
                java.net.URI base = new java.net.URI(scheme + "://" + authority + basePath);
                io.vidocq.cassini.internal.runtime.CassiniResponseBuilder.setBaseUri(base);
            } catch (Exception ignored) {}
            return invokeInternal(match, request, route);
        } finally {
            ParamExtractor.clearProviders();
            ParamExtractor.clearParamConverterProviders();
            FieldInjector.clearFormCache();
            CURRENT_MATCH.remove();
            CURRENT_REQUEST.remove();
            CURRENT_MATCHED_RESOURCES.remove();
            io.vidocq.cassini.internal.runtime.CassiniResponseBuilder.clearBaseUri();
        }
    }

    /**
     * §6.6.1 : résultat de l'exécution des pre-matching filters.
     * Contient soit une {@code response} d'arrêt (abortWith ou exception
     * mappée), soit (si {@code response == null}) le contexte mutable utilisé
     * pour relancer le routing avec method/URI éventuellement modifiés.
     */
    public record PreMatchResult(Response response, CassiniRequestContext ctx) {}

    /** §6.6.1 : exécute les pre-matching filters avant le routing. */
    public PreMatchResult runPreMatching(Request request) throws Exception {
        if (filters.preMatching().isEmpty()) return new PreMatchResult(null, null);
        ParamExtractor.setProviders(new io.vidocq.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        try {
            CassiniRequestContext preCtx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), java.util.Map.of()));
            for (var fe : filters.preMatching()) {
                try { fe.instance().filter(preCtx); }
                catch (java.io.IOException | RuntimeException e) {
                    Response mapped = mapFilterThrowable(e, null, null, preCtx);
                    if (mapped != null) return new PreMatchResult(mapped, preCtx);
                    if (e instanceof RuntimeException re) throw re;
                    throw new RuntimeException(e);
                }
                if (preCtx.isAborted()) {
                    return new PreMatchResult(
                            runResponseFiltersAndWrite(preCtx, preCtx.abortedResponse(), null, null),
                            preCtx);
                }
            }
            return new PreMatchResult(null, preCtx);
        } finally {
            ParamExtractor.clearProviders();
        }
    }

    private Response invokeInternal(MatchResult match, Request request, ResourceMethod route) throws Exception {

        // 0. Pre-matching request filters §6.6 — exécutés avant toute
        //    négociation. S'ils throw, on passe par ExceptionMapper.
        CassiniRequestContext preCtx = null;
        if (!filters.preMatching().isEmpty()) {
            preCtx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), match.pathParams()));
            for (var fe : filters.preMatching()) {
                try { fe.instance().filter(preCtx); }
                catch (java.io.IOException e) {
                    Response mapped = mapFilterThrowable(e, route, null, preCtx);
                    if (mapped != null) return mapped;
                    throw e;
                } catch (RuntimeException e) {
                    Response mapped = mapFilterThrowable(e, route, null, preCtx);
                    if (mapped != null) return mapped;
                    throw e;
                }
                if (preCtx.isAborted()) {
                    return runResponseFiltersAndWrite(preCtx, preCtx.abortedResponse(), route, null);
                }
            }
        }

        // 1. Negotiation
        String ctHeader = request.headers().firstOrNull("Content-Type");
        MediaType contentType = MediaTypes.parse(ctHeader);
        List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        // §3.7.2 : si la requête a un Content-Type ou un body, filtre sur @Consumes.
        boolean checkConsumes = hasRequestBody(request) || ctHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(contentType, consumes)) {
            // §3.7.2 : 415 via WAE pour laisser l'ExceptionMapper intercepter.
            return renderWebAppException(new jakarta.ws.rs.NotSupportedException(), route, null, null);
        }

        List<MediaType> accepts = MediaTypes.parseList(request.headers().firstOrNull("Accept"));
        List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(accepts, produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return renderWebAppException(new jakarta.ws.rs.NotAcceptableException(), route, null, null);
        }
        MediaType chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);

        // 2. Resolve args
        ParamExtractor.ResolvedArgs resolved;
        Object[] args;
        try {
            resolved = ParamExtractor.resolve(route, match, request);
            args = resolved.args();
            if (resolved.bodyIndex() >= 0) {
                Parameter p = route.javaMethod().getParameters()[resolved.bodyIndex()];
                // §4.2.4 : Standard providers DOIVENT retourner un objet non
                // null même pour un body vide (String=="", byte[]=new byte[0],
                // InputStream=empty stream, …). On lit donc toujours via MBR
                // quand un param body est présent.
                // §4.2.4 (suite) : pour la sélection du MBR, le défaut quand
                // Content-Type est absent est application/octet-stream.
                MediaType readMt = (ctHeader == null) ? MediaType.APPLICATION_OCTET_STREAM_TYPE : contentType;
                args[resolved.bodyIndex()] = readEntity(p, readMt, request, route);
            }
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, null);
        } catch (RuntimeException | java.io.IOException re) {
            // §4.4 : les MessageBodyReader / ReaderInterceptor peuvent lever
            // (RuntimeException ou IOException) → on tente l'ExceptionMapper.
            Response mapped = mapFilterThrowable(re, route, chosen, preCtx);
            if (mapped != null) return mapped;
            if (re instanceof RuntimeException rrt) throw rrt;
            throw new RuntimeException(re);
        }

        // 3. Post-matching request filters — marquer le contexte comme
        //    post-matching avant leur exécution pour que les setters
        //    protégés (setRequestUri, setMethod, etc.) lèvent ISE §6.6.
        CassiniRequestContext rctx = null;
        if (!filters.postMatching().isEmpty() || !filters.responseFilters().isEmpty()) {
            rctx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), match.pathParams()));
            rctx.markPostMatching();
            for (var fe : filters.postMatching()) {
                if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
                try { fe.instance().filter(rctx); }
                catch (java.io.IOException e) {
                    Response mapped = mapFilterThrowable(e, route, chosen, rctx);
                    if (mapped != null) return mapped;
                    throw new RuntimeException(e);
                } catch (RuntimeException e) {
                    Response mapped = mapFilterThrowable(e, route, chosen, rctx);
                    if (mapped != null) return mapped;
                    throw e;
                }
                if (rctx.isAborted()) {
                    return runResponseFiltersAndWrite(rctx, rctx.abortedResponse(), route, chosen);
                }
            }
        }

        // 4. Invoke
        Object target;
        CURRENT_MATCH.set(match);
        CURRENT_REQUEST.set(request);
        java.util.List<Object> matched = new java.util.ArrayList<>();
        CURRENT_MATCHED_RESOURCES.set(matched);
        try {
            if (route.isLocated()) {
                // Sub-resource locator §3.4.1 : instantier la ressource racine,
                // parcourir la chaîne de locators, injecter fields à chaque étape.
                Object root = resolver.apply(route.rootBeanClass());
                FieldInjector.inject(root, match, request);
                matched.add(0, root);
                Object intermediate = root;
                for (java.lang.reflect.Method locStep : route.locatorChain()) {
                    java.lang.reflect.Parameter[] lps = locStep.getParameters();
                    Object[] lArgs = lps.length == 0 ? new Object[0]
                            : ParamExtractor.resolveConstructorArgs(lps, match, request);
                    locStep.setAccessible(true);
                    intermediate = locStep.invoke(intermediate, lArgs);
                    if (intermediate == null) {
                        return renderWebAppException(
                                new WebApplicationException("Sub-resource locator returned null", 404),
                                route, chosen, rctx);
                    }
                    // §3.4.2 : un locator peut retourner Class<T> — runtime
                    // instancie la classe via constructor no-arg.
                    if (intermediate instanceof Class<?> cls) {
                        try { intermediate = cls.getDeclaredConstructor().newInstance(); }
                        catch (ReflectiveOperationException e) {
                            return renderWebAppException(
                                    new WebApplicationException(
                                            "Cannot instantiate sub-resource " + cls.getName() + ": " + e.getMessage(),
                                            500),
                                    route, chosen, rctx);
                        }
                    }
                    FieldInjector.inject(intermediate, match, request);
                    matched.add(0, intermediate);
                }
                target = intermediate;
            } else {
                target = resolver.apply(route.beanClass());
                FieldInjector.inject(target, match, request);
                matched.add(target);
            }
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, rctx);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof WebApplicationException wae) {
                return renderWebAppException(wae, route, chosen, rctx);
            }
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
        Object result;
        // §11.1 : si la méthode a un paramètre SseEventSink, on remplace
        // l'arg par notre instance (CassiniSseEventSink) et on capture le
        // résultat sérialisé en response après l'invocation.
        io.vidocq.cassini.internal.sse.CassiniSseEventSink sseSink = null;
        Parameter[] params = route.javaMethod().getParameters();
        for (int pi = 0; pi < params.length; pi++) {
            if (params[pi].getType() == jakarta.ws.rs.sse.SseEventSink.class) {
                sseSink = new io.vidocq.cassini.internal.sse.CassiniSseEventSink(registry);
                args[pi] = sseSink;
                ParamExtractor.setCurrentSink(sseSink);
                break;
            }
        }
        try {
            result = route.javaMethod().invoke(target, args);
        } catch (IllegalArgumentException iae) {
            StringBuilder sb = new StringBuilder("Argument mismatch on ")
                    .append(route.beanClass().getName()).append('.')
                    .append(route.javaMethod().getName()).append("(): ");
            Parameter[] ps = route.javaMethod().getParameters();
            for (int i = 0; i < ps.length; i++) {
                sb.append("\n  [").append(i).append("] param=").append(ps[i].getType().getSimpleName())
                        .append(" arg=").append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
            }
            throw new RuntimeException(sb.toString(), iae);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            // §4.3.1 : WAE avant le mapper — renderWebAppException gère la logique
            // "entité présente → pas de mapper, pas d'entité → mapper si disponible".
            if (cause instanceof WebApplicationException wae) {
                return renderWebAppException(wae, route, chosen, rctx);
            }
            var mapped = exceptionMappers.map(cause);
            if (mapped.isPresent()) return runResponseFiltersAndWrite(rctx, mapped.get(), route, chosen);
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }

        // §11.1 : méthode SSE → on retourne le contenu bufferisé du sink
        // comme corps text/event-stream (la méthode a typiquement un
        // return type void et c'est le sink qui contient les events).
        if (sseSink != null) {
            ParamExtractor.clearCurrentSink();
            byte[] body = sseSink.toByteArray();
            return Response.builder()
                    .status(StatusCode.OK)
                    .header("Content-Type", "text/event-stream")
                    .body(Body.of(body))
                    .build();
        }
        // 5. Marshal + response filters — les WriterInterceptor peuvent
        //    lancer des exceptions : on les route via ExceptionMapper.
        try {
            if (rctx != null && !filters.responseFilters().isEmpty()) {
                return runResponseFiltersForResult(rctx, result, route, chosen);
            }
            return marshal(result, route, chosen);
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, rctx);
        } catch (RuntimeException re) {
            Response mapped = mapFilterThrowable(re, route, chosen, rctx);
            if (mapped != null) return mapped;
            throw re;
        }
    }

    private Response runResponseFiltersAndWrite(CassiniRequestContext rctx,
                                                jakarta.ws.rs.core.Response userResp,
                                                ResourceMethod route, MediaType chosen) throws IOException {
        int status = userResp.getStatus();
        Object entity = userResp.getEntity();
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        for (var e : userResp.getStringHeaders().entrySet()) for (String v : e.getValue()) headers.add(e.getKey(), v);

        CassiniResponseContext rctx2 = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), headers);
        for (var fe : filters.responseFilters()) {
            // route null = pré-matching abort : on applique les filtres globaux,
            // pas le filtrage par méthode.
            if (route != null && !fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
            Throwable[] err = new Throwable[1];
            rctx.runDuringResponsePhase(() -> {
                try { fe.instance().filter(rctx, rctx2); }
                catch (java.io.IOException | RuntimeException e) { err[0] = e; }
            });
            if (err[0] instanceof RuntimeException re) throw re;
            if (err[0] != null) throw new RuntimeException(err[0]);
        }
        return writeFromContext(rctx2, route, chosen);
    }

    private Response runResponseFiltersForResult(CassiniRequestContext rctx, Object result,
                                                  ResourceMethod route, MediaType chosen) throws IOException {
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        int status = (result == null) ? 204 : 200;
        Object entity = (result instanceof jakarta.ws.rs.core.Response jr) ? jr.getEntity() : result;
        java.lang.annotation.Annotation[] entityAnnotations = null;
        if (result instanceof jakarta.ws.rs.core.Response jr2) {
            status = jr2.getStatus();
            for (var e : jr2.getStringHeaders().entrySet()) for (String v : e.getValue()) headers.add(e.getKey(), v);
            // Les annotations de l'entity sont internes au CassiniResponse
            // (§6.7.4 : exposées via ContainerResponseContext.getEntityAnnotations).
            if (jr2 instanceof io.vidocq.cassini.internal.runtime.CassiniResponse cr) {
                entityAnnotations = cr.entityAnnotations();
            }
        }
        // §6.7.4 : getEntityAnnotations() retourne les annotations de la
        // méthode resource fusionnées avec celles passées explicitement à
        // ResponseBuilder.entity(Object, Annotation[]).
        if (route.javaMethod() != null) {
            Annotation[] methodAnns = route.javaMethod().getAnnotations();
            if (entityAnnotations == null || entityAnnotations.length == 0) {
                entityAnnotations = methodAnns;
            } else {
                Annotation[] merged = new Annotation[methodAnns.length + entityAnnotations.length];
                System.arraycopy(methodAnns, 0, merged, 0, methodAnns.length);
                System.arraycopy(entityAnnotations, 0, merged, methodAnns.length, entityAnnotations.length);
                entityAnnotations = merged;
            }
        }

        CassiniResponseContext rctx2 = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), entityAnnotations, headers);
        for (var fe : filters.responseFilters()) {
            if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
            Throwable[] err = new Throwable[1];
            rctx.runDuringResponsePhase(() -> {
                try { fe.instance().filter(rctx, rctx2); }
                catch (java.io.IOException | RuntimeException e) { err[0] = e; }
            });
            if (err[0] instanceof RuntimeException re) throw re;
            if (err[0] != null) throw new RuntimeException(err[0]);
        }
        return writeFromContext(rctx2, route, chosen);
    }

    private Response writeFromContext(CassiniResponseContext rctx, ResourceMethod route, MediaType chosen) throws IOException {
        Object entity = rctx.getEntity();
        int status = rctx.getStatus();
        MultivaluedMap<String, Object> headers = rctx.getHeaders();
        if (entity == null) {
            var b = Response.builder().status(StatusCode.of(status)).body(Body.empty());
            for (var e : headers.entrySet()) for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
            return b.build();
        }
        MediaType mt = rctx.getMediaType() != null ? rctx.getMediaType() : chosen;
        // §6.7.4.2 : si un ContainerResponseFilter a wrappé l'entityStream
        // via setEntityStream(), MBW.writeTo doit écrire dans ce wrapper —
        // le wrapper forwarde vers le originalStream que le runtime collecte.
        if (rctx.getEntityStream() != rctx.originalStream()) {
            Class<?> type = entity.getClass();
            Type gt = rctx.getEntityType() == null ? type : rctx.getEntityType();
            Annotation[] anns = rctx.getEntityAnnotations() == null
                    ? new Annotation[0] : rctx.getEntityAnnotations();
            @SuppressWarnings({"rawtypes", "unchecked"})
            jakarta.ws.rs.ext.MessageBodyWriter writer = registry.findWriter(type, gt, anns, mt)
                    .orElseThrow(() -> new WebApplicationException(
                            "No MessageBodyWriter for " + type.getName() + " / " + MediaTypes.format(mt), 500));
            injectProviderContexts(writer, null);
            MultivaluedMap<String, Object> outHeaders = MessageBodyRegistry.outHeaders();
            for (var e : headers.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
                for (Object v : e.getValue()) outHeaders.add(e.getKey(), v);
            }
            MessageBodyRegistry.writeTo(writer, entity, type, gt, anns, mt, outHeaders, rctx.getEntityStream());
            try { rctx.getEntityStream().close(); } catch (IOException ignored) {}
            byte[] body = rctx.originalStream().toByteArray();
            var b = Response.builder().status(StatusCode.of(status)).body(Body.of(body));
            b.header("Content-Type", MediaTypes.format(mt));
            for (var e : outHeaders.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
                for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
            }
            return b.build();
        }
        // Strip Content-Type from headers map (re-added by writeEntity)
        java.util.Map<String, java.util.List<String>> extra = new java.util.LinkedHashMap<>();
        for (var e : headers.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            java.util.List<String> vs = new java.util.ArrayList<>();
            for (Object v : e.getValue()) vs.add(String.valueOf(v));
            extra.put(e.getKey(), vs);
        }
        return writeEntity(entity, rctx.getEntityType() == null ? entity.getClass() : rctx.getEntityType(),
                rctx.getEntityAnnotations(), mt, StatusCode.of(status), extra, route);
    }

    /** §9.2 : injecte les @Context fields d'un provider singleton avant
     *  l'appel à readFrom/writeTo en utilisant le match et la requête courants
     *  (capturés via ThreadLocal sur la requête en cours). */
    private void injectProviderContexts(Object provider, Request requestOpt) {
        // Skip les classes builtin internes (pas de @Context dedans, optimisation).
        Class<?> cls = provider.getClass();
        if (cls.getName().startsWith("io.vidocq.cassini.internal.MessageBodyRegistry$")) return;
        Request req = requestOpt != null ? requestOpt : CURRENT_REQUEST.get();
        MatchResult match = CURRENT_MATCH.get();
        if (req == null || match == null) return;
        try { FieldInjector.inject(provider, match, req); } catch (RuntimeException ignored) {}
    }

    private boolean hasRequestBody(Request request) {
        Body b = request.body();
        if (b == null) return false;
        long len = b.contentLength();
        if (len > 0) return true;
        // chunked encoding → contentLength may be -1 ; rely on presence of Content-Type
        return request.headers().contains("Content-Type") && len != 0;
    }

    private Object readEntity(Parameter p, MediaType ct, Request request, ResourceMethod route) throws IOException {
        Class<?> type = p.getType();
        Type genericType = p.getParameterizedType();
        Annotation[] anns = p.getAnnotations();
        MultivaluedMap<String, String> headers = MessageBodyRegistry.adaptHeaders(request.headers());

        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyReader reader = registry.findReader(type, genericType, anns, ct)
                .orElseThrow(() -> new WebApplicationException(
                        "No MessageBodyReader for " + type.getName() + " / " + MediaTypes.format(ct), 415));
        // §9.2 : @Context fields des providers user-level (singletons) sont
        // re-injectés à chaque appel pour exposer le contexte courant.
        injectProviderContexts(reader, request);
        // Si @FormParam a déjà consommé le body, replay depuis le cache.
        // Sinon, on bufferise pour que les FieldInjector @BeanParam ultérieurs
        // (sur la ressource elle-même) puissent re-lire le body côté @FormParam.
        byte[] cached = FieldInjector.BODY_CACHE.get();
        InputStream src;
        if (cached != null) {
            src = new java.io.ByteArrayInputStream(cached);
        } else {
            byte[] all = request.body() == null ? new byte[0] : request.body().asInputStream().readAllBytes();
            FieldInjector.BODY_CACHE.set(all);
            src = new java.io.ByteArrayInputStream(all);
        }
        var rInterceptors = route == null ? filters.readerInterceptors()
                : filters.readerInterceptorsFor(route.javaMethod(), route.beanClass());
        try (InputStream in = src) {
            if (rInterceptors.isEmpty()) {
                return reader.readFrom(type, genericType, anns, ct, headers, in);
            }
            return new CassiniReaderInterceptorContext(rInterceptors,
                    reader, registry, type, genericType, anns, ct, headers, in).proceed();
        }
    }

    private Response marshal(Object result, ResourceMethod route, MediaType chosen) throws IOException {
        if (result == null) {
            return Response.of(StatusCode.NO_CONTENT);
        }
        if (result instanceof jakarta.ws.rs.core.Response jr) {
            return fromJaxRs(jr, route, chosen);
        }
        return writeEntity(result, route.javaMethod().getGenericReturnType(),
                route.javaMethod().getAnnotations(), chosen, StatusCode.OK, Map.of(), route);
    }

    private Response writeEntity(Object entity, Type genericType, Annotation[] anns,
                                 MediaType chosen, StatusCode status,
                                 Map<String, List<String>> extraHeaders, ResourceMethod route) throws IOException {
        // §4.2.4 : GenericEntity décrit un type paramétré ; on déballe et
        // on utilise le type "raw"/"genericType" effectif pour le MBW.
        if (entity instanceof jakarta.ws.rs.core.GenericEntity<?> ge) {
            genericType = ge.getType();
            entity = ge.getEntity();
        }
        Class<?> type = entity.getClass();
        MediaType mtSelect = defaultFor(chosen, type);
        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyWriter writer = registry.findWriter(type, genericType, anns, mtSelect)
                .orElseThrow(() -> new WebApplicationException(
                        "No MessageBodyWriter for " + type.getName() + " / " + MediaTypes.format(mtSelect), 500));
        // §4.2.4 : si la méthode n'a pas spécifié de Content-Type, hériter du
        // @Produces du MBW sélectionné (premier media type concret déclaré).
        MediaType mt = mtSelect;
        if (mt.isWildcardType()) {
            jakarta.ws.rs.Produces wp = writer.getClass().getAnnotation(jakarta.ws.rs.Produces.class);
            if (wp != null && wp.value().length > 0) {
                MediaType inferred = MediaType.valueOf(wp.value()[0]);
                if (!inferred.isWildcardType()) mt = inferred;
            }
        }
        final MediaType finalMt = mt;
        injectProviderContexts(writer, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MultivaluedMap<String, Object> outHeaders = MessageBodyRegistry.outHeaders();
        // Peupler outHeaders avec les extraHeaders AVANT le chain interceptor
        // pour que WriterInterceptor.aroundWriteTo.getHeaders() voie ce que
        // la ResourceMethod/ResponseBuilder a produit. Les interceptors
        // peuvent encore muter ; on relit ensuite pour build.
        for (var e : extraHeaders.entrySet())
            for (String v : e.getValue()) outHeaders.add(e.getKey(), v);
        var wInterceptors = route == null ? filters.writerInterceptors()
                : filters.writerInterceptorsFor(route.javaMethod(), route.beanClass());
        if (wInterceptors.isEmpty()) {
            MessageBodyRegistry.writeTo(writer, entity, type, genericType, anns, finalMt, outHeaders, bos);
        } else {
            new CassiniWriterInterceptorContext(wInterceptors, writer, registry,
                    entity, type, genericType, anns, finalMt, outHeaders, bos).proceed();
        }

        var b = Response.builder().status(status).body(Body.of(bos.toByteArray()));
        b.header("Content-Type", MediaTypes.format(finalMt));
        for (var e : outHeaders.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
        }
        return b.build();
    }

    private Response fromJaxRs(jakarta.ws.rs.core.Response jr, ResourceMethod route,
                               MediaType fallback) throws IOException {
        StatusCode status = StatusCode.of(jr.getStatus());
        Object entity = jr.getEntity();
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : jr.getStringHeaders().entrySet()) {
            if (!"Content-Type".equalsIgnoreCase(e.getKey())) {
                headers.put(e.getKey(), e.getValue());
            }
        }
        MediaType chosen = jr.getMediaType() != null ? jr.getMediaType() : fallback;

        if (entity == null) {
            var b = Response.builder().status(status).body(Body.empty());
            for (var e : headers.entrySet()) for (String v : e.getValue()) b.header(e.getKey(), v);
            // Content-Type utilisateur : on le rémet explicitement (filtré plus haut).
            if (jr.getMediaType() != null) b.header("Content-Type", MediaTypes.format(jr.getMediaType()));
            return b.build();
        }
        // §7.2 / §4.2.4 : quand la méthode déclare retourner Response (wrapper),
        // le genericType passé au MBW / WriterInterceptorContext est celui de
        // l'entity réelle, pas Response.class.
        Type gt;
        if (route == null) {
            gt = entity.getClass();
        } else {
            Type ret = route.javaMethod().getGenericReturnType();
            gt = (ret == jakarta.ws.rs.core.Response.class) ? entity.getClass() : ret;
        }
        // §4.2.4 : si l'utilisateur a passé des annotations via
        // ResponseBuilder.entity(Object, Annotation[]), elles priment sur celles
        // de la méthode pour le MessageBodyWriter.isWriteable / writeTo.
        Annotation[] anns = null;
        if (jr instanceof io.vidocq.cassini.internal.runtime.CassiniResponse cr) {
            Annotation[] entAnns = cr.entityAnnotations();
            if (entAnns != null && entAnns.length > 0) anns = entAnns;
        }
        if (anns == null) {
            anns = route == null ? new Annotation[0] : route.javaMethod().getAnnotations();
        }
        return writeEntity(entity, gt, anns, chosen, status, headers, route);
    }

    /**
     * Si {@code chosen} est un wildcard (ex. {@literal *}{@literal /}{@literal *}
     * issu d'un Accept implicite) et que le type entity a un Content-Type
     * naturel, on le substitue. Sinon on respecte le négocié.
     */
    private static MediaType defaultFor(MediaType chosen, Class<?> entityType) {
        if (chosen == null) chosen = MediaType.WILDCARD_TYPE;
        if (!chosen.isWildcardType()) return chosen;
        if (CharSequence.class.isAssignableFrom(entityType)) return MediaType.TEXT_PLAIN_TYPE;
        if (byte[].class == entityType || InputStream.class.isAssignableFrom(entityType)
                || java.io.File.class.isAssignableFrom(entityType)) {
            return MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return chosen;
    }

    /** §3.7.2 : parmi les candidats (même path+verb), choisir celui dont
     *  @Consumes matche Content-Type ET @Produces matche Accept (spécificité
     *  maximale). Si aucun ne matche, retourne le premier (l'Invoker remontera
     *  415 ou 406 plus tard). */
    /** Retourne le qs le plus élevé parmi les @Produces de la route. */
    private static double sourceQuality(java.util.List<MediaType> produces) {
        double best = 0;
        for (MediaType p : produces) {
            String qs = p.getParameters().get("qs");
            double v = 1.0;
            if (qs != null) try { v = Double.parseDouble(qs); } catch (Exception ignored) {}
            if (v > best) best = v;
        }
        return best;
    }

    private MatchResult pickBestMatch(java.util.List<MatchResult> candidates, Request request) {
        if (candidates.size() == 1) return candidates.get(0);
        MediaType ct = MediaTypes.parse(request.headers().firstOrNull("Content-Type"));
        java.util.List<MediaType> accepts = MediaTypes.parseList(request.headers().firstOrNull("Accept"));
        MatchResult best = null;
        double bestScore = -1;
        for (MatchResult c : candidates) {
            var cons = MediaTypes.fromSet(c.method().consumes());
            if (hasRequestBody(request) && !cons.isEmpty() && !MediaTypes.consumesMatches(ct, cons)) continue;
            var prod = MediaTypes.fromSet(c.method().produces());
            // §3.7.2 : @Consumes spécificité domine @Produces (scalé ×10).
            // text/plain > text/* > */* > absence de @Consumes (si ct présent).
            double consScore = consumesSpecificity(ct, cons);
            double prodScore = 0;
            if (!prod.isEmpty()) {
                var pick = MediaTypes.pickProduced(accepts, prod);
                if (pick.isEmpty()) continue;
                // §3.7.2 : qs (source quality) prime sur la spécificité @Produces
                // (text/* qs=1.0 > text/xml qs=0.7) ; spec et acceptQ servent de
                // tiebreaker pour égalités qs.
                double spec = producesAnnotationSpecificity(accepts, prod);
                double qs = sourceQuality(prod);
                // q-value de l'Accept matché : textb;q=0.5 > texta;q=0.4.
                double acceptQ = bestAcceptQuality(accepts, prod);
                prodScore = qs * 10 + spec + acceptQ;
            }
            // §3.7.2 : spécificité du URI template domine d'abord (literalChars
            // desc, totalCaptures desc, defaultCaptures asc), puis @Consumes,
            // puis @Produces. Échelles : classPathLiterals (×1e8) > template
            // literalChars (×1e6) > totalCaptures (×1e3) > defaultCaptures inversé
            // (×1) > consumes (×10) > produces.
            int classLits = c.method().classPathLiterals();
            int tplLits = c.method().template().literalChars();
            int totalCaps = c.method().template().totalCaptures();
            int defaultCaps = c.method().template().defaultCaptures();
            double score = classLits * 1e8
                    + tplLits * 1e6
                    + totalCaps * 1e3
                    + (1000 - defaultCaps)
                    + consScore * 10 + prodScore;
            if (score > bestScore) { best = c; bestScore = score; }
        }
        return best != null ? best : candidates.get(0);
    }

    /** Spécificité du @Produces le mieux classé qui matche un Accept, calculée
     *  sur l'annotation (pas sur le type résolu après wildcard expansion). */
    private static double producesAnnotationSpecificity(List<MediaType> accepts, List<MediaType> produces) {
        double best = 0;
        for (MediaType a : accepts) {
            for (MediaType p : produces) {
                if (!MediaTypes.matches(a, p)) continue;
                double spec = (!p.isWildcardType() ? 2 : 0) + (!p.isWildcardSubtype() ? 1 : 0);
                if (spec > best) best = spec;
            }
        }
        return best;
    }

    /** q-value du meilleur Accept qui matche un @Produces. */
    private static double bestAcceptQuality(List<MediaType> accepts, List<MediaType> produces) {
        double best = 0;
        for (MediaType a : accepts) {
            for (MediaType p : produces) {
                if (!MediaTypes.matches(a, p)) continue;
                double q = MediaTypes.quality(a);
                if (q > best) best = q;
            }
        }
        return best;
    }

    /** Retourne la spécificité du @Consumes le plus précis qui matche ct. */
    private static double consumesSpecificity(MediaType ct, java.util.List<MediaType> consumes) {
        if (ct == null || consumes.isEmpty()) return 0;
        double best = 0;
        for (MediaType c : consumes) {
            if (!MediaTypes.consumesMatches(ct, java.util.List.of(c))) continue;
            double spec = (!c.isWildcardType() ? 2 : 0) + (!c.isWildcardSubtype() ? 1 : 0);
            if (spec > best) best = spec;
        }
        return best;
    }

    /** §3.7.2 / §4.4 : rend une réponse pour une exception hors-scope de la
     *  résolution (pas de MatchResult — typiquement 404/405 depuis le bridge).
     *  Consulte d'abord les ExceptionMapper applicatifs ; sinon, renvoie la
     *  Response portée par la WAE, ou un 500 par défaut. */
    public Response renderThrowable(Throwable t, Request request) throws IOException {
        ParamExtractor.setProviders(new io.vidocq.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        try {
            var mapped = exceptionMappers.map(t);
            if (mapped.isPresent()) {
                return fromJaxRs(mapped.get(), null, MediaType.WILDCARD_TYPE);
            }
            if (t instanceof WebApplicationException wae && wae.getResponse() != null) {
                return fromJaxRs(wae.getResponse(), null, MediaType.WILDCARD_TYPE);
            }
            String msg = t.getMessage() == null ? "" : t.getMessage();
            int status = t instanceof WebApplicationException wae2 && wae2.getResponse() != null
                    ? wae2.getResponse().getStatus() : 500;
            return Response.builder()
                    .status(StatusCode.of(status))
                    .header("Content-Type", "text/plain;charset=utf-8")
                    .body(Body.of(msg)).build();
        } finally {
            ParamExtractor.clearProviders();
        }
    }

    /** §4.4 : si un filtre / interceptor lève une exception, on la passe
     *  à l'ExceptionMapper s'il y en a un. Retourne {@code null} si aucun
     *  mapper n'est applicable — l'appelant décidera de la remonter. */
    private Response mapFilterThrowable(Throwable t, ResourceMethod route, MediaType chosen,
                                        CassiniRequestContext rctx) throws IOException {
        Throwable cause = t;
        if (t instanceof java.io.IOException && t.getCause() != null) cause = t.getCause();
        if (cause instanceof WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, rctx);
        }
        var mapped = exceptionMappers.map(cause);
        if (mapped.isPresent()) {
            MediaType mt = chosen == null ? MediaType.WILDCARD_TYPE : chosen;
            if (rctx != null && !filters.responseFilters().isEmpty())
                return runResponseFiltersAndWrite(rctx, mapped.get(), route, mt);
            return fromJaxRs(mapped.get(), route, mt);
        }
        return null;
    }

    private Response renderWebAppException(WebApplicationException wae, ResourceMethod route,
                                           MediaType chosen, CassiniRequestContext rctx) throws IOException {
        jakarta.ws.rs.core.Response r = wae.getResponse();
        // §4.3.1 : si la réponse embarquée a une entité, le mapper NE DOIT PAS être invoqué.
        if (r != null && r.hasEntity()) {
            if (rctx != null && !filters.responseFilters().isEmpty())
                return runResponseFiltersAndWrite(rctx, r, route, chosen);
            return fromJaxRs(r, route, chosen);
        }
        // §4.4 : sinon, tenter l'ExceptionMapper.
        var mapped = exceptionMappers.map(wae);
        if (mapped.isPresent()) {
            jakarta.ws.rs.core.Response mr = mapped.get();
            if (rctx != null && !filters.responseFilters().isEmpty())
                return runResponseFiltersAndWrite(rctx, mr, route, chosen);
            return fromJaxRs(mr, route, chosen);
        }
        if (r != null) {
            if (rctx != null && !filters.responseFilters().isEmpty())
                return runResponseFiltersAndWrite(rctx, r, route, chosen);
            return fromJaxRs(r, route, chosen);
        }
        String msg = wae.getMessage() == null ? "" : wae.getMessage();
        return Response.builder()
                .status(StatusCode.INTERNAL_SERVER_ERROR)
                .header("Content-Type", "text/plain;charset=utf-8")
                .body(Body.of(msg))
                .build();
    }
}
