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

import io.vidocq.cassini.internal.filter.CassiniRequestContext;
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.io.IOException;
import java.io.PipedInputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Central implementation of {@link CassiniHttpAdapter} — routes each request
 * via {@link UriRouter} then invokes the resource method via {@link Invoker}.
 *
 * <p>Handles pre-matching filters (§6.6.1): they run BEFORE routing
 * and may abort the request or modify the verb/URI.</p>
 *
 * <p>Writes the result to the provided {@link CassiniHttpExchange} (status, headers,
 * body) so that the transport (Chappe, JDK...) only needs to read the collected
 * values after dispatch.</p>
 *
 * <p>SSE streaming case: if the {@code cassini.streaming_pis} attribute is set
 * on the exchange after invocation, the transport is responsible for writing
 * the body (chunked streaming) — {@code DefaultCassiniHttpAdapter} does not
 * write the body.</p>
 */
public final class DefaultCassiniHttpAdapter implements CassiniHttpAdapter {

    private static final System.Logger LOG =
            System.getLogger(DefaultCassiniHttpAdapter.class.getName());

    private static final String HEAD = "HEAD";
    private static final String OPTIONS = "OPTIONS";
    private static final String ALLOW = "Allow";
    private static final String CONTENT_TYPE = "Content-Type";

    private final UriRouter router;
    private final Invoker invoker;
    /** The counters of cassini#42, or {@code null} when the stack was built without them. */
    private final RequestStatistics statistics;

    public DefaultCassiniHttpAdapter(UriRouter router, Invoker invoker) {
        this(router, invoker, null);
    }

    DefaultCassiniHttpAdapter(UriRouter router, Invoker invoker, RequestStatistics statistics) {
        this.router = router;
        this.invoker = invoker;
        this.statistics = statistics;
        this.mayStream = router.routes().stream().anyMatch(DefaultCassiniHttpAdapter::mayStream);
    }

    /** Whether some route may open a streaming response; resolved once, from the routes. */
    private final boolean mayStream;

    /**
     * §11.1: a resource method with an {@code SseEventSink} parameter streams its events; a
     * sub-resource locator resolved at run time (§3.4.1) may lead to one.
     */
    private static boolean mayStream(ResourceMethod route) {
        if (route.dynamicLocator()) return true;
        for (Class<?> type : route.javaMethod().getParameterTypes()) {
            if (type == jakarta.ws.rs.sse.SseEventSink.class) return true;
        }
        return false;
    }

    @Override
    public boolean mayStream() {
        return mayStream;
    }

    @Override
    public CompletionStage<Void> dispatch(CassiniHttpExchange exchange) {
        final RequestStatistics stats = statistics;
        final long start = stats == null ? 0L : stats.begin();
        try {
            // M2h: one request-scope binding per dispatch — all per-request
            // state (matched route, providers, base URI, SSE sink) lives in
            // the RequestScope instead of ThreadLocals.
            RequestScope.<Void, Exception>call(() -> {
                int status = dispatchInternal(exchange);
                // Counted in here, as an int: carrying the status out would box it on every request.
                // end() is the last statement and does not throw, so the catch below never counts it twice.
                if (stats != null) stats.end(start, status);
                return null;
            });
        } catch (Exception e) {
            if (stats != null) stats.end(start, 500);
            LOG.log(System.Logger.Level.ERROR, "Cassini dispatch error", e);
            try {
                byte[] body = (e.getMessage() == null ? "Internal Server Error" : e.getMessage())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.setStatus(500);
                exchange.responseHeaders().put(CONTENT_TYPE, List.of("text/plain;charset=utf-8"));
                exchange.writeResponseBody(body);
            } catch (IOException _) {
                // The transport cannot take the error either: it answers its own 500.
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    /** Routes and answers one request; returns the status written to the exchange. */
    private int dispatchInternal(CassiniHttpExchange exchange) throws Exception {
        String method = exchange.method();
        String verb = method == null ? "GET" : method;
        String path = exchange.routingPath();

        // §6.6.1: pre-matching filters run BEFORE routing → if one calls
        // abortWith(), return directly without attempting to match a route
        // (otherwise a non-existent path would 404 even if a filter would
        // have short-circuited).
        if (!invoker.filters().preMatching().isEmpty()) {
            Invoker.PreMatchResult preMatched = invoker.runPreMatching(exchange);
            CassiniHttpResponse aborted = preMatched.response();
            if (aborted != null) {
                applyResponse(aborted, exchange);
                return aborted.status();
            }
            // §6.6.1: if a pre-matching filter called setMethod /
            // setRequestUri, route the mutated values.
            CassiniRequestContext ctx = preMatched.ctx();
            if (ctx != null) {
                if (ctx.currentMethod() != null) verb = ctx.currentMethod();
                path = reroutedPath(ctx.currentRequestUri(), exchange.contextPath(), path);
            }
        }

        CassiniHttpResponse out = respond(verb, path, exchange);
        applyResponse(out, exchange);
        return out.status();
    }

    /**
     * §6.6.1: the routing path of a request URI that a pre-matching filter set, without the
     * context path; {@code path} when no filter set one.
     */
    private static String reroutedPath(java.net.URI requestUri, String contextPath, String path) {
        if (requestUri == null) return path;
        String rerouted = requestUri.getRawPath() != null ? requestUri.getRawPath() : requestUri.getPath();
        if (rerouted == null) return "/";
        if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)
                && rerouted.startsWith(contextPath)) {
            rerouted = rerouted.substring(contextPath.length());
            return rerouted.isEmpty() ? "/" : rerouted;
        }
        return rerouted;
    }

    private CassiniHttpResponse respond(String verb, String path, CassiniHttpExchange exchange) throws Exception {
        List<MatchResult> candidates = router.matchAll(verb, path);
        if (candidates.isEmpty()) return noRoute(verb, path);
        MatchResult first = candidates.get(0);
        CassiniHttpResponse out = invoker.invoke(candidates, exchange);
        // §3.3.5: HEAD invoked on a @GET method → the headers without the body.
        if (HEAD.equalsIgnoreCase(verb) && !HEAD.equalsIgnoreCase(first.method().httpMethod())) {
            return withoutBody(out);
        }
        return out;
    }

    /** No route for this verb and path: 404 when no verb has one, 405 otherwise (§3.7.2), through
     *  a WebApplicationException so that an ExceptionMapper can intercept it; OPTIONS answers
     *  200 with the Allow header (§3.3.5). */
    private CassiniHttpResponse noRoute(String verb, String path) throws IOException {
        List<String> allowed = router.methodsAllowedFor(path);
        if (allowed.isEmpty()) {
            return invoker.renderThrowable(
                    new jakarta.ws.rs.NotFoundException("No resource matches " + verb + " " + path));
        }
        if (!OPTIONS.equalsIgnoreCase(verb)) {
            var r405 = jakarta.ws.rs.core.Response.status(405)
                    .header(ALLOW, String.join(", ", allowed)).build();
            return invoker.renderThrowable(new jakarta.ws.rs.WebApplicationException(r405));
        }
        if (!allowed.contains(OPTIONS)) allowed.add(OPTIONS);
        if (allowed.contains("GET") && !allowed.contains(HEAD)) allowed.add(HEAD);
        return CassiniHttpResponse.builder()
                .status(200)
                .header(ALLOW, String.join(", ", allowed))
                .header(CONTENT_TYPE, "application/vnd.sun.wadl+xml")
                .body(new byte[0])
                .build();
    }

    private static CassiniHttpResponse withoutBody(CassiniHttpResponse out) {
        var b = CassiniHttpResponse.builder().status(out.status()).body(new byte[0]);
        for (var e : out.headers().entrySet()) {
            for (String v : e.getValue()) b.header(e.getKey(), v);
        }
        return b.build();
    }

    private static void applyResponse(CassiniHttpResponse out, CassiniHttpExchange exchange)
            throws IOException {
        exchange.setStatus(out.status());
        // Handed over: the response is not used after this, so a transport may keep the map.
        exchange.putResponseHeaders(out.headers());

        // SSE streaming: if the transport has already sent headers+body in streaming mode,
        // the cassini.streaming_pis attribute is set — do not overwrite the body.
        PipedInputStream streamingPis =
                (PipedInputStream) exchange.getAttribute("cassini.streaming_pis");
        if (streamingPis != null) return;

        int length = out.bodyLength();
        if (length > 0) {
            // In one call, the entity buffer as it is: the transport may keep it rather than copy it.
            exchange.writeResponseBody(out.bodyArray(), 0, length);
        }
    }
}
