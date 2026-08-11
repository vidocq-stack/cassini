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
package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Chappe → Cassini HTTP adapter.
 *
 * <p>For each HTTP request received by Chappe:</p>
 * <ol>
 *   <li>converts Chappe {@link Request}/{@link Response} to/from {@link io.vidocq.cassini.spi.http.CassiniHttpExchange};</li>
 *   <li>delegates dispatch to {@link CassiniHttpAdapter};</li>
 *   <li>copies the collected result from {@link ChappeHttpExchange} into the Chappe {@link Response}.</li>
 * </ol>
 */
public final class ChappeHttpAdapter implements Handler {

    private static final System.Logger LOG = System.getLogger(ChappeHttpAdapter.class.getName());

    /** Lifecycle hook: enters/leaves the CDI scope ({@code @RequestScoped}) if provided. */
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
     * Each request is handled on a dedicated virtual thread (M2h).
     * This guarantees: (1) isolation of request-scope ScopedValues,
     * (2) no platform-thread starvation if the resource method blocks
     * on I/O or waits for a CompletionStage.
     *
     * <p>M2i (SSE streaming): the caller does not wait for dispatch
     * completion unconditionally any more. A latch is released either by
     * {@link ChappeHttpExchange#openForStreaming} — in which case the chunked
     * {@code Body.streaming} response is returned immediately while the
     * resource method keeps writing events on its own virtual thread — or
     * when dispatch completes (buffered mode, previous behaviour).
     */
    @Override
    public Response handle(Request request) throws Exception {
        var exchange = new ChappeHttpExchange(request);
        var streamingReady = new java.util.concurrent.CountDownLatch(1);
        exchange.setStreamingLatch(streamingReady);
        var future = new CompletableFuture<Response>();

        Thread.ofVirtual().name("cassini-req").start(() -> {
            try {
                final Object[] error = {null};
                scoped.runInScope(() -> {
                    try {
                        engine.dispatch(exchange).toCompletableFuture().get();
                    } catch (Exception e) {
                        error[0] = e;
                    }
                });
                if (error[0] instanceof Exception ex) {
                    // Never let a request die silently: the transport answers a generic
                    // 500 from the failed future, so this log line is the only trace.
                    LOG.log(System.Logger.Level.ERROR,
                            "Request failed: " + exchange.method() + " " + exchange.routingPath(), ex);
                    future.completeExceptionally(ex);
                } else {
                    future.complete(buildChappeResponse(exchange));
                }
            } catch (Throwable t) {
                LOG.log(System.Logger.Level.ERROR,
                        "Request failed: " + exchange.method() + " " + exchange.routingPath(), t);
                future.completeExceptionally(t);
            } finally {
                // Buffered mode: unblock the caller once the response is ready.
                streamingReady.countDown();
            }
        });

        streamingReady.await();

        var streaming = exchange.streamingInfo();
        if (streaming != null) {
            // Streaming mode: headers/status snapshot taken at openForStreaming
            // time (the dispatch may still be mutating the exchange concurrently).
            var b = Response.builder().status(StatusCode.of(streaming.status()));
            streaming.headers().forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
            return b.body(Body.streaming(streaming.input())).build();
        }

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

    private static Response buildChappeResponse(ChappeHttpExchange exchange) {
        var b = Response.builder().status(StatusCode.of(exchange.collectedStatus()));
        exchange.collectedHeaders().forEach((k, vs) -> vs.forEach(v -> b.header(k, v)));
        byte[] body = exchange.collectedBody();
        return b.body(body.length > 0 ? Body.of(body) : Body.empty()).build();
    }
}
