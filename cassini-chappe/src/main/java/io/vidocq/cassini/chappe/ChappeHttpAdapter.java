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

import java.io.PipedInputStream;
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

        // SSE streaming: Body.streaming(pis)
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
