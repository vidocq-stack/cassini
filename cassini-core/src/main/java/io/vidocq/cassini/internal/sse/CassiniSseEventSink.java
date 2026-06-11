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
package io.vidocq.cassini.internal.sse;

import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.spi.http.CassiniStreamingSink;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseEventSink;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cassini implementation of {@link SseEventSink}: buffers SSE events
 * then exposes the serialized content via {@link #toByteArray()}.
 *
 * <p>Format SSE (RFC §11.1.5) :</p>
 * <pre>
 * id: &lt;id&gt;
 * event: &lt;name&gt;
 * retry: &lt;ms&gt;
 * data: &lt;line1&gt;
 * data: &lt;line2&gt;
 *
 * </pre>
 */
public final class CassiniSseEventSink implements SseEventSink {

    private final MessageBodyRegistry registry;
    /** Mode bufferisé (Chappe ou transport sans streaming). */
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    /** Mode streaming (JDK ou transport chunked-capable), {@code null} = mode bufferisé. */
    private final CassiniStreamingSink streamingSink;
    private volatile boolean closed = false;
    private final java.util.concurrent.CompletableFuture<Void> closeFuture =
            new java.util.concurrent.CompletableFuture<>();

    public CassiniSseEventSink(MessageBodyRegistry registry) {
        this(registry, null);
    }

    public CassiniSseEventSink(MessageBodyRegistry registry, CassiniStreamingSink streamingSink) {
        this.registry = registry;
        this.streamingSink = streamingSink;
    }

    @Override
    public boolean isClosed() { return closed; }

    @SuppressWarnings({"rawtypes", "unchecked"})
    @Override
    public CompletionStage<?> send(OutboundSseEvent event) {
        // §11: invoking send on a closed sink throws IllegalStateException
        // (TCK sseeventsink#closeTest).
        if (closed) throw new IllegalStateException("SseEventSink is closed");
        // M2i: the transport write end died (client disconnected) — flip to
        // closed so server-side loops polling isClosed() terminate
        // (TCK sseeventsource#closeTest), and report the failure on the stage.
        if (streamingSink != null && !streamingSink.isOpen()) {
            closed = true;
            return CompletableFuture.failedFuture(
                    new IOException("SSE connection closed by the client"));
        }
        try {
            byte[] chunk = serializeEvent(event);
            if (streamingSink != null) {
                return streamingSink.writeChunk(chunk)
                        .thenCompose(__ -> streamingSink.flush())
                        .whenComplete((r, t) -> { if (t != null) closed = true; });
            }
            buffer.write(chunk);
        } catch (IOException e) {
            CompletableFuture<Object> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
        return CompletableFuture.completedFuture(null);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private byte[] serializeEvent(OutboundSseEvent event) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        StringBuilder header = new StringBuilder();
        if (event.getComment() != null) {
            for (String line : event.getComment().split("\n", -1)) {
                header.append(": ").append(line).append('\n');
            }
        }
        if (event.getId() != null) header.append("id: ").append(event.getId()).append('\n');
        if (event.getName() != null) header.append("event: ").append(event.getName()).append('\n');
        if (event.isReconnectDelaySet()) {
            header.append("retry: ").append(event.getReconnectDelay()).append('\n');
        }
        out.write(header.toString().getBytes(StandardCharsets.UTF_8));

        Object data = event.getData();
        if (data != null) {
            Class<?> type = event.getType() != null ? event.getType() : data.getClass();
            java.lang.reflect.Type gt = event.getGenericType() != null ? event.getGenericType() : type;
            MediaType mt = event.getMediaType();
            MessageBodyWriter w = registry.findWriter(type, gt, new Annotation[0], mt)
                    .orElseThrow(() -> new IllegalStateException(
                            "No MessageBodyWriter for SSE event type=" + type + " mt=" + mt));
            ByteArrayOutputStream tmp = new ByteArrayOutputStream();
            MultivaluedMap<String, Object> hdrs = new MultivaluedHashMap<>();
            w.writeTo(data, type, gt, new Annotation[0], mt, hdrs, tmp);
            String serialized = new String(tmp.toByteArray(), StandardCharsets.UTF_8);
            for (String line : serialized.split("\n", -1)) {
                out.write(("data: " + line + "\n").getBytes(StandardCharsets.UTF_8));
            }
        }
        out.write('\n');
        return out.toByteArray();
    }

    @Override
    public void close() throws IOException {
        closed = true;
        if (streamingSink != null) {
            streamingSink.close();
        }
        closeFuture.complete(null);
    }

    /** True if this sink pushes events directly to the wire (no buffering). */
    public boolean isStreaming() { return streamingSink != null; }

    /**
     * M2i: forces the lazy streaming commit (transport-side) when the resource
     * method returns normally without having produced any event yet — the
     * broadcaster pattern: register the sink, return, events come later from
     * another thread. A flush on the transport sink commits the chunked
     * response so the connection stays open for those future events.
     */
    public void commitStreaming() {
        if (streamingSink != null && !closed && streamingSink.isOpen()) {
            streamingSink.flush();
        }
    }

    /**
     * Blocks the current virtual thread until {@link #close()} is called.
     * No-op if the sink is already closed. Allows SSE resource methods to close
     * the sink asynchronously (from a background thread).
     */
    public void awaitClose() {
        if (closed) return;
        try { closeFuture.get(); }
        catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        catch (java.util.concurrent.ExecutionException ignored) {}
    }

    public byte[] toByteArray() { return buffer.toByteArray(); }
}
