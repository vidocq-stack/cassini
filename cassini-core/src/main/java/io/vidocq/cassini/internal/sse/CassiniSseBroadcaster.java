package io.vidocq.cassini.internal.sse;

import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEventSink;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Implémentation Cassini de {@link SseBroadcaster}. In-memory, single-process.
 */
public final class CassiniSseBroadcaster implements SseBroadcaster {

    private final CopyOnWriteArrayList<SseEventSink> sinks = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<Consumer<SseEventSink>> closeListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<BiConsumer<SseEventSink, Throwable>> errorListeners = new CopyOnWriteArrayList<>();
    private volatile boolean closed = false;

    @Override
    public void register(SseEventSink subscriber) {
        if (closed) throw new IllegalStateException("Broadcaster closed");
        sinks.add(subscriber);
    }

    @Override
    public CompletionStage<?> broadcast(OutboundSseEvent event) {
        if (closed) return CompletableFuture.completedFuture(null);
        for (SseEventSink sink : sinks) {
            try {
                sink.send(event);
            } catch (RuntimeException e) {
                for (var l : errorListeners) l.accept(sink, e);
            }
            if (sink.isClosed()) {
                sinks.remove(sink);
                for (var l : closeListeners) l.accept(sink);
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void onClose(Consumer<SseEventSink> onClose) { closeListeners.add(onClose); }

    @Override
    public void onError(BiConsumer<SseEventSink, Throwable> onError) { errorListeners.add(onError); }

    @Override
    public void close() {
        closed = true;
        for (SseEventSink sink : sinks) {
            try { sink.close(); } catch (Exception ignored) {}
            for (var l : closeListeners) l.accept(sink);
        }
        sinks.clear();
    }

    @Override
    public void close(boolean cascading) {
        close();
    }
}
