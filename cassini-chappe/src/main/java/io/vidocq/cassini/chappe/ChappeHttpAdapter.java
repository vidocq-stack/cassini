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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

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
    /** Source of Chappe response builders — {@link Response#builder()} outside tests. */
    private final Supplier<Response.Builder> builders;

    public ChappeHttpAdapter(CassiniHttpAdapter engine) {
        this(engine, Scoped.IDENTITY);
    }

    public ChappeHttpAdapter(CassiniHttpAdapter engine, Scoped scoped) {
        this(engine, scoped, Response::builder);
    }

    /** Test seam: lets a test make the Chappe response build fail on any Chappe version. */
    ChappeHttpAdapter(CassiniHttpAdapter engine, Scoped scoped, Supplier<Response.Builder> builders) {
        this.engine = engine;
        this.scoped = scoped == null ? Scoped.IDENTITY : scoped;
        this.builders = builders;
        this.inline = !engine.mayStream() && this.scoped != Scoped.IDENTITY;
    }

    /** Whether requests are dispatched on the calling thread, see {@link #handleHere}. */
    private final boolean inline;

    /**
     * When the engine may stream ({@link CassiniHttpAdapter#mayStream}) or no {@link Scoped} hook
     * bounds the request scope, each request is handled on a dedicated virtual thread (M2h);
     * otherwise on the thread Chappe called the handler on, itself a virtual thread, so blocking
     * I/O or a wait on a CompletionStage starves no platform thread either way, and the
     * request-scope ScopedValues end with the dispatch.
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
        if (inline) {
            Exception failure = dispatchHere(exchange);
            if (failure != null) throw failure;
            return buildChappeResponse(exchange);
        }
        CompletableFuture<Response> response = dispatchOnItsOwnThread(exchange);
        var streaming = exchange.streamingInfo();
        if (streaming != null) return streamingResponse(exchange, streaming);
        try {
            return response.get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new IllegalStateException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for virtual thread", ie);
        }
    }

    /**
     * The dispatch of an engine that never streams, on the thread that received the request: no
     * thread to start, no hand-off to wait for. Only with a {@link Scoped} hook, which enters and
     * leaves the request scope around the call: without one, a CDI container may keep the request
     * context on the thread (a {@code RequestContextController} activated by a request filter), and
     * the thread started per request is what discards it. A failure ends the request as on the
     * dispatch thread: logged, then returned for {@link #handle} to throw to Chappe, which answers
     * 500.
     *
     * @return the failure of the dispatch, or null when it succeeded
     */
    private Exception dispatchHere(ChappeHttpExchange exchange) {
        try {
            scoped.runInScope(() -> engine.dispatch(exchange).toCompletableFuture().join());
            return null;
        } catch (RuntimeException e) {
            Throwable failure = e instanceof CompletionException ce && ce.getCause() != null ? ce.getCause() : e;
            logFailure(exchange, failure);
            return failure instanceof Exception ex ? ex : e;
        }
    }

    /**
     * Starts the dispatch on a virtual thread of its own and waits until it either opened a
     * stream ({@link ChappeHttpExchange#openForStreaming}) or ended.
     *
     * @return the buffered response, completed when the dispatch ends
     */
    private CompletableFuture<Response> dispatchOnItsOwnThread(ChappeHttpExchange exchange) throws InterruptedException {
        var streamingReady = new java.util.concurrent.CountDownLatch(1);
        exchange.setStreamingLatch(streamingReady);
        var future = new CompletableFuture<Response>();
        Thread.ofVirtual().name("cassini-req").start(() -> dispatchInto(future, exchange, streamingReady));
        streamingReady.await();
        return future;
    }

    private void dispatchInto(CompletableFuture<Response> future, ChappeHttpExchange exchange,
                              java.util.concurrent.CountDownLatch streamingReady) {
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
                logFailure(exchange, ex);
                future.completeExceptionally(ex);
            } else if (exchange.streamingInfo() != null) {
                // Streaming mode: the handler thread already answered from the
                // streaming snapshot; building a buffered response here is useless
                // and would log a second diagnostic if that build failed too.
                future.complete(null);
            } else {
                future.complete(buildChappeResponse(exchange));
            }
        } catch (Throwable t) {
            logFailure(exchange, t);
            future.completeExceptionally(t);
        } finally {
            // Buffered mode: unblock the caller once the response is ready.
            streamingReady.countDown();
        }
    }

    /** Streaming mode: headers/status snapshot taken at openForStreaming time (the dispatch may
     *  still be mutating the exchange concurrently). */
    private Response streamingResponse(ChappeHttpExchange exchange, ChappeHttpExchange.StreamingInfo streaming) {
        try {
            var b = builders.get().status(StatusCode.of(streaming.status()));
            copyHeaders(b, streaming.headers());
            return b.body(Body.streaming(streaming.input())).build();
        } catch (RuntimeException e) {
            // cassini#56: Chappe never received the pipe, so nobody would ever read
            // or close it and the thread writing the entity would block forever.
            // Closing it makes the writer's next write fail with an IOException.
            closeQuietly(streaming.input());
            return failedResponse(exchange, e);
        }
    }

    /** Never let a request die silently: Chappe answers a generic 500, so this line is the only trace. */
    private static void logFailure(ChappeHttpExchange exchange, Throwable failure) {
        LOG.log(System.Logger.Level.ERROR,
                "Request failed: " + exchange.method() + " " + exchange.routingPath(), failure);
    }

    private Response buildChappeResponse(ChappeHttpExchange exchange) {
        try {
            var b = builders.get().status(StatusCode.of(exchange.collectedStatus()));
            copyHeaders(b, exchange.collectedHeaders());
            byte[] body = exchange.collectedBody();
            return b.body(body.length > 0 ? Body.of(body) : Body.empty()).build();
        } catch (RuntimeException e) {
            return failedResponse(exchange, e);
        }
    }

    /** Copies the JAX-RS headers, turning a Chappe refusal into a {@link RejectedHeader}. */
    private static void copyHeaders(Response.Builder b, Map<String, List<String>> headers) {
        headers.forEach((name, values) -> values.forEach(value -> {
            try {
                b.header(name, value);
            } catch (RuntimeException e) {
                throw new RejectedHeader(name, e);
            }
        }));
    }

    /**
     * Logs why the Chappe response could not be built and answers a bare 500. A rejected
     * header is named, but neither its value nor the transport's exception message is ever
     * logged: the value may carry a secret or an injection attempt, and a transport may
     * build its message from it. Only the exception class (and its stack frames) is logged.
     */
    private static Response failedResponse(ChappeHttpExchange exchange, RuntimeException e) {
        String request = exchange.method() + " " + printable(exchange.routingPath());
        if (e instanceof RejectedHeader rejected) {
            LOG.log(System.Logger.Level.ERROR, "Response header " + printable(rejected.name)
                    + " of " + request + " rejected by the HTTP transport (" + rejected.causeType
                    + "); answering 500 instead. The value is not logged. A header name must be"
                    + " an RFC 9110 token, a value ISO-8859-1 text without CR, LF, NUL or other"
                    + " control characters, and not null. Encode non-Latin-1 text per RFC 8187,"
                    + " e.g. Content-Disposition: attachment; filename*=UTF-8''%E2%82%AC.pdf");
        } else {
            // Only the class: a transport that validates headers in build() may put a
            // value in its message. The stack trace goes through a message-less copy.
            var trace = new RuntimeException(e.getClass().getName() + " (message not logged)");
            trace.setStackTrace(e.getStackTrace());
            LOG.log(System.Logger.Level.ERROR, "Response of " + request
                    + " could not be built by the HTTP transport (" + e.getClass().getName()
                    + "); answering 500 instead. The exception message is not logged.", trace);
        }
        return Response.builder().status(StatusCode.of(500)).body(Body.empty()).build();
    }

    private static void closeQuietly(InputStream in) {
        try {
            in.close();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: the response is already lost.
        }
    }

    /** {@code s} quoted, with every char outside printable ASCII escaped as {@code \}{@code uXXXX}. */
    private static String printable(String s) {
        if (s == null) return "null";
        var sb = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x20 && c <= 0x7E) sb.append(c);
            else sb.append(String.format("\\u%04X", (int) c));
        }
        return sb.append('"').toString();
    }

    /** A response header the transport refused. Carries the name only, never the value. */
    private static final class RejectedHeader extends RuntimeException {
        final String name;
        final String causeType;

        RejectedHeader(String name, RuntimeException cause) {
            super(null, null, false, false);
            this.name = name;
            this.causeType = cause.getClass().getName();
        }
    }
}
