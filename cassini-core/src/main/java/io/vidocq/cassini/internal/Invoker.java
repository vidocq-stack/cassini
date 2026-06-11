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
        } catch (RuntimeException | java.io.IOException re) {
            // §4.4: MessageBodyReader / ReaderInterceptor may throw
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
                // Streaming mode (JDK): the response has already been sent on the wire.
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
            // route null = pre-matching abort/exception §6.5.2: only globally
            // bound filters (without @NameBinding) apply.
            // appliesTo(null,null) returns true for globals and false
            // for NameBound ones — rely on that for filtering.
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
            // Entity annotations are internal to CassiniResponse
            // (§6.7.4: exposed through ContainerResponseContext.getEntityAnnotations).
            if (jr2 instanceof io.vidocq.cassini.internal.runtime.CassiniResponse cr) {
                entityAnnotations = cr.entityAnnotations();
            }
        }
        // §6.7.4: getEntityAnnotations() returns the resource-method annotations
        // merged with those explicitly passed to
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
        // §6.7.4.2: if a ContainerResponseFilter wrapped the entityStream
        // via setEntityStream(), MBW.writeTo must write into that wrapper —
        // the wrapper forwards to the originalStream collected by the runtime.
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

    /** §9.2: injects the @Context fields of a singleton provider before
     *  the readFrom/writeTo call using the current match and request
     *  (carried by the per-request scope).
     *
     *  <p>M5a: routes injection through the generated adapter when available,
     *  keeping the reflective {@link FieldInjector} as a safety-net fallback.
     *  {@code injectParams=false} — providers carry only {@code @Context} fields,
     *  never {@code @PathParam}/{@code @QueryParam}/etc.</p> */
    private void injectProviderContexts(Object provider, CassiniHttpExchange requestOpt) {
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

    boolean hasRequestBody(CassiniHttpExchange request) {
        long len = request.contentLength();
        if (len > 0) return true;
        // chunked encoding → contentLength may be -1 ; rely on presence of Content-Type
        return request.firstHeader("Content-Type") != null && len != 0;
    }

    Object readEntity(Parameter p, MediaType ct, CassiniHttpExchange request, ResourceMethod route) throws IOException {
        Class<?> type = p.getType();
        Type genericType = p.getParameterizedType();
        Annotation[] anns = p.getAnnotations();
        MultivaluedMap<String, String> headers = MessageBodyRegistry.adaptExchangeHeaders(request.requestHeaders());

        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyReader reader = registry.findReader(type, genericType, anns, ct).orElse(null);
        // §7.2: if no MBR matches initially but ReaderInterceptors are
        // registered, delay selection — an interceptor may rewrite
        // type/mediaType (setType, setMediaType) to match a different MBR.
        var rInterceptorsForChoice = route == null ? filters.readerInterceptorsFor(null, null)
                : filters.readerInterceptorsFor(route.javaMethod(), route.beanClass());
        if (reader == null && rInterceptorsForChoice.isEmpty()) {
            throw new WebApplicationException(
                    "No MessageBodyReader for " + type.getName() + " / " + MediaTypes.format(ct), 415);
        }
        // §9.2: @Context fields of user-level providers (singletons) are
        // re-injected on each call to expose the current context.
        if (reader != null) injectProviderContexts(reader, request);
        // If @FormParam already consumed the body, replay it from cache (exchange attribute, M2h).
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

    CassiniHttpResponse marshal(Object result, ResourceMethod route, MediaType chosen) throws IOException {
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
        // §4.2.4: GenericEntity describes a parameterized type; unwrap it and
        // use the effective "raw"/"genericType" for the MBW.
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
        // §4.2.4: if the method did not specify Content-Type, inherit it from
        // the selected MBW's @Produces (first declared concrete media type).
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
        // Populate outHeaders with extraHeaders BEFORE the interceptor chain
        // so WriterInterceptor.aroundWriteTo.getHeaders() sees what the
        // ResourceMethod/ResponseBuilder produced. Interceptors
        // may still mutate it; read it back afterwards for the build.
        for (var e : extraHeaders.entrySet())
            for (String v : e.getValue()) outHeaders.add(e.getKey(), v);
        // §6.5.2: if route==null (pre-matching abort/exception), only globally
        // bound writer interceptors apply.
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
            // User Content-Type: re-emit it explicitly (filtered above).
            if (jr.getMediaType() != null) b.header("Content-Type", MediaTypes.format(jr.getMediaType()));
            return b.build();
        }
        // §7.2 / §4.2.4: when the method declares a Response return (wrapper),
        // the genericType passed to MBW / WriterInterceptorContext is that of
        // the real entity, not Response.class.
        Type gt;
        if (route == null) {
            gt = entity.getClass();
        } else {
            Type ret = route.javaMethod().getGenericReturnType();
            gt = (ret == jakarta.ws.rs.core.Response.class) ? entity.getClass() : ret;
        }
        // §4.2.4: if the user passed annotations through
        // ResponseBuilder.entity(Object, Annotation[]), they take precedence over
        // method annotations for MessageBodyWriter.isWriteable / writeTo.
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
     * If {@code chosen} is a wildcard (e.g. {@literal *}{@literal /}{@literal *}
     * coming from an implicit Accept) and the entity type has a natural
     * Content-Type, substitute it. Otherwise respect the negotiated one.
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

    /** §3.7.2: among the candidates (same path+verb), choose the one whose
     *  @Consumes matches Content-Type AND whose @Produces matches Accept (maximum
     *  specificity). If none matches, return the first one (the Invoker will raise
     *  415 or 406 later). */
    /** Returns the highest qs among the route's @Produces. */
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

    MatchResult pickBestMatch(java.util.List<MatchResult> candidates, CassiniHttpExchange request) {
        if (candidates.size() == 1) return candidates.get(0);
        MediaType ct = MediaTypes.parse(request.firstHeader("Content-Type"));
        java.util.List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
        MatchResult best = null;
        double bestScore = -1;
        for (MatchResult c : candidates) {
            var cons = MediaTypes.fromSet(c.method().consumes());
            if (hasRequestBody(request) && !cons.isEmpty() && !MediaTypes.consumesMatches(ct, cons)) continue;
            var prod = MediaTypes.fromSet(c.method().produces());
            // §3.7.2: @Consumes specificity dominates @Produces (scaled ×10).
            // text/plain > text/* > */* > absence of @Consumes (if ct is present).
            double consScore = consumesSpecificity(ct, cons);
            double prodScore = 0;
            if (!prod.isEmpty()) {
                var pick = MediaTypes.pickProduced(accepts, prod);
                if (pick.isEmpty()) continue;
                // §3.7.2 / JAXRS:SPEC:25.11 + 26.8: order
                //   primary   = q-value of the MOST SPECIFIC matching Accept
                //               (cf. bestAcceptQuality)
                //   secondary = qs-value (source quality, server-side)
                //   tertiary  = @Produces specificity (tie-break)
                double acceptQ = bestAcceptQuality(accepts, prod);
                double qs = sourceQuality(prod);
                double spec = producesAnnotationSpecificity(accepts, prod);
                prodScore = acceptQ * 1_000_000 + qs * 1_000 + spec;
            }
            // §3.7.2: URI template specificity dominates first (literalChars
            // desc, totalCaptures desc, defaultCaptures asc), then @Consumes,
            // then @Produces. Scales: classPathLiterals (×1e8) > template
            // literalChars (×1e6) > totalCaptures (×1e3) > inverse defaultCaptures
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

    /** Specificity of the best-ranked @Produces matching an Accept, computed
     *  from the annotation (not from the type resolved after wildcard expansion). */
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

    /** q-value of the MOST SPECIFIC Accept matching an @Produces.
     *  §3.7.2 / §3.8: to resolve {@code clientImagePreferenceTest}
     *  (Accept "image/something;q=0.1, image/*;q=0.9" + @Produces "image/*"),
     *  we must keep the most precise matching Accept: for @Produces
     *  image/*, that is image/something (concrete > wildcard) → q=0.1, not
     *  the wildcard's q=0.9. This allows @Produces image/png (which only matches
     *  image/* with q=0.9) to win. */
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

    /** Returns the specificity of the most precise @Consumes that matches ct. */
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
