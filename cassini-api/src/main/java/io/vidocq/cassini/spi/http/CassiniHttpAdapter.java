package io.vidocq.cassini.spi.http;

import java.util.concurrent.CompletionStage;

/**
 * Server entry point for an HTTP transport that drives Cassini.
 *
 * <p>The adapter (Chappe, JDK HttpServer, ...) delegates each incoming request to the
 * Cassini runtime by calling {@link #dispatch(CassiniHttpExchange)}. The return value
 * is a {@link CompletionStage} to allow correct propagation of processing completion
 * (M2h: non-blocking, virtual threads, async @Suspended).
 *
 * <p>Until M2h, the current implementation may return an already-completed stage —
 * the signature remains async to avoid breaking the contract later.
 */
public interface CassiniHttpAdapter {

    /**
     * Processes the incoming request and writes the response.
     *
     * @return a stage that completes when the response body is fully written
     *         (or when the connection is closed on error)
     */
    CompletionStage<Void> dispatch(CassiniHttpExchange exchange);
}
