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
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;
import io.vidocq.cassini.internal.context.CassiniUriInfo;
import io.vidocq.cassini.internal.filter.CassiniRequestContext;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import io.vidocq.cassini.internal.runtime.CassiniResponseBuilder;
import io.vidocq.cassini.internal.sse.CassiniSseEventSink;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import io.vidocq.cassini.internal.gen.AdapterRegistry;
import io.vidocq.cassini.internal.gen.InjectionSupportImpl;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.function.Function;

/**
 * Executes a {@link ResourceMethod}: content negotiation, body reading via
 * {@link MessageBodyReader}, invocation, and serialization via
 * {@link MessageBodyWriter}.
 */
public final class Invoker {

    /** Exchange attribute key for the matched-instance chain (M2h). */
    public static final String ATTR_MATCHED_RESOURCES = "cassini.matched_resources";

    private static final String CONTENT_TYPE = "Content-Type";

    /** Context exposed during the {@link #resolver} call to let resolvers
     *  (e.g. TCK harnesses) create instances with constructor injection
     *  §3.1.1. M2h: carried by the per-request {@link RequestScope} binding
     *  (virtual-thread-safe, bounded lifetime) — no ThreadLocal.
     *
     *  @return the routing result of the request being processed, or
     *          {@code null} outside a dispatch. */
    public static MatchResult currentMatch() {
        var scope = RequestScope.current();
        return scope == null ? null : scope.match;
    }

    /** @return the exchange of the request being processed, or {@code null}
     *          outside a dispatch. See {@link #currentMatch()}. */
    public static CassiniHttpExchange currentRequest() {
        var scope = RequestScope.current();
        return scope == null ? null : scope.request;
    }

    /** Sets the per-request match/request slots in the active scope. */
    static void publishCurrent(MatchResult match, CassiniHttpExchange request) {
        var scope = RequestScope.current();
        scope.match = match;
        scope.request = request;
    }

    final Function<Class<?>, Object> resolver;
    private final MessageBodyRegistry registry;
    private final ExceptionMapperRegistry exceptionMappers;
    private FilterRegistry filters = new FilterRegistry();
    /** Deployment-level JAX-RS Application exposed via {@code @Context Application}
     *  (M2h: instance field seeded into the request scope — replaces the
     *  InheritableThreadLocal that crossed the per-request VT spawn). */
    private jakarta.ws.rs.core.Application application;
    /** Optional — allows de-proxying a CDI bean (real contextual instance)
     *  before {@code @Context} injection. {@code null} in {@code newInstance} mode. */
    private io.vidocq.cassini.spi.bean.BeanProvider beanProvider;
    // §3.4.1 dynamic sub-resource dispatch (extracted - this class stays the facade)
    private final DynamicLocatorDispatch dynamicDispatch = new DynamicLocatorDispatch(this);
    // Entity I/O + response-writing pipeline (extracted - this class stays the facade)
    private final ResponsePipeline pipeline = new ResponsePipeline(this);

    public void setFilters(FilterRegistry f) { this.filters = f == null ? new FilterRegistry() : f; }
    public FilterRegistry filters() { return filters; }
    public void setBeanProvider(io.vidocq.cassini.spi.bean.BeanProvider bp) { this.beanProvider = bp; }
    public void setApplication(jakarta.ws.rs.core.Application app) { this.application = app; }

    /**
     * {@code @Context} injection target for a resolved instance: the real
     * contextual instance behind a possible CDI client proxy (cf. {@code BeanProvider#contextualInstance}).
     * The resource method invocation still runs on the original object (the proxy), which
     * delegates to that same instance in the active scope — otherwise {@code @Context}
     * fields injected reflectively on the proxy are never seen by the method body.
     */
    Object injectionTarget(Class<?> beanClass, Object resolved) {
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

    /** Resolves the best route among candidates according to the request
     *  Accept/Content-Type (§3.7.2). Then calls the canonical
     *  invoke(MatchResult, Request) with the winner. */
    public CassiniHttpResponse invoke(java.util.List<MatchResult> candidates, CassiniHttpExchange request) throws Exception {
        return RequestScope.call(() -> invokeInScope(candidates, request));
    }

    private CassiniHttpResponse invokeInScope(java.util.List<MatchResult> candidates, CassiniHttpExchange request) throws Exception {
        if (candidates.isEmpty()) throw new IllegalArgumentException("no candidates");
        if (application != null) RequestScope.current().application = application;
        MatchResult match = pickBestMatch(candidates, request);
        ResourceMethod route = match.method();
        ParamExtractor.setProviders(new io.vidocq.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        ParamExtractor.setParamConverterProviders(filters.paramConverterProviders());
        try {
            publishBaseUri(request);
            // §3.4.1 dynamic dispatch: the route emitted for a sub-resource locator
            // returning Object is resolved at runtime — invoke the chain,
            // scan the effective class of the returned instance, then delegate.
            if (route.dynamicLocator()) {
                return dynamicDispatch.invokeDynamicLocator(match, request);
            }
            CassiniHttpResponse resp = invokeInternal(match, request, route);
            // §5.1: if Request.selectVariant was called during invocation,
            // its negotiation dimensions are stored in exchange attribute
            // PENDING_VARY → add them to the response Vary header.
            return applyPendingVary(resp, request);
        } finally {
            ParamExtractor.clearProviders();
            ParamExtractor.clearParamConverterProviders();
            FieldInjector.clearFormCache();
            publishCurrent(null, null);
            CassiniResponseBuilder.clearBaseUri();
            // §8.2: the async request processing is over — fire the registered
            // CompletionCallbacks, with success on the normal and mapped paths.
            // The unmapped-throwable path already fired with its cause, and the
            // once-guard in fireCompletion makes this call a no-op then.
            if (request.getAttribute(CassiniAsyncResponseImpl.ATTR_KEY)
                    instanceof CassiniAsyncResponseImpl ar) {
                ar.fireCompletion(null);
            }
        }
    }

    /**
     * The base URI of the request, shared with {@code UriInfo.getBaseUri()} and the response
     * builder: one URI per listener and host, not one parse per request. Best effort: a request
     * that gives none leaves the response builder without a base URI.
     */
    private static void publishBaseUri(CassiniHttpExchange request) {
        try {
            URI u = request.requestUri();
            String scheme = u != null && u.getScheme() != null ? u.getScheme() : schemeOf(request);
            String authority = u != null && u.getAuthority() != null ? u.getAuthority() : authorityOf(request);
            CassiniResponseBuilder.setBaseUri(CassiniUriInfo.baseUri(scheme, authority, request.contextPath()));
        } catch (URISyntaxException | RuntimeException _) {
            // No base URI for this request: nothing to share.
        }
    }

    private static String schemeOf(CassiniHttpExchange request) {
        return request.isSecure() ? "https" : "http";
    }

    private static String authorityOf(CassiniHttpExchange request) {
        String host = request.firstHeader("Host");
        return host != null && !host.isEmpty() ? host : "127.0.0.1";
    }

    /** M2i: best-effort close of a streaming SSE sink on a resource error —
     *  the chunked headers are already committed, EOF is the only signal left. */
    private static void closeStreamingSinkQuietly(CassiniSseEventSink sink) {
        if (sink != null && sink.isStreaming()) {
            try {
                sink.close();
            } catch (Exception _) {
                // Best effort: the client sees the connection end either way.
            }
        }
    }

    /** §5.1: injects the Vary header collected during Request.selectVariant. */
    @SuppressWarnings("unchecked")
    private static CassiniHttpResponse applyPendingVary(CassiniHttpResponse resp,
                                                        CassiniHttpExchange request) {
        var dims = (java.util.Set<String>) request.getAttribute(
                io.vidocq.cassini.internal.context.CassiniRequest.ATTR_PENDING_VARY);
        if (dims == null || dims.isEmpty()) return resp;
        // Rebuild the Response with the additional Vary header.
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


    /**
     * §6.6.1: result of pre-matching filter execution.
     * Contains either a stop {@code response} (abortWith or mapped
     * exception), or (if {@code response == null}) the mutable context used
     * to restart routing with possibly modified method/URI.
     */
    public record PreMatchResult(CassiniHttpResponse response, CassiniRequestContext ctx) {}

    /** §6.6.1: executes pre-matching filters before routing. */
    public PreMatchResult runPreMatching(CassiniHttpExchange request) throws IOException {
        return RequestScope.call(() -> runPreMatchingInScope(request));
    }

    private PreMatchResult runPreMatchingInScope(CassiniHttpExchange request) throws IOException {
        if (filters.preMatching().isEmpty()) return new PreMatchResult(null, null);
        if (application != null) RequestScope.current().application = application;
        ParamExtractor.setProviders(new io.vidocq.cassini.internal.context.CassiniProviders(
                registry, exceptionMappers, filters.contextResolvers()));
        try {
            CassiniRequestContext preCtx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), java.util.Map.of()));
            for (var fe : filters.preMatching()) {
                try { fe.instance().filter(preCtx); }
                catch (IOException e) {
                    CassiniHttpResponse mapped = mapFilterThrowable(e, null, null, preCtx);
                    if (mapped != null) return new PreMatchResult(mapped, preCtx);
                    throw new UncheckedIOException(e);
                } catch (RuntimeException e) {
                    CassiniHttpResponse mapped = mapFilterThrowable(e, null, null, preCtx);
                    if (mapped != null) return new PreMatchResult(mapped, preCtx);
                    throw e;
                }
                if (preCtx.isAborted()) {
                    return new PreMatchResult(
                            runResponseFiltersAndWrite(preCtx, preCtx.abortedResponse(), null, null),
                            preCtx);
                }
            }
            // §6.6.1: once per request — invokeInternal must not run them a second time.
            RequestScope.current().preMatchingDone = true;
            return new PreMatchResult(null, preCtx);
        } finally {
            ParamExtractor.clearProviders();
        }
    }

    /**
     * The request context handed to the response filters when parameter resolution or the
     * entity read fails: the pre-matching context when this call built one, otherwise one built
     * on demand under the same conditions (pre-matching filters registered, response filters to
     * run), {@code null} when no response filter needs it. Keeps the error paths as they were
     * when the pre-matching filters ran, and their context was built, on every invocation.
     */
    private CassiniRequestContext errorContext(CassiniRequestContext preCtx, MatchResult match,
                                               CassiniHttpExchange request) {
        if (preCtx != null) return preCtx;
        if (filters.preMatching().isEmpty() || filters.responseFilters().isEmpty()) return null;
        return new CassiniRequestContext(request, new CassiniUriInfo(
                request, request.contextPath(), match.pathParams()));
    }

    /**
     * §6.6.1: whether {@link #invokeInternal} runs the pre-matching filters: when there are any,
     * and the dispatch did not already run them before routing.
     */
    private boolean preMatchingToRun() {
        if (filters.preMatching().isEmpty()) return false;
        RequestScope scope = RequestScope.current();
        return scope == null || !scope.preMatchingDone;
    }

    /**
     * The state of one resource method invocation, filled phase by phase by
     * {@link #invokeInternal}: what a phase found and the next ones read.
     */
    private static final class Invocation {
        final MatchResult match;
        final CassiniHttpExchange request;
        final ResourceMethod route;
        final MethodParameters declared;
        /** The pre-matching context, when this invocation ran the pre-matching filters. */
        CassiniRequestContext preCtx;
        String contentTypeHeader;
        MediaType contentType;
        MediaType chosen;
        CassiniAsyncResponseImpl asyncResponse;
        Object[] args;
        /** The post-matching context, when there are post-matching or response filters. */
        CassiniRequestContext rctx;
        Object target;
        CassiniSseEventSink sseSink;
        Object result;
        /** What {@link #invokeInternal} throws when a phase returned {@link #FAILED}. */
        Exception failure;

        Invocation(MatchResult match, CassiniHttpExchange request, ResourceMethod route) {
            this.match = match;
            this.request = request;
            this.route = route;
            this.declared = MethodParameters.of(route.javaMethod());
        }

        /** Records a failure no mapper handled: thrown as it is when it is an {@link Exception},
         *  wrapped otherwise. */
        CassiniHttpResponse fail(Throwable cause) {
            failure = cause instanceof Exception ex ? ex : new IllegalStateException(cause);
            return FAILED;
        }
    }

    /** Returned by a phase of {@link #invokeInternal} whose failure is in {@link Invocation#failure};
     *  never written. */
    private static final CassiniHttpResponse FAILED = CassiniHttpResponse.builder().status(500).build();

    /**
     * Runs a matched resource method: the pre-matching filters when the dispatch did not run them,
     * negotiation, the arguments, the post-matching filters, the target instance, the method, its
     * asynchronous result, then the response. Each phase returns the response that ends the
     * request early (an abort, a rendered or mapped failure), or null to go on.
     */
    private CassiniHttpResponse invokeInternal(MatchResult match, CassiniHttpExchange request, ResourceMethod route) throws Exception {
        Invocation inv = new Invocation(match, request, route);
        CassiniHttpResponse early = runPreMatchingFilters(inv);
        if (early == null) early = negotiate(inv);
        if (early == null) {
            prepareAsyncResponse(inv);
            early = resolveArguments(inv);
        }
        if (early == null) early = runPostMatchingFilters(inv);
        if (early == null) early = resolveTarget(inv);
        if (early == null) early = invokeResourceMethod(inv);
        if (early == null) early = awaitResult(inv);
        if (early == FAILED) throw inv.failure;
        if (early != null) return early;
        return inv.sseSink != null ? finishSse(inv.sseSink) : writeResult(inv);
    }

    /**
     * 0. §6.6: the pre-matching request filters, before any negotiation, unless the dispatch
     * already ran them before routing (DefaultCassiniHttpAdapter): §6.6.1, a pre-matching filter
     * runs once per request. A filter failure goes through the ExceptionMappers.
     */
    private CassiniHttpResponse runPreMatchingFilters(Invocation inv) throws IOException {
        if (!preMatchingToRun()) return null;
        inv.preCtx = new CassiniRequestContext(inv.request, new CassiniUriInfo(
                inv.request, inv.request.contextPath(), inv.match.pathParams()));
        for (var fe : filters.preMatching()) {
            CassiniHttpResponse stop = runPreMatchingFilter(fe.instance(), inv);
            if (stop != null) return stop;
        }
        return null;
    }

    private CassiniHttpResponse runPreMatchingFilter(ContainerRequestFilter filter, Invocation inv) throws IOException {
        try {
            filter.filter(inv.preCtx);
        } catch (IOException | RuntimeException e) {
            CassiniHttpResponse mapped = mapFilterThrowable(e, inv.route, null, inv.preCtx);
            if (mapped != null) return mapped;
            throw e;
        }
        return inv.preCtx.isAborted()
                ? runResponseFiltersAndWrite(inv.preCtx, inv.preCtx.abortedResponse(), inv.route, null)
                : null;
    }

    /** 1. §3.7.2: content negotiation; a 415 or a 406 goes through a WebApplicationException so
     *  that an ExceptionMapper can intercept it. */
    private CassiniHttpResponse negotiate(Invocation inv) throws IOException {
        CassiniHttpExchange request = inv.request;
        ResourceMethod route = inv.route;
        inv.contentTypeHeader = request.firstHeader(CONTENT_TYPE);
        inv.contentType = MediaTypes.parse(inv.contentTypeHeader);
        List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        // §3.7.2: if the request has a Content-Type or a body, filter on @Consumes.
        boolean checkConsumes = hasRequestBody(request) || inv.contentTypeHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(inv.contentType, consumes)) {
            return renderWebAppException(new jakarta.ws.rs.NotSupportedException(), route, null, null);
        }
        List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
        List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(accepts, produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return renderWebAppException(new jakarta.ws.rs.NotAcceptableException(), route, null, null);
        }
        inv.chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);
        return null;
    }

    /** §8.2: a @Suspended AsyncResponse is created before the arguments are resolved, so that
     *  ParamExtractor injects it through the exchange attribute. */
    private static void prepareAsyncResponse(Invocation inv) {
        if (inv.declared.suspended() < 0) return;
        CassiniAsyncResponseImpl asyncResponse = new CassiniAsyncResponseImpl();
        inv.asyncResponse = asyncResponse;
        inv.request.setAttribute(CassiniAsyncResponseImpl.ATTR_KEY, asyncResponse);
        // §8.2: if the transport can watch the connection, release the
        // suspended response when the client goes away — fire the
        // registered ConnectionCallbacks, then (if nothing resumed it)
        // fail the completion so the waiting virtual thread is freed
        // instead of working until its own timeout for a dead client.
        inv.request.onClientDisconnect(() -> {
            asyncResponse.fireDisconnect();
            asyncResponse.completionFuture().completeExceptionally(new IOException("Client disconnected"));
        });
    }

    /** 2. The arguments of the resource method, its entity included. A failure goes through the
     *  ExceptionMappers (§4.4). */
    private CassiniHttpResponse resolveArguments(Invocation inv) throws IOException {
        try {
            ParamExtractor.ResolvedArgs resolved = ParamExtractor.resolve(inv.route, inv.match, inv.request);
            inv.args = resolved.args();
            int body = resolved.bodyIndex();
            if (body >= 0) {
                // §4.2.4: Standard providers MUST return a non-null object
                // even for an empty body (String=="", byte[]=new byte[0],
                // InputStream=empty stream, ...). So we always read through an MBR
                // when a body parameter is present. For MBR selection, the default
                // when Content-Type is absent is application/octet-stream.
                MediaType readMt = inv.contentTypeHeader == null
                        ? MediaType.APPLICATION_OCTET_STREAM_TYPE : inv.contentType;
                inv.args[body] = readEntity(inv.declared.get(body), readMt, inv.request, inv.route);
            }
            return null;
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, inv.route, inv.chosen, null);
        } catch (EntityReadException ere) {
            // cassini#39: the reader (or its interceptor chain) failed on the entity.
            return renderEntityReadFailure(ere.getCause(), inv.route, inv.chosen,
                    errorContext(inv.preCtx, inv.match, inv.request), inv.request);
        } catch (IOException e) {
            // §4.4: buffering the entity failed → try ExceptionMapper.
            CassiniHttpResponse mapped = mapFilterThrowable(e, inv.route, inv.chosen,
                    errorContext(inv.preCtx, inv.match, inv.request));
            if (mapped != null) return mapped;
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            // §4.4: resolving a parameter failed → try ExceptionMapper.
            CassiniHttpResponse mapped = mapFilterThrowable(e, inv.route, inv.chosen,
                    errorContext(inv.preCtx, inv.match, inv.request));
            if (mapped != null) return mapped;
            throw e;
        }
    }

    /**
     * 3. The post-matching request filters (§6.6), on a context marked post-matching first, so
     * that the setters reserved to pre-matching filters (setRequestUri, setMethod) throw.
     */
    private CassiniHttpResponse runPostMatchingFilters(Invocation inv) throws IOException {
        if (filters.postMatching().isEmpty() && filters.responseFilters().isEmpty()) return null;
        inv.rctx = new CassiniRequestContext(inv.request, new CassiniUriInfo(
                inv.request, inv.request.contextPath(), inv.match.pathParams()));
        inv.rctx.markPostMatching();
        // §9.2: publish match/request in the scope so injectProviderContexts can
        // populate provider @Context fields (ResourceInfo, UriInfo, etc.).
        // Note: for singleton filters, in-place writes to shared fields
        // are not thread-safe — acceptable as long as we have no per-thread proxy.
        publishCurrent(inv.match, inv.request);
        for (var fe : filters.postMatching()) {
            if (fe.appliesTo(inv.route.javaMethod(), inv.route.beanClass())) {
                // §9.2: @Context injection into the singleton filter.
                injectProviderContexts(fe.instance(), inv.request);
                CassiniHttpResponse stop = runPostMatchingFilter(fe.instance(), inv);
                if (stop != null) return stop;
            }
        }
        return null;
    }

    private CassiniHttpResponse runPostMatchingFilter(ContainerRequestFilter filter, Invocation inv) throws IOException {
        try {
            filter.filter(inv.rctx);
        } catch (IOException e) {
            CassiniHttpResponse mapped = mapFilterThrowable(e, inv.route, inv.chosen, inv.rctx);
            if (mapped != null) return mapped;
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            CassiniHttpResponse mapped = mapFilterThrowable(e, inv.route, inv.chosen, inv.rctx);
            if (mapped != null) return mapped;
            throw e;
        }
        return inv.rctx.isAborted()
                ? runResponseFiltersAndWrite(inv.rctx, inv.rctx.abortedResponse(), inv.route, inv.chosen)
                : null;
    }

    /** 4. The instance the resource method runs on, with its fields injected (§9.2): the root
     *  resource, or the last object of its sub-resource locator chain (§3.4.1). */
    private CassiniHttpResponse resolveTarget(Invocation inv) throws IOException, IllegalAccessException {
        publishCurrent(inv.match, inv.request);
        List<Object> matched = new ArrayList<>();
        inv.request.setAttribute(ATTR_MATCHED_RESOURCES, matched);
        ResourceMethod route = inv.route;
        try {
            if (route.isLocated()) return walkLocators(inv, matched);
            inv.target = resolver.apply(route.beanClass());
            // §9: inject @Context into the real contextual instance (de-proxied),
            // but invoke the method on `target` (the CDI proxy delegates to that instance).
            injectResourceFields(route.beanClass(), inv.target, inv.match, inv.request);
            matched.add(inv.target);
            return null;
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, inv.chosen, inv.rctx);
        } catch (InvocationTargetException ite) {
            if (ite.getCause() instanceof WebApplicationException wae) {
                return renderWebAppException(wae, route, inv.chosen, inv.rctx);
            }
            return inv.fail(ite.getCause());
        }
    }

    /** §3.4.1: instantiates the root resource and walks the locator chain, injecting the fields
     *  of each step; the last object is the target. */
    private CassiniHttpResponse walkLocators(Invocation inv, List<Object> matched)
            throws IOException, IllegalAccessException, InvocationTargetException {
        ResourceMethod route = inv.route;
        Object root = resolver.apply(route.rootBeanClass());
        injectResourceFields(route.rootBeanClass(), root, inv.match, inv.request);
        matched.add(0, root);
        Object intermediate = root;
        for (Method locStep : route.locatorChain()) {
            Object[] lArgs = ParamExtractor.resolveConstructorArgs(locStep.getParameters(), inv.match, inv.request);
            locStep.setAccessible(true); // NOSONAR java:S3011 - a locator of a non-public resource class (§3.4.1)
            intermediate = locStep.invoke(intermediate, lArgs);
            if (intermediate == null) {
                return renderWebAppException(
                        new WebApplicationException("Sub-resource locator returned null", 404),
                        route, inv.chosen, inv.rctx);
            }
            // §3.4.2: a locator may return Class<T> — runtime
            // instantiates the class through its no-arg constructor.
            // M6a: prefer generated adapter newInstance() over reflection
            if (intermediate instanceof Class<?> cls) {
                Object created = instantiateSubResourceClass(cls, route, inv.chosen, inv.rctx);
                if (created instanceof CassiniHttpResponse failed) return failed;
                intermediate = created;
            }
            injectFields(intermediate.getClass(), intermediate, inv.match, inv.request, false);
            matched.add(0, intermediate);
        }
        inv.target = intermediate;
        return null;
    }

    /** 4. Runs the resource method: through its generated adapter when there is one (P1b), by
     *  reflection otherwise. */
    private CassiniHttpResponse invokeResourceMethod(Invocation inv) throws Exception {
        openSseSink(inv);
        ResourceMethod route = inv.route;
        var adapter = AdapterRegistry.lookup(route.beanClass());
        int mid = adapter.isPresent() ? AdapterRegistry.methodId(route.beanClass(), route.javaMethod()) : -1;
        if (adapter.isPresent() && mid >= 0) {
            try {
                inv.result = adapter.get().invoke(mid, inv.target, inv.args);
                return null;
            } catch (Throwable t) {
                // M2i: if streaming already started, the headers are committed —
                // close the pipe so the client sees EOF instead of hanging.
                closeStreamingSinkQuietly(inv.sseSink);
                return handleResourceThrowable(t, route, inv.chosen, inv.rctx);
            }
        }
        try {
            inv.result = route.javaMethod().invoke(inv.target, inv.args);
            return null;
        } catch (IllegalArgumentException iae) {
            throw new IllegalArgumentException(argumentMismatch(route, inv.args), iae);
        } catch (InvocationTargetException ite) {
            closeStreamingSinkQuietly(inv.sseSink);
            return handleResourceThrowable(ite.getCause(), route, inv.chosen, inv.rctx);
        }
    }

    /** §11.1: an SseEventSink parameter gets our sink. When the transport can stream (chunked),
     *  the events go directly to the wire. */
    private void openSseSink(Invocation inv) {
        int index = inv.declared.sseSink();
        if (index < 0) return;
        var streamingHeaders = new LinkedHashMap<String, List<String>>();
        streamingHeaders.put(CONTENT_TYPE, List.of("text/event-stream;charset=utf-8"));
        streamingHeaders.put("Cache-Control", List.of("no-cache"));
        var streamingSink = inv.request.openForStreaming(200, streamingHeaders);
        inv.sseSink = new CassiniSseEventSink(registry, streamingSink);
        inv.args[index] = inv.sseSink;
        ParamExtractor.setCurrentSink(inv.sseSink);
    }

    private static String argumentMismatch(ResourceMethod route, Object[] args) {
        StringBuilder sb = new StringBuilder("Argument mismatch on ")
                .append(route.beanClass().getName()).append('.')
                .append(route.javaMethod().getName()).append("(): ");
        Class<?>[] types = route.javaMethod().getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            sb.append("\n  [").append(i).append("] param=").append(types[i].getSimpleName())
                    .append(" arg=").append(args[i] == null ? "null" : args[i].getClass().getSimpleName());
        }
        return sb.toString();
    }

    /** §8.2 and §9.2: the result of a suspended response, then of a CompletionStage the method
     *  returned. The virtual thread waits for it without holding its carrier (M2h). */
    private CassiniHttpResponse awaitResult(Invocation inv) throws IOException {
        if (inv.asyncResponse != null) {
            CassiniHttpResponse failed = awaitAsyncResponse(inv);
            if (failed != null) return failed;
        }
        if (inv.result instanceof CompletionStage<?> stage) return awaitCompletionStage(inv, stage);
        return null;
    }

    private CassiniHttpResponse awaitAsyncResponse(Invocation inv) throws IOException {
        try {
            inv.result = inv.asyncResponse.completionFuture().get();
            return null;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            CassiniHttpResponse handled = renderOrMap(inv, cause);
            if (handled != null) return handled;
            // §8.2: the processing ends with an UNMAPPED throwable, and
            // CompletionCallback.onComplete receives it. The rendered and mapped paths
            // fire onComplete(null) from the finally block of invokeInScope.
            inv.asyncResponse.fireCompletion(cause);
            return inv.fail(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for AsyncResponse.resume()", ie);
        }
    }

    private CassiniHttpResponse awaitCompletionStage(Invocation inv, CompletionStage<?> stage) throws IOException {
        try {
            inv.result = stage.toCompletableFuture().get();
            return null;
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            CassiniHttpResponse handled = renderOrMap(inv, cause);
            return handled != null ? handled : inv.fail(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while awaiting CompletionStage", ie);
        }
    }

    /** The failure of an asynchronous result: rendered when it is a WebApplicationException,
     *  mapped by an ExceptionMapper otherwise; null when no mapper handles it. */
    private CassiniHttpResponse renderOrMap(Invocation inv, Throwable cause) throws IOException {
        if (cause instanceof WebApplicationException wae) {
            return renderWebAppException(wae, inv.route, inv.chosen, inv.rctx);
        }
        var mapped = exceptionMappers.map(cause);
        return mapped.isPresent() ? runResponseFiltersAndWrite(inv.rctx, mapped.get(), inv.route, inv.chosen) : null;
    }

    /** §11.1: the response of an SSE resource method once it returned. */
    private static CassiniHttpResponse finishSse(CassiniSseEventSink sseSink) {
        ParamExtractor.clearCurrentSink();
        if (sseSink.isStreaming()) {
            // M2i: the method returned normally — force the lazy transport
            // commit so a sink registered for later events (broadcaster
            // pattern) keeps its connection open even with zero events sent.
            sseSink.commitStreaming();
            // Streaming mode: the response is (being) sent on the wire.
            return CassiniHttpResponse.builder().status(200).body(new byte[0]).build();
        }
        // Buffered mode: wait for sink.close() then send the whole buffer at once.
        sseSink.awaitClose();
        return CassiniHttpResponse.builder()
                .status(200)
                .header(CONTENT_TYPE, "text/event-stream")
                .body(sseSink.toByteArray())
                .build();
    }

    /** 5. Marshals the result and runs the response filters; a WriterInterceptor failure goes
     *  through the ExceptionMappers. */
    private CassiniHttpResponse writeResult(Invocation inv) throws IOException {
        try {
            if (inv.rctx != null && !filters.responseFilters().isEmpty()) {
                return runResponseFiltersForResult(inv.rctx, inv.result, inv.route, inv.chosen);
            }
            return marshal(inv.result, inv.route, inv.chosen);
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, inv.route, inv.chosen, inv.rctx);
        } catch (RuntimeException re) {
            CassiniHttpResponse mapped = mapFilterThrowable(re, inv.route, inv.chosen, inv.rctx);
            if (mapped != null) return mapped;
            throw re;
        }
    }

    // Entity I/O + response writing — delegated to ResponsePipeline.

    CassiniHttpResponse runResponseFiltersAndWrite(CassiniRequestContext rctx,
                                                jakarta.ws.rs.core.Response userResp,
                                                ResourceMethod route, MediaType chosen) throws IOException {
        return pipeline.runResponseFiltersAndWrite(rctx, userResp, route, chosen);
    }

    CassiniHttpResponse runResponseFiltersForResult(CassiniRequestContext rctx, Object result,
                                                  ResourceMethod route, MediaType chosen) throws IOException {
        return pipeline.runResponseFiltersForResult(rctx, result, route, chosen);
    }

    Object readEntity(MethodParameters.Param p, MediaType ct, CassiniHttpExchange request, ResourceMethod route) throws IOException {
        return pipeline.readEntity(p, ct, request, route);
    }

    CassiniHttpResponse marshal(Object result, ResourceMethod route, MediaType chosen) throws IOException {
        return pipeline.marshal(result, route, chosen);
    }

    CassiniHttpResponse fromJaxRs(jakarta.ws.rs.core.Response jr, ResourceMethod route,
                               MediaType fallback) throws IOException {
        return pipeline.fromJaxRs(jr, route, fallback);
    }

    /** §9.2: injects the @Context fields of a singleton provider before
     *  the readFrom/writeTo call using the current match and request
     *  (carried by the per-request scope).
     *
     *  <p>M5a: routes injection through the generated adapter when available,
     *  keeping the reflective {@link FieldInjector} as a safety-net fallback.
     *  {@code injectParams=false} — providers carry only {@code @Context} fields,
     *  never {@code @PathParam}/{@code @QueryParam}/etc.</p> */
    void injectProviderContexts(Object provider, CassiniHttpExchange requestOpt) {
        // Skip les classes builtin internes (pas de @Context dedans, optimisation).
        Class<?> cls = provider.getClass();
        if (cls.getName().startsWith("io.vidocq.cassini.internal.MessageBodyRegistry$")) return;
        CassiniHttpExchange req = requestOpt != null ? requestOpt : currentRequest();
        MatchResult match = currentMatch();
        if (req == null || match == null) return;
        // M5a: prefer generated adapter (VarHandle, no per-request reflection); fall back
        // to FieldInjector when the adapter cannot be generated (private cross-package field,
        // module closure, etc.).
        var adapter = AdapterRegistry.lookup(cls);
        try {
            if (adapter.isPresent()) {
                adapter.get().injectFields(provider, new InjectionSupportImpl(match, req), false);
            } else {
                FieldInjector.inject(provider, match, req);
            }
        } catch (RuntimeException _) {
            // A provider whose fields cannot be injected keeps them as they are.
        }
    }

    /**
     * Injects the {@code @Context}, {@code @BeanParam} and {@code @*Param} fields of a resource
     * instance (§3.2, §9.2) into its contextual instance ({@link #injectionTarget}). A class that
     * declares none, the common case, costs neither the injection nor the bean provider lookup.
     */
    void injectResourceFields(Class<?> beanClass, Object resolved, MatchResult match, CassiniHttpExchange request) {
        if (resolved == null) return;
        if (!InjectableFields.declaredBy(beanClass) && !InjectableFields.declaredBy(resolved.getClass())) return;
        injectFields(beanClass, injectionTarget(beanClass, resolved), match, request, true);
    }

    /**
     * Seam: injects fields into {@code target} using the registered adapter if available,
     * otherwise falls back to {@link FieldInjector#inject}.
     *
     * <p>P0: {@link AdapterRegistry#lookup} always returns empty → always falls back.
     * P1: when an adapter is present, calls {@code adapter.injectFields(target, support, injectParams)}.</p>
     *
     * @param beanClass    the resource class (key for adapter lookup)
     * @param target       the injection target (already unwrapped from CDI proxy)
     * @param match        the routing result
     * @param request      the HTTP exchange
     * @param injectParams whether to inject @*Param fields (false for sub-resource roots per §3.4.1)
     */
    void injectFields(Class<?> beanClass, Object target,
                              MatchResult match, CassiniHttpExchange request, boolean injectParams) {
        var adapter = AdapterRegistry.lookup(beanClass);
        if (adapter.isPresent()) {
            adapter.get().injectFields(target, new InjectionSupportImpl(match, request), injectParams);
        } else {
            FieldInjector.inject(target, match, request, injectParams);
        }
    }

    // §3.7.2 negotiation scoring — delegated to RouteNegotiation.

    boolean hasRequestBody(CassiniHttpExchange request) {
        return RouteNegotiation.hasRequestBody(request);
    }

    MatchResult pickBestMatch(java.util.List<MatchResult> candidates, CassiniHttpExchange request) {
        return RouteNegotiation.pickBestMatch(candidates, request);
    }

    /**
     * Shared handler for exceptions thrown by a resource method — whether via reflective
     * {@code Method.invoke} ({@code ite.getCause()}) or via the direct adapter call.
     *
     * <p>Priority:</p>
     * <ol>
     *   <li>WAE → {@link #renderWebAppException}</li>
     *   <li>ExceptionMapper present → {@link #runResponseFiltersAndWrite} (response filters run)</li>
     *   <li>Exception subtype → rethrow as-is</li>
     *   <li>Throwable → wrap in IllegalStateException and rethrow</li>
     * </ol>
     *
     * @param cause  the raw throwable (getCause() already extracted for ITE callers)
     * @param route  the matched resource method
     * @param chosen the negotiated media type
     * @param rctx   the request context (may be null if post-matching filters not started)
     * @return a response if the exception was mapped; otherwise never returns (throws)
     * @throws Exception rethrown if not mapped
     */
    CassiniHttpResponse handleResourceThrowable(Throwable cause,
                                                        ResourceMethod route,
                                                        MediaType chosen,
                                                        CassiniRequestContext rctx) throws Exception {
        if (cause instanceof WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, rctx);
        }
        var mapped = exceptionMappers.map(cause);
        if (mapped.isPresent()) {
            // Run response filters if we have a request context (post-matching path),
            // otherwise fall back to fromJaxRs (dynamic-locator final path has no rctx).
            if (rctx != null && !filters.responseFilters().isEmpty()) {
                return runResponseFiltersAndWrite(rctx, mapped.get(), route, chosen);
            }
            return fromJaxRs(mapped.get(), route, chosen);
        }
        if (cause instanceof Exception ex) throw ex;
        throw new IllegalStateException(cause);
    }

    /** §3.7.2 / §4.4: renders a response for an exception outside the scope of
     *  resolution (no MatchResult — typically 404/405 from the bridge).
     *  Checks application ExceptionMappers first; otherwise returns the
     *  Response carried by the WAE, or a default 500. */
    public CassiniHttpResponse renderThrowable(Throwable t) throws IOException {
        return RequestScope.call(() -> renderThrowableInScope(t));
    }

    private CassiniHttpResponse renderThrowableInScope(Throwable t) throws IOException {
        if (application != null) RequestScope.current().application = application;
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
                    .header(CONTENT_TYPE, "text/plain;charset=utf-8")
                    .body(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8)).build();
        } finally {
            ParamExtractor.clearProviders();
        }
    }

    /** §4.4: if a filter / interceptor throws an exception, pass it to the
     *  ExceptionMapper if one exists. Returns {@code null} if no mapper
     *  applies — the caller decides whether to rethrow it. */
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

    /**
     * cassini#39: renders a failure of the request-entity reader — the selected
     * {@link MessageBodyReader} or the {@code ReaderInterceptor} chain around it —
     * other than a {@link WebApplicationException} (rendered as such) and a
     * {@code NoContentException} (already a {@code BadRequestException}, §4.2.4).
     * <ol>
     *   <li>An application {@code ExceptionMapper} for the raw failure wins, as before:
     *       {@code ExceptionMapper<JsonbException>} workarounds and catch-all mappers
     *       keep working.</li>
     *   <li>A failure that says the server cannot bind the type at all (a reflective
     *       access, module-export or linkage error in its causes, see
     *       {@link EntityReadFailures#isServerSide}) stays a server error: rethrown, so
     *       the transport answers 500 and logs it at ERROR, as before.</li>
     *   <li>Otherwise it is the client's fault: one DEBUG line (stack at TRACE), then a
     *       {@code BadRequestException} wrapping the failure, which the application's
     *       {@code BadRequestException} / {@code ClientErrorException} /
     *       {@code WebApplicationException} mappers may shape. Unmapped, it is a 400
     *       with an empty body.</li>
     * </ol>
     * <p>Step 1 must never reach a built-in catch-all mapper: if a default
     * {@code ExceptionMapper<Throwable>} (§4.4) is ever registered in
     * {@link ExceptionMapperRegistry}, skip it here, or it swallows step 3 —
     * {@code EntityReadFailureTest} fails if that happens.</p>
     */
    CassiniHttpResponse renderEntityReadFailure(Throwable failure, ResourceMethod route, MediaType chosen,
                                                CassiniRequestContext rctx,
                                                CassiniHttpExchange request) throws IOException {
        CassiniHttpResponse mapped = mapFilterThrowable(failure, route, chosen, rctx);
        if (mapped != null) return mapped;
        if (EntityReadFailures.isServerSide(failure)) {
            // The server cannot bind the type at all (module export, linkage, reflective
            // access): its own fault, not the client's — the transport answers 500 and
            // logs it at ERROR, as before cassini#39.
            if (failure instanceof RuntimeException re) throw re;
            throw new IllegalStateException(failure);
        }
        EntityReadFailures.logRejected(request, failure);
        return renderWebAppException(new jakarta.ws.rs.BadRequestException(failure), route, chosen, rctx);
    }

    CassiniHttpResponse renderWebAppException(WebApplicationException wae, ResourceMethod route,
                                           MediaType chosen, CassiniRequestContext rctx) throws IOException {
        jakarta.ws.rs.core.Response r = wae.getResponse();
        // §4.3.1: if the embedded response has an entity, the mapper MUST NOT be invoked.
        if (r != null && r.hasEntity()) {
            if (rctx != null && !filters.responseFilters().isEmpty())
                return runResponseFiltersAndWrite(rctx, r, route, chosen);
            return fromJaxRs(r, route, chosen);
        }
        // §4.4: otherwise, try the ExceptionMapper.
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
                .header(CONTENT_TYPE, "text/plain;charset=utf-8")
                .body(msg.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
    }

    /**
     * M6a: instantiates a sub-resource class returned by a sub-resource locator.
     * Prefers the generated adapter's {@code newInstance()} over reflection.
     * Returns the new instance, or a {@link CassiniHttpResponse} error if instantiation fails.
     *
     * @param rctx may be {@code null} (in dynamic-locator paths that run before post-matching filters)
     */
    Object instantiateSubResourceClass(Class<?> cls, ResourceMethod route,
                                                MediaType chosen, CassiniRequestContext rctx) throws IOException {
        // M6a: try generated adapter newInstance() first
        var adapter = AdapterRegistry.lookup(cls);
        if (adapter.isPresent()) {
            try {
                return adapter.get().newInstance();
            } catch (UnsupportedOperationException _) {
                // no no-arg ctor — fall through to reflective path
            }
        }
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            return renderWebAppException(
                    new WebApplicationException(
                            "Cannot instantiate sub-resource " + cls.getName() + ": " + e.getMessage(), 500),
                    route, chosen, rctx);
        }
    }
}
