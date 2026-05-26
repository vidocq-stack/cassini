package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;
import io.vidocq.cassini.internal.context.CassiniUriInfo;
import io.vidocq.cassini.internal.filter.CassiniReaderInterceptorContext;
import io.vidocq.cassini.internal.filter.CassiniRequestContext;
import io.vidocq.cassini.internal.filter.CassiniResponseContext;
import io.vidocq.cassini.internal.filter.CassiniWriterInterceptorContext;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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
     *  constructeur §3.1.1.
     *
     *  <p>TODO(M2h) : migrer vers un {@code RequestContext} portable virtual-thread-safe
     *  (ScopedValue ou contexte attaché au {@link CassiniHttpExchange}). Les
     *  ThreadLocal cassent en virtual-thread quand l'Invoker yield. */
    public static final ThreadLocal<MatchResult> CURRENT_MATCH = new ThreadLocal<>();
    public static final ThreadLocal<CassiniHttpExchange> CURRENT_REQUEST = new ThreadLocal<>();
    /** Clé d'attribut exchange pour la chaîne d'instances matchées (M2h). */
    public static final String ATTR_MATCHED_RESOURCES = "cassini.matched_resources";

    private final Function<Class<?>, Object> resolver;
    private final MessageBodyRegistry registry;
    private final ExceptionMapperRegistry exceptionMappers;
    private FilterRegistry filters = new FilterRegistry();
    /** Optionnel — permet de déproxifier un bean CDI (instance contextuelle réelle)
     *  avant l'injection {@code @Context}. {@code null} en mode {@code newInstance}. */
    private io.vidocq.cassini.spi.bean.BeanProvider beanProvider;

    public void setFilters(FilterRegistry f) { this.filters = f == null ? new FilterRegistry() : f; }
    public FilterRegistry filters() { return filters; }
    public void setBeanProvider(io.vidocq.cassini.spi.bean.BeanProvider bp) { this.beanProvider = bp; }

    /**
     * Cible d'injection {@code @Context} pour une instance résolue : l'instance contextuelle
     * réelle derrière un éventuel client proxy CDI (cf. {@code BeanProvider#contextualInstance}).
     * L'invocation de la méthode resource reste faite sur l'objet d'origine (le proxy), qui
     * délègue à cette même instance dans le scope actif — sans quoi les champs {@code @Context}
     * injectés par réflexion sur le proxy ne sont jamais vus par le corps de la méthode.
     */
    private Object injectionTarget(Class<?> beanClass, Object resolved) {
        return (beanProvider != null && resolved != null)
                ? beanProvider.contextualInstance(beanClass, resolved) : resolved;
    }

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

    public CassiniHttpResponse invoke(MatchResult match, CassiniHttpExchange request) throws Exception {
        return invoke(java.util.List.of(match), request);
    }

    /** Résout la meilleure route parmi les candidats en fonction des Accept/
     *  Content-Type de la requête (§3.7.2). Passe à invoke(MatchResult, Request)
     *  canonique avec le gagnant. */
    public CassiniHttpResponse invoke(java.util.List<MatchResult> candidates, CassiniHttpExchange request) throws Exception {
        if (candidates.isEmpty()) throw new IllegalArgumentException("no candidates");
        MatchResult match = pickBestMatch(candidates, request);
        ResourceMethod route = match.method();
        ParamExtractor.setProviders(new io.vidocq.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        ParamExtractor.setParamConverterProviders(filters.paramConverterProviders());
        try {
            try {
                java.net.URI u = request.requestUri();
                String scheme = u != null && u.getScheme() != null ? u.getScheme()
                        : (request.isSecure() ? "https" : "http");
                String authority = u != null && u.getAuthority() != null ? u.getAuthority() : null;
                if (authority == null) {
                    String host = request.firstHeader("Host");
                    if (host != null && !host.isEmpty()) authority = host;
                }
                if (authority == null) authority = "127.0.0.1";
                String ctx = request.contextPath();
                if (ctx == null) ctx = "";
                String basePath = ctx.isEmpty() ? "/" : ctx + "/";
                java.net.URI base = new java.net.URI(scheme + "://" + authority + basePath);
                io.vidocq.cassini.internal.runtime.CassiniResponseBuilder.setBaseUri(base);
            } catch (Exception ignored) {}
            // §3.4.1 dynamic dispatch : la route émise pour un sub-resource locator
            // retournant Object est résolue au runtime — on invoque la chaîne, on
            // scanne la classe effective de l'instance retournée, puis on délègue.
            if (route.dynamicLocator()) {
                return invokeDynamicLocator(match, request);
            }
            CassiniHttpResponse resp = invokeInternal(match, request, route);
            // §5.1 : si Request.selectVariant a été appelé pendant l'invocation,
            // ses dimensions de négociation sont stockées dans l'attribut exchange
            // PENDING_VARY → on les ajoute au header Vary de la réponse.
            return applyPendingVary(resp, request);
        } finally {
            ParamExtractor.clearProviders();
            ParamExtractor.clearParamConverterProviders();
            FieldInjector.clearFormCache();
            CURRENT_MATCH.remove();
            CURRENT_REQUEST.remove();
            io.vidocq.cassini.internal.runtime.CassiniResponseBuilder.clearBaseUri();
        }
    }

    /** §5.1 : injecte le header Vary collecté pendant Request.selectVariant. */
    @SuppressWarnings("unchecked")
    private static CassiniHttpResponse applyPendingVary(CassiniHttpResponse resp,
                                                        CassiniHttpExchange request) {
        var dims = (java.util.Set<String>) request.getAttribute(
                io.vidocq.cassini.internal.context.CassiniRequest.ATTR_PENDING_VARY);
        if (dims == null || dims.isEmpty()) return resp;
        // On reconstruit la Response avec le header Vary supplémentaire.
        var b = CassiniHttpResponse.builder().status(resp.status()).body(resp.body());
        boolean hasVary = false;
        for (var e : resp.headers().entrySet()) {
            for (String v : e.getValue()) b.header(e.getKey(), v);
            if ("Vary".equalsIgnoreCase(e.getKey())) hasVary = true;
        }
        if (!hasVary) {
            b.header("Vary", String.join(", ", dims));
        }
        return b.build();
    }

    /** §3.4.1 : exécute la chaîne d'un dynamic-locator (return Object), scanne
     *  la classe effective de l'instance retournée, et délègue le sub-routing
     *  à un mini-router éphémère. Si la sous-méthode est elle-même un dynamic
     *  locator (Object → Object → final), récursion. */
    private CassiniHttpResponse invokeDynamicLocator(MatchResult match, CassiniHttpExchange request) throws Exception {
        ResourceMethod route = match.method();
        // 1. Instancier root + invoquer la chaîne de locators
        Object root;
        try {
            root = resolver.apply(route.rootBeanClass());
        } catch (RuntimeException e) {
            return renderWebAppException(
                    new WebApplicationException("Cannot resolve root " + route.rootBeanClass().getName(), 500),
                    route, MediaType.WILDCARD_TYPE, null);
        }
        FieldInjector.inject(injectionTarget(route.rootBeanClass(), root), match, request);
        java.util.List<Object> matched = new java.util.ArrayList<>();
        matched.add(root);
        CURRENT_MATCH.set(match);
        CURRENT_REQUEST.set(request);
        request.setAttribute(ATTR_MATCHED_RESOURCES, matched);
        Object intermediate = root;
        for (java.lang.reflect.Method locStep : route.locatorChain()) {
            Parameter[] lps = locStep.getParameters();
            Object[] lArgs = lps.length == 0 ? new Object[0]
                    : ParamExtractor.resolveConstructorArgs(lps, match, request);
            locStep.setAccessible(true);
            try {
                intermediate = locStep.invoke(intermediate, lArgs);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return renderWebAppException(wae, route, MediaType.WILDCARD_TYPE, null);
                }
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }
            if (intermediate == null) {
                return renderWebAppException(
                        new WebApplicationException("Sub-resource locator returned null", 404),
                        route, MediaType.WILDCARD_TYPE, null);
            }
            if (intermediate instanceof Class<?> cls) {
                try { intermediate = cls.getDeclaredConstructor().newInstance(); }
                catch (ReflectiveOperationException e) {
                    return renderWebAppException(
                            new WebApplicationException(
                                    "Cannot instantiate sub-resource " + cls.getName() + ": " + e.getMessage(), 500),
                            route, MediaType.WILDCARD_TYPE, null);
                }
            }
            FieldInjector.inject(intermediate, match, request, false);
            matched.add(0, intermediate);
        }
        // 2. Calculer le remaining path à partir du capture {__rest:.*}
        String rest = "";
        if (match.pathParams().containsKey("__rest")) {
            var vs = match.pathParams().get("__rest");
            if (vs != null && !vs.isEmpty() && vs.get(0) != null) rest = vs.get(0);
        }
        String remaining = rest.isEmpty() ? "/" : "/" + rest;
        return dispatchOnInstance(intermediate, remaining, request, matched);
    }

    /** §3.4.1 : scanne dynamiquement {@code instance.getClass()} et résout la
     *  meilleure route pour {@code remaining}+méthode HTTP de la requête.
     *  Réutilise {@link #invokeInternal} en passant l'instance déjà créée
     *  via un resolver ad-hoc pour éviter une re-instanciation. */
    private CassiniHttpResponse dispatchOnInstance(Object instance, String remaining, CassiniHttpExchange request,
                                        java.util.List<Object> matchedSoFar) throws Exception {
        Class<?> cls = instance.getClass();
        // §3.6 : si la classe runtime n'a pas @Path à la racine, on simule en
        // ajoutant @Path("") via un wrapper de scan. ResourceScanner.discover
        // exige @Path sur la classe — on contourne en scannant les locators à
        // partir d'un faux locator-chain.
        java.util.List<ResourceMethod> subRoutes = scanInstanceClass(cls);
        if (subRoutes.isEmpty()) {
            return renderWebAppException(new jakarta.ws.rs.NotFoundException(),
                    null, MediaType.WILDCARD_TYPE, null);
        }
        UriRouter subRouter = new UriRouter(subRoutes);
        String httpMethod = request.method() == null ? "GET" : request.method();
        java.util.List<MatchResult> subCandidates = subRouter.matchAll(httpMethod, remaining);
        if (subCandidates.isEmpty()) {
            // 405 si une autre méthode HTTP matche le path
            var allowed = subRouter.methodsAllowedFor(remaining);
            if (!allowed.isEmpty()) {
                return renderWebAppException(
                        new jakarta.ws.rs.NotAllowedException(allowed.get(0),
                                allowed.subList(1, allowed.size()).toArray(String[]::new)),
                        null, MediaType.WILDCARD_TYPE, null);
            }
            return renderWebAppException(new jakarta.ws.rs.NotFoundException(),
                    null, MediaType.WILDCARD_TYPE, null);
        }
        MatchResult subMatch = pickBestMatch(subCandidates, request);
        ResourceMethod subRoute = subMatch.method();
        // Récursion si la sous-route est elle-même un dynamic-locator
        if (subRoute.dynamicLocator()) {
            // Remonter d'un cran : invoquer la sous-chaîne sur l'instance courante
            return invokeDynamicLocatorWithInstance(subMatch, request, instance, matchedSoFar);
        }
        // Invoquer la méthode finale sur l'instance courante via un resolver ad-hoc
        return invokeFinalOnInstance(subMatch, request, instance, matchedSoFar);
    }

    /** Variante {@link #invokeDynamicLocator} qui démarre depuis une instance
     *  déjà résolue (au lieu de la classe racine). */
    private CassiniHttpResponse invokeDynamicLocatorWithInstance(MatchResult match, CassiniHttpExchange request,
                                                      Object startInstance,
                                                      java.util.List<Object> matchedSoFar) throws Exception {
        ResourceMethod route = match.method();
        Object intermediate = startInstance;
        CURRENT_MATCH.set(match);
        CURRENT_REQUEST.set(request);
        request.setAttribute(ATTR_MATCHED_RESOURCES, matchedSoFar);
        for (java.lang.reflect.Method locStep : route.locatorChain()) {
            // Sauter les locators déjà exécutés (présents en haut de la chaîne
            // de l'instance courante). On reconnaît un locator déjà fait par
            // sa déclaration sur une classe "ancêtre" ; ici, on n'en a aucun
            // car le scan a redémarré sur intermediate.getClass(), donc on
            // exécute toute la sous-chaîne.
            Parameter[] lps = locStep.getParameters();
            Object[] lArgs = lps.length == 0 ? new Object[0]
                    : ParamExtractor.resolveConstructorArgs(lps, match, request);
            locStep.setAccessible(true);
            try {
                intermediate = locStep.invoke(intermediate, lArgs);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return renderWebAppException(wae, route, MediaType.WILDCARD_TYPE, null);
                }
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }
            if (intermediate == null) {
                return renderWebAppException(
                        new WebApplicationException("Sub-resource locator returned null", 404),
                        route, MediaType.WILDCARD_TYPE, null);
            }
            if (intermediate instanceof Class<?> cls) {
                try { intermediate = cls.getDeclaredConstructor().newInstance(); }
                catch (ReflectiveOperationException e) {
                    return renderWebAppException(
                            new WebApplicationException(
                                    "Cannot instantiate sub-resource " + cls.getName() + ": " + e.getMessage(), 500),
                            route, MediaType.WILDCARD_TYPE, null);
                }
            }
            FieldInjector.inject(intermediate, match, request, false);
            matchedSoFar.add(0, intermediate);
        }
        String rest = "";
        if (match.pathParams().containsKey("__rest")) {
            var vs = match.pathParams().get("__rest");
            if (vs != null && !vs.isEmpty() && vs.get(0) != null) rest = vs.get(0);
        }
        String remaining = rest.isEmpty() ? "/" : "/" + rest;
        return dispatchOnInstance(intermediate, remaining, request, matchedSoFar);
    }

    /** Invoque la méthode finale d'une sub-route en passant {@code instance}
     *  comme target (au lieu de re-instancier via {@code resolver}). Utilise
     *  un resolver ad-hoc qui retourne l'instance pour la classe attendue. */
    private CassiniHttpResponse invokeFinalOnInstance(MatchResult match, CassiniHttpExchange request,
                                           Object instance,
                                           java.util.List<Object> matchedSoFar) throws Exception {
        ResourceMethod route = match.method();
        // On reproduit ici un sous-ensemble du flux (pas de filters, pas de
        // pre/post-matching pour cette route synthétique de dynamic dispatch).
        // §3.7.2 négociation Accept/Content-Type appliquée.
        String ctHeader = request.firstHeader("Content-Type");
        MediaType contentType = MediaTypes.parse(ctHeader);
        java.util.List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        boolean checkConsumes = hasRequestBody(request) || ctHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(contentType, consumes)) {
            return renderWebAppException(new jakarta.ws.rs.NotSupportedException(), route, null, null);
        }
        java.util.List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
        java.util.List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(accepts, produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return renderWebAppException(new jakarta.ws.rs.NotAcceptableException(), route, null, null);
        }
        MediaType chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);

        // Resolve args
        ParamExtractor.ResolvedArgs resolved;
        Object[] args;
        try {
            resolved = ParamExtractor.resolve(route, match, request);
            args = resolved.args();
            if (resolved.bodyIndex() >= 0) {
                Parameter p = route.javaMethod().getParameters()[resolved.bodyIndex()];
                MediaType readMt = (ctHeader == null) ? MediaType.APPLICATION_OCTET_STREAM_TYPE : contentType;
                args[resolved.bodyIndex()] = readEntity(p, readMt, request, route);
            }
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, null);
        }

        // §3.4.1 : sub-resource via dynamic locator → pas d'injection @*Param
        FieldInjector.inject(instance, match, request, false);
        Object result;
        try {
            route.javaMethod().setAccessible(true);
            result = route.javaMethod().invoke(instance, args);
        } catch (InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof WebApplicationException wae) {
                return renderWebAppException(wae, route, chosen, null);
            }
            var mapped = exceptionMappers.map(cause);
            if (mapped.isPresent()) return fromJaxRs(mapped.get(), route, chosen);
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        }
        if (result instanceof java.util.concurrent.CompletionStage<?> cs) {
            // TODO(M2h) : propager le stage non-bloquant jusqu'au transport
            // (cf. CassiniHttpAdapter.dispatch retourne CompletionStage<Void>).
            // Ce bloc try/get est isolé via Async.awaitBlocking dans toutes les
            // autres occurrences ; on le garde inline ici car la branche WAE
            // doit être traitée localement (renderWebAppException).
            try { result = cs.toCompletableFuture().get(); }
            catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return renderWebAppException(wae, route, chosen, null);
                }
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted", ie);
            }
        }
        return marshal(result, route, chosen);
    }

    /** Scanne {@code cls} comme classe ressource (ajout @Path("") implicite si
     *  manquant) pour produire des routes locales. Utilisé en dynamic dispatch
     *  où la classe vient d'un sub-resource locator runtime, sans @Path racine. */
    private static java.util.List<ResourceMethod> scanInstanceClass(Class<?> cls) {
        // ResourceScanner.discover exige @Path sur la classe ; pour les classes
        // sub-resource sans @Path, on simule via scanLocatorType depuis basePath="/".
        // Mais scanLocatorType est privé — on utilise le contournement standard :
        // discover() fonctionne si la classe a un @Path. Si elle n'en a pas, on
        // utilise une réflexion light pour construire les routes.
        Path p = cls.getAnnotation(Path.class);
        if (p != null) {
            // La classe est elle-même @Path → on scanne normalement et on
            // dépouille le préfixe correspondant à la classe (le routing se fait
            // sur remaining qui n'a pas le @Path racine).
            return ResourceScanner.discover(cls);
        }
        return scanSubResourceClass(cls);
    }

    /** Scanne une classe sub-resource (sans @Path racine) en produisant des
     *  ResourceMethod à template = @Path(method) seul (resource methods + sub
     *  locators). Pas de récursion sur les locators avec retour Object —
     *  ils émettent à leur tour des routes dynamiques. */
    private static java.util.List<ResourceMethod> scanSubResourceClass(Class<?> cls) {
        java.util.List<ResourceMethod> out = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String verb = resolveHttpMethodOf(m);
            Path subPath = m.getAnnotation(Path.class);
            String path = subPath == null ? "/" : normalizeFwd(subPath.value());
            java.util.Set<String> mp = setOf(m.getAnnotation(Produces.class));
            java.util.Set<String> mc = setOf(m.getAnnotation(Consumes.class));
            if (verb != null) {
                m.setAccessible(true);
                out.add(new ResourceMethod(cls, m, verb,
                        UriTemplate.compile(path), mp, mc));
                continue;
            }
            if (subPath == null) continue;
            // Sub-resource locator au sein d'une classe sub-resource :
            // émet une route dynamique qui réinjecte du dispatch runtime.
            Class<?> ret = m.getReturnType();
            if (ret == void.class || ret == null) continue;
            m.setAccessible(true);
            // chaîne contient juste cette méthode ; le dispatcher courant
            // l'invoquera sur l'instance déjà résolue (pas via root).
            java.util.List<java.lang.reflect.Method> chain = java.util.List.of(m);
            out.add(new ResourceMethod(Object.class, m, "*",
                    UriTemplate.compile(path),
                    mp, mc, cls, chain, 0, true));
            String wildcardPath = path.equals("/") ? "/{__rest:.*}" : path + "/{__rest:.*}";
            out.add(new ResourceMethod(Object.class, m, "*",
                    UriTemplate.compile(wildcardPath),
                    mp, mc, cls, chain, 0, true));
        }
        return out;
    }

    private static String resolveHttpMethodOf(java.lang.reflect.Method m) {
        for (java.lang.annotation.Annotation a : m.getAnnotations()) {
            jakarta.ws.rs.HttpMethod meta = a.annotationType().getAnnotation(jakarta.ws.rs.HttpMethod.class);
            if (meta != null) return meta.value();
        }
        return null;
    }

    private static java.util.Set<String> setOf(java.lang.annotation.Annotation ann) {
        if (ann instanceof Produces p) return new java.util.LinkedHashSet<>(java.util.List.of(p.value()));
        if (ann instanceof Consumes c) return new java.util.LinkedHashSet<>(java.util.List.of(c.value()));
        return java.util.Set.of();
    }

    private static String normalizeFwd(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * §6.6.1 : résultat de l'exécution des pre-matching filters.
     * Contient soit une {@code response} d'arrêt (abortWith ou exception
     * mappée), soit (si {@code response == null}) le contexte mutable utilisé
     * pour relancer le routing avec method/URI éventuellement modifiés.
     */
    public record PreMatchResult(CassiniHttpResponse response, CassiniRequestContext ctx) {}

    /** §6.6.1 : exécute les pre-matching filters avant le routing. */
    public PreMatchResult runPreMatching(CassiniHttpExchange request) throws Exception {
        if (filters.preMatching().isEmpty()) return new PreMatchResult(null, null);
        ParamExtractor.setProviders(new io.vidocq.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        try {
            CassiniRequestContext preCtx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), java.util.Map.of()));
            for (var fe : filters.preMatching()) {
                try { fe.instance().filter(preCtx); }
                catch (java.io.IOException | RuntimeException e) {
                    CassiniHttpResponse mapped = mapFilterThrowable(e, null, null, preCtx);
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

    private CassiniHttpResponse invokeInternal(MatchResult match, CassiniHttpExchange request, ResourceMethod route) throws Exception {

        // 0. Pre-matching request filters §6.6 — exécutés avant toute
        //    négociation. S'ils throw, on passe par ExceptionMapper.
        CassiniRequestContext preCtx = null;
        if (!filters.preMatching().isEmpty()) {
            preCtx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), match.pathParams()));
            for (var fe : filters.preMatching()) {
                try { fe.instance().filter(preCtx); }
                catch (java.io.IOException e) {
                    CassiniHttpResponse mapped = mapFilterThrowable(e, route, null, preCtx);
                    if (mapped != null) return mapped;
                    throw e;
                } catch (RuntimeException e) {
                    CassiniHttpResponse mapped = mapFilterThrowable(e, route, null, preCtx);
                    if (mapped != null) return mapped;
                    throw e;
                }
                if (preCtx.isAborted()) {
                    return runResponseFiltersAndWrite(preCtx, preCtx.abortedResponse(), route, null);
                }
            }
        }

        // 1. Negotiation
        String ctHeader = request.firstHeader("Content-Type");
        MediaType contentType = MediaTypes.parse(ctHeader);
        List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        // §3.7.2 : si la requête a un Content-Type ou un body, filtre sur @Consumes.
        boolean checkConsumes = hasRequestBody(request) || ctHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(contentType, consumes)) {
            // §3.7.2 : 415 via WAE pour laisser l'ExceptionMapper intercepter.
            return renderWebAppException(new jakarta.ws.rs.NotSupportedException(), route, null, null);
        }

        List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
        List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(accepts, produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return renderWebAppException(new jakarta.ws.rs.NotAcceptableException(), route, null, null);
        }
        MediaType chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);

        // §8.2 : @Suspended AsyncResponse — créer l'impl AVANT l'extraction des params
        // pour que ParamExtractor puisse l'injecter via l'attribut exchange.
        CassiniAsyncResponseImpl asyncResponse = null;
        for (java.lang.reflect.Parameter p : route.javaMethod().getParameters()) {
            if (p.getAnnotation(jakarta.ws.rs.container.Suspended.class) != null
                    && jakarta.ws.rs.container.AsyncResponse.class.isAssignableFrom(p.getType())) {
                asyncResponse = new CassiniAsyncResponseImpl();
                request.setAttribute(CassiniAsyncResponseImpl.ATTR_KEY, asyncResponse);
                break;
            }
        }

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
            CassiniHttpResponse mapped = mapFilterThrowable(re, route, chosen, preCtx);
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
            // §9.2 : set CURRENT_MATCH/REQUEST pour que injectProviderContexts puisse
            // alimenter les @Context fields (ResourceInfo, UriInfo, etc.) des providers.
            // Note : pour filters singletons, l'écriture in-place dans des fields partagés
            // est non thread-safe — acceptable tant qu'on n'a pas de proxy par-thread.
            CURRENT_MATCH.set(match);
            CURRENT_REQUEST.set(request);
            for (var fe : filters.postMatching()) {
                if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
                // §9.2 : injection @Context dans le provider (filter) singleton.
                injectProviderContexts(fe.instance(), request);
                try { fe.instance().filter(rctx); }
                catch (java.io.IOException e) {
                    CassiniHttpResponse mapped = mapFilterThrowable(e, route, chosen, rctx);
                    if (mapped != null) return mapped;
                    throw new RuntimeException(e);
                } catch (RuntimeException e) {
                    CassiniHttpResponse mapped = mapFilterThrowable(e, route, chosen, rctx);
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
        request.setAttribute(ATTR_MATCHED_RESOURCES, matched);
        try {
            if (route.isLocated()) {
                // Sub-resource locator §3.4.1 : instantier la ressource racine,
                // parcourir la chaîne de locators, injecter fields à chaque étape.
                Object root = resolver.apply(route.rootBeanClass());
                FieldInjector.inject(injectionTarget(route.rootBeanClass(), root), match, request);
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
                    FieldInjector.inject(intermediate, match, request, false);
                    matched.add(0, intermediate);
                }
                target = intermediate;
            } else {
                target = resolver.apply(route.beanClass());
                // §9 : injecter les @Context dans l'instance contextuelle réelle (déproxifiée),
                // mais invoquer la méthode sur `target` (le proxy CDI délègue à cette instance).
                FieldInjector.inject(injectionTarget(route.beanClass(), target), match, request);
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
        // l'arg par notre instance (CassiniSseEventSink). En mode streaming
        // (JDK transport), les events sont écrits directement sur le wire.
        io.vidocq.cassini.internal.sse.CassiniSseEventSink sseSink = null;
        Parameter[] params = route.javaMethod().getParameters();
        for (int pi = 0; pi < params.length; pi++) {
            if (params[pi].getType() == jakarta.ws.rs.sse.SseEventSink.class) {
                // Tenter d'ouvrir le streaming chunked (JDK transport).
                var streamingHeaders = new java.util.LinkedHashMap<String, java.util.List<String>>();
                streamingHeaders.put("Content-Type",
                        java.util.List.of("text/event-stream;charset=utf-8"));
                streamingHeaders.put("Cache-Control", java.util.List.of("no-cache"));
                var streamingSink = request.openForStreaming(200, streamingHeaders);
                sseSink = new io.vidocq.cassini.internal.sse.CassiniSseEventSink(registry, streamingSink);
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

        // §8.2 : @Suspended AsyncResponse — bloquer le virtual thread jusqu'à resume().
        // Le virtual thread yield son carrier sans starvation (M2h).
        if (asyncResponse != null) {
            try {
                result = asyncResponse.completionFuture().get();
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return renderWebAppException(wae, route, chosen, rctx);
                }
                var mapped = exceptionMappers.map(cause);
                if (mapped.isPresent()) return runResponseFiltersAndWrite(rctx, mapped.get(), route, chosen);
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for AsyncResponse.resume()", ie);
            }
        }

        // §9.2 : CompletionStage<T> retourné par une méthode resource.
        // On bloque le virtual thread (M2h) — libère le carrier sans starvation.
        if (result instanceof java.util.concurrent.CompletionStage<?> cs) {
            try {
                result = cs.toCompletableFuture().get();
            } catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return renderWebAppException(wae, route, chosen, rctx);
                }
                var mapped = exceptionMappers.map(cause);
                if (mapped.isPresent()) return runResponseFiltersAndWrite(rctx, mapped.get(), route, chosen);
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while awaiting CompletionStage", ie);
            }
        }

        // §11.1 : méthode SSE.
        if (sseSink != null) {
            ParamExtractor.clearCurrentSink();
            if (sseSink.isStreaming()) {
                // Mode streaming (JDK) : la réponse a déjà été envoyée sur le wire.
                return CassiniHttpResponse.builder().status(200).body(new byte[0]).build();
            }
            // Mode bufferisé : attendre sink.close() puis envoyer le buffer en bloc.
            sseSink.awaitClose();
            byte[] body = sseSink.toByteArray();
            return CassiniHttpResponse.builder()
                    .status(200)
                    .header("Content-Type", "text/event-stream")
                    .body(body)
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
            CassiniHttpResponse mapped = mapFilterThrowable(re, route, chosen, rctx);
            if (mapped != null) return mapped;
            throw re;
        }
    }

    private CassiniHttpResponse runResponseFiltersAndWrite(CassiniRequestContext rctx,
                                                jakarta.ws.rs.core.Response userResp,
                                                ResourceMethod route, MediaType chosen) throws IOException {
        int status = userResp.getStatus();
        Object entity = userResp.getEntity();
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        for (var e : userResp.getStringHeaders().entrySet()) for (String v : e.getValue()) headers.add(e.getKey(), v);

        CassiniResponseContext rctx2 = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), headers);
        for (var fe : filters.responseFilters()) {
            // route null = pré-matching abort/exception §6.5.2 : seuls les
            // filtres globalement liés (sans @NameBinding) s'appliquent.
            // appliesTo(null,null) retourne true pour les globaux et false
            // pour les NameBound — on s'appuie dessus pour filtrer.
            if (route == null) {
                if (!fe.appliesTo(null, null)) continue;
            } else {
                if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
            }
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

    private CassiniHttpResponse runResponseFiltersForResult(CassiniRequestContext rctx, Object result,
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

    private CassiniHttpResponse writeFromContext(CassiniResponseContext rctx, ResourceMethod route, MediaType chosen) throws IOException {
        Object entity = rctx.getEntity();
        int status = rctx.getStatus();
        MultivaluedMap<String, Object> headers = rctx.getHeaders();
        if (entity == null) {
            var b = CassiniHttpResponse.builder().status(status).body(new byte[0]);
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
            var b = CassiniHttpResponse.builder().status(status).body(body);
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
                rctx.getEntityAnnotations(), mt, status, extra, route);
    }

    /** §9.2 : injecte les @Context fields d'un provider singleton avant
     *  l'appel à readFrom/writeTo en utilisant le match et la requête courants
     *  (capturés via ThreadLocal sur la requête en cours). */
    private void injectProviderContexts(Object provider, CassiniHttpExchange requestOpt) {
        // Skip les classes builtin internes (pas de @Context dedans, optimisation).
        Class<?> cls = provider.getClass();
        if (cls.getName().startsWith("io.vidocq.cassini.internal.MessageBodyRegistry$")) return;
        CassiniHttpExchange req = requestOpt != null ? requestOpt : CURRENT_REQUEST.get();
        MatchResult match = CURRENT_MATCH.get();
        if (req == null || match == null) return;
        try { FieldInjector.inject(provider, match, req); } catch (RuntimeException ignored) {}
    }

    private boolean hasRequestBody(CassiniHttpExchange request) {
        long len = request.contentLength();
        if (len > 0) return true;
        // chunked encoding → contentLength may be -1 ; rely on presence of Content-Type
        return request.firstHeader("Content-Type") != null && len != 0;
    }

    private Object readEntity(Parameter p, MediaType ct, CassiniHttpExchange request, ResourceMethod route) throws IOException {
        Class<?> type = p.getType();
        Type genericType = p.getParameterizedType();
        Annotation[] anns = p.getAnnotations();
        MultivaluedMap<String, String> headers = MessageBodyRegistry.adaptExchangeHeaders(request.requestHeaders());

        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyReader reader = registry.findReader(type, genericType, anns, ct).orElse(null);
        // §7.2 : si aucun MBR ne matche initialement mais des ReaderInterceptors
        // sont enregistrés, on diffère la sélection — un interceptor peut ré-écrire
        // type/mediaType (setType, setMediaType) pour matcher un MBR différent.
        var rInterceptorsForChoice = route == null ? filters.readerInterceptorsFor(null, null)
                : filters.readerInterceptorsFor(route.javaMethod(), route.beanClass());
        if (reader == null && rInterceptorsForChoice.isEmpty()) {
            throw new WebApplicationException(
                    "No MessageBodyReader for " + type.getName() + " / " + MediaTypes.format(ct), 415);
        }
        // §9.2 : @Context fields des providers user-level (singletons) sont
        // re-injectés à chaque appel pour exposer le contexte courant.
        if (reader != null) injectProviderContexts(reader, request);
        // Si @FormParam a déjà consommé le body, replay depuis le cache (attribut exchange, M2h).
        byte[] cached = (byte[]) request.getAttribute(FieldInjector.ATTR_BODY_CACHE);
        InputStream src;
        if (cached != null) {
            src = new java.io.ByteArrayInputStream(cached);
        } else {
            byte[] all = request.requestBody() == null ? new byte[0] : request.requestBody().readAllBytes();
            request.setAttribute(FieldInjector.ATTR_BODY_CACHE, all);
            src = new java.io.ByteArrayInputStream(all);
        }
        var rInterceptors = rInterceptorsForChoice;
        try (InputStream in = src) {
            if (rInterceptors.isEmpty()) {
                return reader.readFrom(type, genericType, anns, ct, headers, in);
            }
            return new CassiniReaderInterceptorContext(rInterceptors,
                    reader, registry, type, genericType, anns, ct, headers, in).proceed();
        }
    }

    private CassiniHttpResponse marshal(Object result, ResourceMethod route, MediaType chosen) throws IOException {
        if (result == null) {
            return CassiniHttpResponse.status(204);
        }
        if (result instanceof jakarta.ws.rs.core.Response jr) {
            return fromJaxRs(jr, route, chosen);
        }
        return writeEntity(result, route.javaMethod().getGenericReturnType(),
                route.javaMethod().getAnnotations(), chosen, 200, Map.of(), route);
    }

    private CassiniHttpResponse writeEntity(Object entity, Type genericType, Annotation[] anns,
                                 MediaType chosen, int status,
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
        // §6.5.2 : si route==null (pré-matching abort/exception), seuls les
        // writer interceptors globalement liés s'appliquent.
        var wInterceptors = route == null ? filters.writerInterceptorsFor(null, null)
                : filters.writerInterceptorsFor(route.javaMethod(), route.beanClass());
        if (wInterceptors.isEmpty()) {
            MessageBodyRegistry.writeTo(writer, entity, type, genericType, anns, finalMt, outHeaders, bos);
        } else {
            new CassiniWriterInterceptorContext(wInterceptors, writer, registry,
                    entity, type, genericType, anns, finalMt, outHeaders, bos).proceed();
        }

        var b = CassiniHttpResponse.builder().status(status).body(bos.toByteArray());
        b.header("Content-Type", MediaTypes.format(finalMt));
        for (var e : outHeaders.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
        }
        return b.build();
    }

    private CassiniHttpResponse fromJaxRs(jakarta.ws.rs.core.Response jr, ResourceMethod route,
                               MediaType fallback) throws IOException {
        int status =jr.getStatus();
        Object entity = jr.getEntity();
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : jr.getStringHeaders().entrySet()) {
            if (!"Content-Type".equalsIgnoreCase(e.getKey())) {
                headers.put(e.getKey(), e.getValue());
            }
        }
        MediaType chosen = jr.getMediaType() != null ? jr.getMediaType() : fallback;

        if (entity == null) {
            var b = CassiniHttpResponse.builder().status(status).body(new byte[0]);
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

    private MatchResult pickBestMatch(java.util.List<MatchResult> candidates, CassiniHttpExchange request) {
        if (candidates.size() == 1) return candidates.get(0);
        MediaType ct = MediaTypes.parse(request.firstHeader("Content-Type"));
        java.util.List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
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
                // §3.7.2 / JAXRS:SPEC:25.11 + 26.8 : ordre
                //   primary   = q-value de l'Accept LE PLUS SPÉCIFIQUE qui matche
                //               (cf. bestAcceptQuality)
                //   secondary = qs-value (source quality, server-side)
                //   tertiary  = @Produces specificity (tie-break)
                double acceptQ = bestAcceptQuality(accepts, prod);
                double qs = sourceQuality(prod);
                double spec = producesAnnotationSpecificity(accepts, prod);
                prodScore = acceptQ * 1_000_000 + qs * 1_000 + spec;
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

    /** q-value de l'Accept LE PLUS SPÉCIFIQUE qui matche un @Produces.
     *  §3.7.2 / §3.8 : pour résoudre {@code clientImagePreferenceTest}
     *  (Accept "image/something;q=0.1, image/*;q=0.9" + @Produces "image/*"),
     *  on doit retenir l'Accept le plus précis qui matche : pour @Produces
     *  image/*, c'est image/something (concret > wildcard) → q=0.1, et non
     *  q=0.9 du wildcard. Cela permet à @Produces image/png (qui ne matche
     *  que image/* avec q=0.9) de gagner. */
    private static double bestAcceptQuality(List<MediaType> accepts, List<MediaType> produces) {
        double bestQ = 0;
        int bestSpec = -1;
        for (MediaType a : accepts) {
            for (MediaType p : produces) {
                if (!MediaTypes.matches(a, p)) continue;
                int aSpec = (!a.isWildcardType() ? 2 : 0) + (!a.isWildcardSubtype() ? 1 : 0);
                double q = MediaTypes.quality(a);
                if (aSpec > bestSpec || (aSpec == bestSpec && q > bestQ)) {
                    bestSpec = aSpec;
                    bestQ = q;
                }
            }
        }
        return bestQ;
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
    public CassiniHttpResponse renderThrowable(Throwable t, CassiniHttpExchange request) throws IOException {
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
            return CassiniHttpResponse.builder()
                    .status(status)
                    .header("Content-Type", "text/plain;charset=utf-8")
                    .body(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8)).build();
        } finally {
            ParamExtractor.clearProviders();
        }
    }

    /** §4.4 : si un filtre / interceptor lève une exception, on la passe
     *  à l'ExceptionMapper s'il y en a un. Retourne {@code null} si aucun
     *  mapper n'est applicable — l'appelant décidera de la remonter. */
    private CassiniHttpResponse mapFilterThrowable(Throwable t, ResourceMethod route, MediaType chosen,
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

    private CassiniHttpResponse renderWebAppException(WebApplicationException wae, ResourceMethod route,
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
        return CassiniHttpResponse.builder()
                .status(500)
                .header("Content-Type", "text/plain;charset=utf-8")
                .body(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }
}
