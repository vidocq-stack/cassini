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
 * Central {@link CassiniHttpAdapter} implementation — routes each request via
 * {@link UriRouter} then invokes the resource method via {@link Invoker}.
 *
 * <p>Handles pre-matching filters (§6.6.1): they run BEFORE routing and can
 * abort the request or modify verb/URI.</p>
 *
 * <p>Writes the result to the provided {@link CassiniHttpExchange} (status,
 * headers, body) so the transport (Chappe, JDK...) just needs to read the
 * values collected after dispatch.</p>
 *
 * <p>SSE streaming case: if the {@code cassini.streaming_pis} attribute is set
 * on the exchange after invocation, the transport is responsible for writing
 * the body (chunked streaming) — {@code DefaultCassiniHttpAdapter} does not
 * write the body.</p>
 */
public final class DefaultCassiniHttpAdapter implements CassiniHttpAdapter {

    private static final System.Logger LOG =
            System.getLogger(DefaultCassiniHttpAdapter.class.getName());

    private final UriRouter router;
    private final Invoker invoker;

    public DefaultCassiniHttpAdapter(UriRouter router, Invoker invoker) {
        this.router = router;
        this.invoker = invoker;
    }

    @Override
    public CompletionStage<Void> dispatch(CassiniHttpExchange exchange) {
        try {
            dispatchInternal(exchange);
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Cassini dispatch error", e);
            try {
                byte[] body = (e.getMessage() == null ? "Internal Server Error" : e.getMessage())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.setStatus(500);
                exchange.responseHeaders().put("Content-Type",
                        java.util.List.of("text/plain;charset=utf-8"));
                exchange.responseBody().write(body);
            } catch (IOException ignored) {}
        }
        return CompletableFuture.completedFuture(null);
    }

    private void dispatchInternal(CassiniHttpExchange exchange) throws Exception {
        String verb = exchange.method();
        if (verb == null) verb = "GET";

        String path = exchange.routingPath();

        // §6.6.1: pre-matching filters run BEFORE routing → if one of them
        // abortWith(), return directly without attempting to match a route
        // (otherwise /nonexistent-path would 404 even if a filter would have
        // short-circuited it).
        var preMatchFilters = invoker.filters().preMatching();
        if (!preMatchFilters.isEmpty()) {
            Object[] holderPre = new Object[1];
            try {
                holderPre[0] = invoker.runPreMatching(exchange);
            } catch (Exception e) {
                holderPre[0] = e;
            }
            if (holderPre[0] instanceof Exception ex) throw ex;
            if (holderPre[0] instanceof Invoker.PreMatchResult pmr) {
                if (pmr.response() != null) {
                    applyResponse(pmr.response(), exchange);
                    return;
                }
                // §6.6.1: if a pre-matching filter called setMethod /
                // setRequestUri, re-run routing on the mutated values.
                if (pmr.ctx() != null) {
                    CassiniRequestContext ctx = pmr.ctx();
                    String mutMethod = ctx.currentMethod();
                    if (mutMethod != null) verb = mutMethod;
                    java.net.URI mutUri = ctx.currentRequestUri();
                    if (mutUri != null) {
                        String mutPath = mutUri.getRawPath();
                        if (mutPath == null) mutPath = mutUri.getPath();
                        if (mutPath == null) mutPath = "/";
                        // Strip contextPath from the mutated absolute URI (§6.6.1 setRequestUri)
                        String ctxPath = exchange.contextPath();
                        if (ctxPath != null && !ctxPath.isEmpty() && !"/".equals(ctxPath)
                                && mutPath.startsWith(ctxPath)) {
                            mutPath = mutPath.substring(ctxPath.length());
                            if (mutPath.isEmpty()) mutPath = "/";
                        }
                        path = mutPath;
                    }
                }
            }
        }

        List<MatchResult> candidates = router.matchAll(verb, path);

        CassiniHttpResponse out;
        if (candidates.isEmpty()) {
            List<String> allowed = router.methodsAllowedFor(path);
            if (!allowed.isEmpty()) {
                // §3.3.5: OPTIONS without explicit handler → 200 + Allow header
                if ("OPTIONS".equalsIgnoreCase(verb)) {
                    if (!allowed.contains("OPTIONS")) allowed.add("OPTIONS");
                    if (allowed.contains("GET") && !allowed.contains("HEAD")) allowed.add("HEAD");
                    out = CassiniHttpResponse.builder()
                            .status(200)
                            .header("Allow", String.join(", ", allowed))
                            .header("Content-Type", "application/vnd.sun.wadl+xml")
                            .body(new byte[0])
                            .build();
                } else {
                    // §3.7.2: 405 via WebApplicationException so the ExceptionMapper can intercept
                    var r405 = jakarta.ws.rs.core.Response.status(405)
                            .header("Allow", String.join(", ", allowed)).build();
                    out = invoker.renderThrowable(
                            new jakarta.ws.rs.WebApplicationException(r405), exchange);
                }
            } else {
                out = invoker.renderThrowable(
                        new jakarta.ws.rs.NotFoundException("No resource matches " + verb + " " + path),
                        exchange);
            }
        } else {
            MatchResult result = candidates.get(0);
            out = invoker.invoke(candidates, exchange);
            // §3.3.5: HEAD invoked on a @GET method → return headers without body
            if ("HEAD".equalsIgnoreCase(verb) && !"HEAD".equalsIgnoreCase(result.method().httpMethod())) {
                var b = CassiniHttpResponse.builder().status(out.status()).body(new byte[0]);
                for (var e : out.headers().entrySet())
                    for (String v : e.getValue()) b.header(e.getKey(), v);
                out = b.build();
            }
        }

        applyResponse(out, exchange);
    }

    private static void applyResponse(CassiniHttpResponse out, CassiniHttpExchange exchange)
            throws IOException {
        exchange.setStatus(out.status());
        exchange.responseHeaders().putAll(out.headers());

        // SSE streaming: if the transport has already sent headers+body in streaming mode,
        // the cassini.streaming_pis attribute is set — do not rewrite the body.
        PipedInputStream streamingPis =
                (PipedInputStream) exchange.getAttribute("cassini.streaming_pis");
        if (streamingPis != null) return;

        byte[] body = out.body();
        if (body != null && body.length > 0) {
            exchange.responseBody().write(body);
        }
    }
}
