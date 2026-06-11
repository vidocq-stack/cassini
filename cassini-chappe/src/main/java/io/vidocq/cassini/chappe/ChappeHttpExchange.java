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

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chappe → {@link CassiniHttpExchange} adapter.
 *
 * <p>Reads lazily from {@link Request} and collects status/headers/body in memory;
 * the final Chappe {@link Response} is built by {@link ChappeHttpAdapter}.
 */
public final class ChappeHttpExchange implements CassiniHttpExchange {

    private final Request request;
    private final String contextPath;
    private int responseStatus = 200;
    private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private final Map<String, Object> attributes = new java.util.HashMap<>();

    /** Immutable snapshot taken by {@link #openForStreaming} (M2i) — read by the
     *  adapter to build the chunked Chappe response while the dispatch keeps
     *  running concurrently on its own virtual thread. */
    public record StreamingInfo(int status, Map<String, List<String>> headers,
                                InputStream input) {}

    /**
     * Thread-agnostic chunk stream backing SSE streaming (M2i). Deliberately
     * NOT a {@code PipedInputStream}: piped streams track the liveness of the
     * last writer <em>thread</em> and throw {@code "Pipe broken"} once it dies —
     * which is exactly the SSE broadcaster pattern (the registering request's
     * virtual thread ends, later events come from other threads). A bounded
     * queue gives natural backpressure and chunk-atomic writes instead.
     */
    static final class ChunkQueueInputStream extends InputStream {
        private static final byte[] EOF = new byte[0];
        private final java.util.concurrent.BlockingQueue<byte[]> queue =
                new java.util.concurrent.LinkedBlockingQueue<>(64);
        private volatile boolean closed;
        private byte[] current = new byte[0];
        private int pos;

        /** Producer side — any thread. Blocks (bounded queue) for backpressure. */
        void put(byte[] chunk) throws IOException {
            try {
                while (!queue.offer(chunk, 50, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    if (closed) throw new IOException("Streaming consumer closed");
                }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while streaming", ie);
            }
            if (closed) throw new IOException("Streaming consumer closed");
        }

        /** Producer side — signals normal end of stream. */
        void endOfStream() {
            queue.offer(EOF); // capacity slack: consumer drains; worst case offer fails and closed kicks in
        }

        private boolean ensureCurrent() throws IOException {
            while (pos >= current.length) {
                if (current == EOF) return false;
                try {
                    byte[] next = queue.poll(50, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (next == null) {
                        if (closed) return false;
                        continue;
                    }
                    current = next;
                    pos = 0;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while reading stream", ie);
                }
            }
            return true;
        }

        @Override public int read() throws IOException {
            if (!ensureCurrent()) return -1;
            return current[pos++] & 0xFF;
        }

        @Override public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!ensureCurrent()) return -1;
            int n = Math.min(len, current.length - pos);
            System.arraycopy(current, pos, b, off, n);
            pos += n;
            return n;
        }

        @Override public int available() {
            int n = current.length - pos;
            for (byte[] c : queue) n += c.length;
            return Math.max(n, 0);
        }

        /** Consumer side — the transport stops reading (client gone). */
        @Override public void close() {
            closed = true;
            queue.clear();
            queue.offer(EOF);
        }

        boolean isConsumerClosed() { return closed; }
    }

    private volatile StreamingInfo streamingInfo;
    private java.util.concurrent.CountDownLatch streamingLatch;

    /** Set by {@code ChappeHttpAdapter} before dispatch; released either by
     *  {@link #openForStreaming} (streaming mode) or when dispatch completes. */
    public void setStreamingLatch(java.util.concurrent.CountDownLatch latch) {
        this.streamingLatch = latch;
    }

    /** @return the streaming snapshot, or {@code null} if the response is buffered. */
    public StreamingInfo streamingInfo() {
        return streamingInfo;
    }

    public ChappeHttpExchange(Request request) {
        this(request, request.contextPath());
    }

    public ChappeHttpExchange(Request request, String contextPath) {
        this.request = request;
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    public Request chappeRequest() { return request; }
    public int collectedStatus() { return responseStatus; }
    public byte[] collectedBody() { return responseBody.toByteArray(); }
    public Map<String, List<String>> collectedHeaders() { return responseHeaders; }

    @Override public String method() {
        return request.method() == null ? "GET" : request.method().toString();
    }

    @Override public URI requestUri() { return request.uri(); }

    @Override public String requestUriRaw() {
        URI u = request.uri();
        return u == null ? request.path() : u.toString();
    }

    @Override public Map<String, List<String>> requestHeaders() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (var e : request.headers()) {
            m.computeIfAbsent(e.name(), _ -> new ArrayList<>()).add(e.value());
        }
        return m;
    }

    @Override public InputStream requestBody() {
        var body = request.body();
        return body == null ? InputStream.nullInputStream() : body.asInputStream();
    }

    @Override public long contentLength() {
        var body = request.body();
        return body == null ? -1L : body.contentLength();
    }

    @Override public String contextPath() { return contextPath; }

    @Override public String routingPath() {
        // Chappe provides pathInfo(), which is already stripped of the contextPath.
        String pi = request.pathInfo();
        if (pi == null || pi.isEmpty()) return "/";
        return pi;
    }

    @Override public void setStatus(int code) { this.responseStatus = code; }

    @Override public Map<String, List<String>> responseHeaders() { return responseHeaders; }

    @Override public OutputStream responseBody() { return responseBody; }

    @Override public SocketAddress remoteAddress() { return null; }

    @Override public boolean isSecure() { return request.isSecure(); }

    @Override public String authScheme() { return null; }

    @Override public Principal userPrincipal() { return null; }

    @Override public boolean isUserInRole(String role) { return false; }

    /**
     * M2i: opens chunked SSE streaming. Creates a pipe whose read end is handed
     * to Chappe ({@code Body.streaming}) by the adapter as soon as the latch is
     * released, while the resource method keeps writing events into the write
     * end from its own virtual thread — the 8 KB pipe buffer provides natural
     * backpressure. See ASYNC.md (« SSE streaming with Chappe »).
     *
     * <p>The streaming response is committed <em>lazily</em>, on the first
     * write or flush — never at creation. The Invoker opens the sink before
     * invoking the resource method; if the method throws before producing any
     * event (e.g. the 503 + Retry-After throttling pattern of the TCK's
     * {@code ServiceUnavailableResource}), the error response must still reach
     * the client through the regular buffered path. A {@code close()} without
     * prior write therefore does <em>not</em> commit either.
     */
    @Override
    public io.vidocq.cassini.spi.http.CassiniStreamingSink openForStreaming(
            int status, Map<String, List<String>> headers) {
        final ChunkQueueInputStream stream = new ChunkQueueInputStream();
        var snapshot = new LinkedHashMap<String, List<String>>();
        headers.forEach((k, vs) -> snapshot.put(k, List.copyOf(vs)));
        final StreamingInfo pending = new StreamingInfo(status, snapshot, stream);
        return new io.vidocq.cassini.spi.http.CassiniStreamingSink() {
            private volatile boolean open = true;
            private volatile boolean committed = false;

            private void commit() {
                if (!committed) {
                    committed = true;
                    streamingInfo = pending;
                    if (streamingLatch != null) streamingLatch.countDown();
                }
            }

            @Override public java.util.concurrent.CompletionStage<Void> writeChunk(byte[] data) {
                try {
                    commit();
                    stream.put(data);
                    return java.util.concurrent.CompletableFuture.completedFuture(null);
                } catch (IOException e) {
                    open = false;
                    return java.util.concurrent.CompletableFuture.failedFuture(e);
                }
            }
            @Override public java.util.concurrent.CompletionStage<Void> flush() {
                commit();
                if (stream.isConsumerClosed()) {
                    open = false;
                    return java.util.concurrent.CompletableFuture.failedFuture(
                            new IOException("Streaming consumer closed"));
                }
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public java.util.concurrent.CompletionStage<Void> close() {
                open = false;
                // No commit here: closing an uncommitted sink means no event was
                // ever produced — the response goes through the buffered path
                // (typically an exception mapped by the Invoker).
                if (committed) stream.endOfStream();
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
            @Override public boolean isOpen() { return open && !stream.isConsumerClosed(); }
        };
    }

    @Override public void setAttribute(String key, Object value) { attributes.put(key, value); }

    /**
     * Reads first from the exchange-local store, then falls back to the
     * Chappe per-request attributes. The fallback is what lets an upstream
     * Chappe {@code Handler} (auth bridge, servlet glue) hand request state
     * to Cassini across the per-request virtual-thread boundary (M2h) —
     * e.g. {@code cassini.auth} set before dispatch.
     */
    @Override public Object getAttribute(String key) {
        Object v = attributes.get(key);
        return v != null ? v : request.attribute(key);
    }
}
