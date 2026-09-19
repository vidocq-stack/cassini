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

import io.vidocq.cassini.internal.gen.AdapterRegistry;
import io.vidocq.cassini.internal.gen.InjectionSupportImpl;

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
 * Executes a {@link ResourceMethod}: content negotiation, body reading via
 * {@link MessageBodyReader}, invocation, and serialization via
 * {@link MessageBodyWriter}.
 */
public final class Invoker {

    /** Exchange attribute key for the matched-instance chain (M2h). */
    public static final String ATTR_MATCHED_RESOURCES = "cassini.matched_resources";

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
            io.vidocq.cassini.internal.runtime.CassiniResponseBuilder.clearBaseUri();
            // §8.2: the async request processing is over — fire the registered
            // CompletionCallbacks. Normal/mapped paths report success (null);
            // the unmapped-throwable path already fired with the cause (the
            // once-guard in fireCompletion makes this a no-op then).
            if (request.getAttribute(CassiniAsyncResponseImpl.ATTR_KEY)
                    instanceof CassiniAsyncResponseImpl ar) {
                ar.fireCompletion(null);
            }
        }
    }

    /** M2i: best-effort close of a streaming SSE sink on a resource error —
     *  the chunked headers are already committed, EOF is the only signal left. */
    private static void closeStreamingSinkQuietly(io.vidocq.cassini.internal.sse.CassiniSseEventSink sink) {
        if (sink != null && sink.isStreaming()) {
            try {
                sink.close();
            } catch (Exception ignored) { /* best effort */ }
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
    public PreMatchResult runPreMatching(CassiniHttpExchange request) throws Exception {
        return RequestScope.call(() -> runPreMatchingInScope(request));
    }

    private PreMatchResult runPreMatchingInScope(CassiniHttpExchange request) throws Exception {
        if (filters.preMatching().isEmpty()) return new PreMatchResult(null, null);
        if (application != null) RequestScope.current().application = application;
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

        // 0. Pre-matching request filters §6.6 — executed before any
        //    negotiation. If they throw, go through ExceptionMapper.
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
        // §3.7.2: if the request has a Content-Type or a body, filter on @Consumes.
        boolean checkConsumes = hasRequestBody(request) || ctHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(contentType, consumes)) {
            // §3.7.2: 415 via WAE so ExceptionMapper can intercept.
            return renderWebAppException(new jakarta.ws.rs.NotSupportedException(), route, null, null);
        }

        List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
        List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(accepts, produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return renderWebAppException(new jakarta.ws.rs.NotAcceptableException(), route, null, null);
        }
        MediaType chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);

        // §8.2: @Suspended AsyncResponse — create the impl BEFORE parameter extraction
        // so ParamExtractor can inject it through the exchange attribute.
        CassiniAsyncResponseImpl asyncResponse = null;
        for (java.lang.reflect.Parameter p : route.javaMethod().getParameters()) {
            if (p.getAnnotation(jakarta.ws.rs.container.Suspended.class) != null
                    && jakarta.ws.rs.container.AsyncResponse.class.isAssignableFrom(p.getType())) {
                asyncResponse = new CassiniAsyncResponseImpl();
                request.setAttribute(CassiniAsyncResponseImpl.ATTR_KEY, asyncResponse);
                // §8.2: if the transport can watch the connection, release the
                // suspended response when the client goes away — fire the
                // registered ConnectionCallbacks, then (if nothing resumed it)
                // fail the completion so the waiting virtual thread is freed
                // instead of working until its own timeout for a dead client.
                final CassiniAsyncResponseImpl ar = asyncResponse;
                request.onClientDisconnect(() -> {
                    ar.fireDisconnect();
                    ar.completionFuture().completeExceptionally(
                            new java.io.IOException("Client disconnected"));
                });
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
                // §4.2.4: Standard providers MUST return a non-null object
                // even for an empty body (String=="", byte[]=new byte[0],
                // InputStream=empty stream, ...). So we always read through an MBR
                // when a body parameter is present.
                // §4.2.4 (continued): for MBR selection, the default when
                // Content-Type is absent is application/octet-stream.
                MediaType readMt = (ctHeader == null) ? MediaType.APPLICATION_OCTET_STREAM_TYPE : contentType;
                args[resolved.bodyIndex()] = readEntity(p, readMt, request, route);
            }
        } catch (WebApplicationException wae) {
            return renderWebAppException(wae, route, chosen, null);
        } catch (EntityReadException ere) {
            // cassini#39: the reader (or its interceptor chain) failed on the entity.
            return renderEntityReadFailure(ere.getCause(), route, chosen, preCtx, request);
        } catch (RuntimeException | java.io.IOException re) {
            // §4.4: parameter resolution or body buffering failed
            // (RuntimeException or IOException) → try ExceptionMapper.
            CassiniHttpResponse mapped = mapFilterThrowable(re, route, chosen, preCtx);
            if (mapped != null) return mapped;
            if (re instanceof RuntimeException rrt) throw rrt;
            throw new RuntimeException(re);
        }

        // 3. Post-matching request filters — mark the context as
        //    post-matching before execution so protected setters
        //    (setRequestUri, setMethod, etc.) throw ISE §6.6.
        CassiniRequestContext rctx = null;
        if (!filters.postMatching().isEmpty() || !filters.responseFilters().isEmpty()) {
            rctx = new CassiniRequestContext(request, new CassiniUriInfo(
                    request, request.contextPath(), match.pathParams()));
            rctx.markPostMatching();
            // §9.2: publish match/request in the scope so injectProviderContexts can
            // populate provider @Context fields (ResourceInfo, UriInfo, etc.).
            // Note: for singleton filters, in-place writes to shared fields
            // are not thread-safe — acceptable as long as we have no per-thread proxy.
            publishCurrent(match, request);
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
        publishCurrent(match, request);
        java.util.List<Object> matched = new java.util.ArrayList<>();
        request.setAttribute(ATTR_MATCHED_RESOURCES, matched);
        try {
            if (route.isLocated()) {
                // Sub-resource locator §3.4.1: instantiate the root resource,
                // walk the locator chain, inject fields at each step.
                Object root = resolver.apply(route.rootBeanClass());
                injectFields(route.rootBeanClass(), injectionTarget(route.rootBeanClass(), root), match, request, true);
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
                    // §3.4.2: a locator may return Class<T> — runtime
                    // instantiates the class through its no-arg constructor.
                    // M6a: prefer generated adapter newInstance() over reflection
                    if (intermediate instanceof Class<?> cls) {
                        Object created = instantiateSubResourceClass(cls, route, chosen, rctx);
                        if (created instanceof CassiniHttpResponse) return (CassiniHttpResponse) created;
                        intermediate = created;
                    }
                    injectFields(intermediate.getClass(), intermediate, match, request, false);
                    matched.add(0, intermediate);
                }
                target = intermediate;
            } else {
                target = resolver.apply(route.beanClass());
                // §9: inject @Context into the real contextual instance (de-proxied),
                // but invoke the method on `target` (the CDI proxy delegates to that instance).
                injectFields(route.beanClass(), injectionTarget(route.beanClass(), target), match, request, true);
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
        // §11.1: if the method has an SseEventSink parameter, replace
        // the arg with our instance (CassiniSseEventSink). In streaming mode
        // (JDK transport), events are written directly to the wire.
        io.vidocq.cassini.internal.sse.CassiniSseEventSink sseSink = null;
        Parameter[] params = route.javaMethod().getParameters();
        for (int pi = 0; pi < params.length; pi++) {
            if (params[pi].getType() == jakarta.ws.rs.sse.SseEventSink.class) {
                // Try to open chunked streaming (JDK transport).
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
        // P1b: attempt direct typed dispatch via generated adapter; fall back to reflection.
        {
            var adapter = AdapterRegistry.lookup(route.beanClass());
            int mid = adapter.isPresent()
                    ? AdapterRegistry.methodId(route.beanClass(), route.javaMethod()) : -1;
            if (adapter.isPresent() && mid >= 0) {
                try {
                    result = adapter.get().invoke(mid, target, args);
                } catch (Throwable t) {
                    // M2i: if streaming already started, the headers are committed —
                    // close the pipe so the client sees EOF instead of hanging.
                    closeStreamingSinkQuietly(sseSink);
                    return handleResourceThrowable(t, route, chosen, rctx);
                }
            } else {
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
                    closeStreamingSinkQuietly(sseSink);
                    return handleResourceThrowable(ite.getCause(), route, chosen, rctx);
                }
            }
        }

        // §8.2: @Suspended AsyncResponse — block the virtual thread until resume().
        // The virtual thread yields its carrier without starvation (M2h).
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
                // §8.2: the processing ends with an UNMAPPED throwable —
                // CompletionCallback.onComplete receives it (mapped/WAE paths
                // fire onComplete(null) from invokeInScope's finally).
                asyncResponse.fireCompletion(cause);
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted waiting for AsyncResponse.resume()", ie);
            }
        }

        // §9.2: CompletionStage<T> returned by a resource method.
        // We block the virtual thread (M2h) — releasing the carrier without starvation.
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

        // §11.1: SSE method.
        if (sseSink != null) {
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
            byte[] body = sseSink.toByteArray();
            return CassiniHttpResponse.builder()
                    .status(200)
                    .header("Content-Type", "text/event-stream")
                    .body(body)
                    .build();
        }
        // 5. Marshal + response filters — WriterInterceptors can
        //    throw exceptions: routed via ExceptionMapper.
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

    Object readEntity(Parameter p, MediaType ct, CassiniHttpExchange request, ResourceMethod route) throws IOException {
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
        } catch (RuntimeException ignored) {}
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
     *   <li>Throwable → wrap in RuntimeException and rethrow</li>
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
        throw new RuntimeException(cause);
    }

    /** §3.7.2 / §4.4: renders a response for an exception outside the scope of
     *  resolution (no MatchResult — typically 404/405 from the bridge).
     *  Checks application ExceptionMappers first; otherwise returns the
     *  Response carried by the WAE, or a default 500. */
    public CassiniHttpResponse renderThrowable(Throwable t, CassiniHttpExchange request) throws IOException {
        return RequestScope.call(() -> renderThrowableInScope(t, request));
    }

    private CassiniHttpResponse renderThrowableInScope(Throwable t, CassiniHttpExchange request) throws IOException {
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
                    .header("Content-Type", "text/plain;charset=utf-8")
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
     *   <li>Otherwise it is the client's fault: one DEBUG line (stack at TRACE), then a
     *       {@code BadRequestException} wrapping the failure, which the application's
     *       {@code BadRequestException} / {@code ClientErrorException} /
     *       {@code WebApplicationException} mappers may shape. Unmapped, it is a 400
     *       with an empty body.</li>
     * </ol>
     * <p>Step 1 must never reach a built-in catch-all mapper: if a default
     * {@code ExceptionMapper<Throwable>} (§4.4) is ever registered in
     * {@link ExceptionMapperRegistry}, skip it here, or it swallows step 2 —
     * {@code EntityReadFailureTest} fails if that happens.</p>
     */
    CassiniHttpResponse renderEntityReadFailure(Throwable failure, ResourceMethod route, MediaType chosen,
                                                CassiniRequestContext rctx,
                                                CassiniHttpExchange request) throws IOException {
        CassiniHttpResponse mapped = mapFilterThrowable(failure, route, chosen, rctx);
        if (mapped != null) return mapped;
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
                .header("Content-Type", "text/plain;charset=utf-8")
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
            } catch (UnsupportedOperationException ignored) {
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
