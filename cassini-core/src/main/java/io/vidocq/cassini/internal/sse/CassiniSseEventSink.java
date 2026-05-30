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
        if (closed) return CompletableFuture.completedFuture(null);
        try {
            byte[] chunk = serializeEvent(event);
            if (streamingSink != null && streamingSink.isOpen()) {
                return streamingSink.writeChunk(chunk).thenCompose(__ -> streamingSink.flush());
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
