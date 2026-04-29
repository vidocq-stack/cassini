package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;

import java.io.PipedInputStream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Adapter HTTP Chappe → Cassini.
 *
 * <p>Pour chaque requête HTTP reçue par Chappe :</p>
 * <ol>
 *   <li>convertit {@link Request}/{@link Response} Chappe ↔ {@link io.vidocq.cassini.spi.http.CassiniHttpExchange} ;</li>
 *   <li>délègue le dispatch à {@link CassiniHttpAdapter} ;</li>
 *   <li>copie le résultat collecté depuis {@link ChappeHttpExchange} dans la {@link Response} Chappe.</li>
 * </ol>
 */
public final class ChappeHttpAdapter implements Handler {

    private static final System.Logger LOG = System.getLogger(ChappeHttpAdapter.class.getName());

    /** Hook lifecycle : entre/sort du scope CDI ({@code @RequestScoped}) si fourni. */
    @FunctionalInterface
    public interface Scoped {
        void runInScope(Runnable action);
        Scoped IDENTITY = Runnable::run;
    }

    private final CassiniHttpAdapter engine;
    private final Scoped scoped;

    public ChappeHttpAdapter(CassiniHttpAdapter engine) {
        this(engine, Scoped.IDENTITY);
    }

    public ChappeHttpAdapter(CassiniHttpAdapter engine, Scoped scoped) {
        this.engine = engine;
        this.scoped = scoped == null ? Scoped.IDENTITY : scoped;
    }

    /**
     * Chaque requête est traitée sur un virtual thread dédié (M2h).
     * Cela garantit : (1) isolation des ScopedValues request-scope,
     * (2) aucun starvation de platform thread si la resource method bloque
     * sur I/O ou attend un CompletionStage.
     */
    @Override
    public Response handle(Request request) throws Exception {
        var future = new CompletableFuture<Response>();
        Thread.ofVirtual().name("cassini-req").start(() -> {
            try {
                future.complete(dispatchOnCurrentThread(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for virtual thread", ie);
        }
    }

    private Response dispatchOnCurrentThread(Request request) throws Exception {
        ChappeHttpExchange exchange = new ChappeHttpExchange(request);

        final Object[] error = {null};
        scoped.runInScope(() -> {
            try {
                engine.dispatch(exchange).toCompletableFuture().get();
            } catch (Exception e) {
                error[0] = e;
            }
        });
        if (error[0] instanceof Exception ex) throw ex;

        // Streaming SSE : Body.streaming(pis)
        PipedInputStream streamingPis =
                (PipedInputStream) exchange.getAttribute("cassini.streaming_pis");
        if (streamingPis != null) {
            var b = Response.builder().status(StatusCode.of(exchange.collectedStatus()));
            exchange.collectedHeaders().forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
            return b.body(Body.streaming(streamingPis)).build();
        }

        return buildChappeResponse(exchange);
    }

    private static Response buildChappeResponse(ChappeHttpExchange exchange) {
        var b = Response.builder().status(StatusCode.of(exchange.collectedStatus()));
        exchange.collectedHeaders().forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
        byte[] body = exchange.collectedBody();
        return b.body(body.length > 0 ? Body.of(body) : Body.empty()).build();
    }
}
