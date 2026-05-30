package io.vidocq.cassini.spi.http;

import java.util.concurrent.CompletionStage;

/**
 * Server-side entry point of an HTTP transport driving Cassini.
 *
 * <p>The adapter (Chappe, JDK HttpServer, ...) delegates each incoming request
 * to the Cassini runtime by calling {@link #dispatch(CassiniHttpExchange)}. The
 * return value is a {@link CompletionStage} so the end of processing can be
 * propagated correctly (M2h: non-blocking, virtual threads, async @Suspended).
 *
 * <p>Until M2h lands, the current implementation may return an already-completed
 * stage — the signature stays async so the contract does not break later.
 */
public interface CassiniHttpAdapter {

    /**
     * Handles the incoming request and writes the response.
     *
     * @return a stage that completes once the response body is fully written
     *         (or the connection is closed on error)
     */
    CompletionStage<Void> dispatch(CassiniHttpExchange exchange);
}
