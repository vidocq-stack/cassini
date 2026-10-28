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
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniStreamingSink;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.sse.OutboundSseEvent;
import jakarta.ws.rs.sse.SseBroadcaster;
import jakarta.ws.rs.sse.SseEvent;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §9.x / §11 server-sent events: the event builder, the sink and the broadcaster. */
class SseTest {

    private static final String HELLO_EVENT = "data: hello\n\n";

    private static final String HELLO = "hello";

    private final CassiniSse sse = new CassiniSse();

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---- events ----

    @Test
    void anEventCarriesEveryFieldGivenToItsBuilder() {
        OutboundSseEvent event = sse.newEventBuilder().id("7").name("tick").comment("note").reconnectDelay(500)
                .mediaType(MediaType.APPLICATION_JSON_TYPE).data(new GenericType<List<String>>() { }, List.of("a"))
                .build();
        assertEquals("7", event.getId());
        assertEquals("tick", event.getName());
        assertEquals("note", event.getComment());
        assertEquals(500, event.getReconnectDelay());
        assertTrue(event.isReconnectDelaySet());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, event.getMediaType());
        assertEquals(List.class, event.getType());
        assertEquals(new GenericType<List<String>>() { }.getType(), event.getGenericType());
        assertEquals(List.of("a"), event.getData());
    }

    @Test
    void theFactoryShortcutsBuildTextEvents() {
        OutboundSseEvent plain = sse.newEvent(HELLO);
        assertEquals(HELLO, plain.getData());
        assertEquals(String.class, plain.getType());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, plain.getMediaType());
        assertNull(plain.getName());
        assertFalse(plain.isReconnectDelaySet());
        assertEquals(SseEvent.RECONNECT_NOT_SET, plain.getReconnectDelay());
        assertEquals("greeting", sse.newEvent("greeting", HELLO).getName());

        OutboundSseEvent typed = sse.newEventBuilder().data(CharSequence.class, HELLO).build();
        assertEquals(CharSequence.class, typed.getType());
        assertEquals(CharSequence.class, typed.getGenericType());
    }

    @Test
    void anEventNeedsDataOrAComment() {
        assertEquals("only a comment", sse.newEventBuilder().comment("only a comment").build().getComment());
        var empty = sse.newEventBuilder().id("1");
        assertThrows(IllegalStateException.class, empty::build);
        var builder = sse.newEventBuilder();
        assertThrows(NullPointerException.class, () -> builder.mediaType(null));
        assertThrows(NullPointerException.class, () -> builder.data(null));
        assertThrows(NullPointerException.class, () -> builder.data((Class<?>) null, HELLO));
        assertThrows(NullPointerException.class, () -> builder.data(String.class, null));
        assertThrows(NullPointerException.class, () -> builder.data((GenericType<?>) null, HELLO));
        var stringType = new GenericType<String>() { };
        assertThrows(NullPointerException.class, () -> builder.data(stringType, null));
    }

    // ---- buffered sink ----

    @Test
    void aBufferedSinkWritesTheEventStreamFormat() throws Exception {
        var sink = new CassiniSseEventSink(new MessageBodyRegistry());
        assertFalse(sink.isStreaming());

        sink.send(sse.newEventBuilder().comment("first\nsecond").id("1").name("tick").reconnectDelay(3000)
                .data("line 1\nline 2").build()).toCompletableFuture().get(5, TimeUnit.SECONDS);
        sink.send(sse.newEventBuilder().comment("keep-alive").build());

        assertEquals(": first\n: second\nid: 1\nevent: tick\nretry: 3000\ndata: line 1\ndata: line 2\n\n"
                + ": keep-alive\n\n", new String(sink.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void aClosedSinkRefusesEventsAndReleasesItsWaiters() throws Exception {
        var sink = new CassiniSseEventSink(new MessageBodyRegistry());
        var waiter = Thread.ofVirtual().start(sink::awaitClose);
        sink.close();
        waiter.join(5_000);
        assertFalse(waiter.isAlive(), "awaitClose returns once the sink is closed");
        assertTrue(sink.isClosed());
        sink.awaitClose();
        var event = sse.newEvent(HELLO);
        assertThrows(IllegalStateException.class, () -> sink.send(event));
    }

    @Test
    void anInterruptedWaiterKeepsItsInterruptStatus() throws Exception {
        var sink = new CassiniSseEventSink(new MessageBodyRegistry());
        var interrupted = new CompletableFuture<Boolean>();
        var waiter = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            sink.awaitClose();
            interrupted.complete(Thread.currentThread().isInterrupted());
        });
        waiter.join(5_000);
        assertTrue(interrupted.get(5, TimeUnit.SECONDS));
    }

    // ---- streaming sink ----

    /** A transport sink recording what it is given. */
    private static final class RecordingStream implements CassiniStreamingSink {
        final List<String> chunks = new CopyOnWriteArrayList<>();
        int flushes;
        boolean open = true;
        boolean closed;
        CompletionStage<Void> nextWrite = CompletableFuture.completedFuture(null);

        @Override public CompletionStage<Void> writeChunk(byte[] data) {
            chunks.add(new String(data, StandardCharsets.UTF_8));
            return nextWrite;
        }
        @Override public CompletionStage<Void> flush() { flushes++; return CompletableFuture.completedFuture(null); }
        @Override public CompletionStage<Void> close() { closed = true; return CompletableFuture.completedFuture(null); }
        @Override public boolean isOpen() { return open; }
    }

    @Test
    void aStreamingSinkPushesEachEventAndFlushesIt() throws Exception {
        var stream = new RecordingStream();
        var sink = new CassiniSseEventSink(new MessageBodyRegistry(), stream);
        assertTrue(sink.isStreaming());

        sink.send(sse.newEvent(HELLO)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(List.of(HELLO_EVENT), stream.chunks);
        assertEquals(1, stream.flushes);
        assertEquals(0, sink.toByteArray().length, "nothing is buffered");
        sink.commitStreaming();
        assertEquals(2, stream.flushes, "a commit flushes the open stream");
        sink.close();
        assertTrue(stream.closed);
        sink.commitStreaming();
        assertEquals(2, stream.flushes, "a closed sink commits nothing");
    }

    @Test
    void aStreamClosedByTheClientClosesTheSink() {
        var stream = new RecordingStream();
        var sink = new CassiniSseEventSink(new MessageBodyRegistry(), stream);
        stream.open = false;
        sink.commitStreaming();
        assertEquals(0, stream.flushes);

        var sent = sink.send(sse.newEvent(HELLO)).toCompletableFuture();

        var failure = assertThrows(java.util.concurrent.ExecutionException.class, sent::get);
        assertInstanceOf(IOException.class, failure.getCause());
        assertTrue(sink.isClosed());
    }

    @Test
    void aFailedWriteClosesTheSink() {
        var stream = new RecordingStream();
        stream.nextWrite = CompletableFuture.failedFuture(new IOException("reset"));
        var sink = new CassiniSseEventSink(new MessageBodyRegistry(), stream);

        var sent = sink.send(sse.newEvent(HELLO)).toCompletableFuture();

        assertThrows(java.util.concurrent.ExecutionException.class, sent::get);
        assertTrue(sink.isClosed());
    }

    // ---- broadcaster ----

    @Test
    void aBroadcastReachesEveryRegisteredSink() throws Exception {
        SseBroadcaster broadcaster = sse.newBroadcaster();
        var first = new CassiniSseEventSink(new MessageBodyRegistry());
        var second = new CassiniSseEventSink(new MessageBodyRegistry());
        broadcaster.register(first);
        broadcaster.register(second);

        broadcaster.broadcast(sse.newEvent(HELLO)).toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertEquals(HELLO_EVENT, new String(first.toByteArray(), StandardCharsets.UTF_8));
        assertEquals(HELLO_EVENT, new String(second.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void aFailingSinkIsReportedAndAClosedOneIsDropped() {
        SseBroadcaster broadcaster = sse.newBroadcaster();
        List<SseEventSink> errors = new ArrayList<>();
        List<Throwable> causes = new ArrayList<>();
        List<SseEventSink> closes = new ArrayList<>();
        broadcaster.onError((sink, cause) -> { errors.add(sink); causes.add(cause); });
        broadcaster.onClose(closes::add);
        var healthy = new CassiniSseEventSink(new MessageBodyRegistry());
        var closed = new CassiniSseEventSink(new MessageBodyRegistry());
        broadcaster.register(healthy);
        broadcaster.register(closed);
        closeQuietly(closed);

        broadcaster.broadcast(sse.newEvent(HELLO));
        broadcaster.broadcast(sse.newEvent("again"));

        assertEquals(List.of(closed), errors, "reported once, then dropped");
        assertInstanceOf(IllegalStateException.class, causes.get(0));
        assertEquals(List.of(closed), closes);
        assertEquals("data: hello\n\ndata: again\n\n", new String(healthy.toByteArray(), StandardCharsets.UTF_8));
    }

    @Test
    void closingTheBroadcasterClosesItsSinks() {
        SseBroadcaster broadcaster = sse.newBroadcaster();
        List<SseEventSink> closes = new ArrayList<>();
        broadcaster.onClose(closes::add);
        var sink = new CassiniSseEventSink(new MessageBodyRegistry());
        broadcaster.register(sink);

        broadcaster.close();

        assertTrue(sink.isClosed());
        assertEquals(List.of(sink), closes);
        var other = new CassiniSseEventSink(new MessageBodyRegistry());
        assertThrows(IllegalStateException.class, () -> broadcaster.register(other));
    }

    @Test
    void aSinkThatFailsToCloseDoesNotStopTheBroadcasterClose() {
        SseBroadcaster broadcaster = sse.newBroadcaster();
        List<SseEventSink> closes = new ArrayList<>();
        broadcaster.onClose(closes::add);
        SseEventSink stubborn = new SseEventSink() {
            @Override public boolean isClosed() { return false; }
            @Override public CompletionStage<?> send(OutboundSseEvent event) { return CompletableFuture.completedFuture(null); }
            @Override public void close() throws IOException { throw new IOException("stuck"); }
        };
        broadcaster.register(stubborn);

        broadcaster.close(true);

        assertSame(stubborn, closes.get(0));
    }

    /** CASSINI-030: close(false) releases the broadcaster but leaves the sinks open. */
    @Test
    void aNonCascadingCloseLeavesTheSinksOpen() {
        SseBroadcaster broadcaster = sse.newBroadcaster();
        var sink = new CassiniSseEventSink(new MessageBodyRegistry());
        broadcaster.register(sink);

        broadcaster.close(false);

        assertFalse(sink.isClosed());
    }

    /** CASSINI-030: once closed, every other method throws IllegalStateException; a second close is ignored. */
    @Test
    void aClosedBroadcasterRefusesEveryOtherCallAndIgnoresAnotherClose() {
        SseBroadcaster broadcaster = sse.newBroadcaster();
        List<SseEventSink> closes = new ArrayList<>();
        broadcaster.onClose(closes::add);
        broadcaster.register(new CassiniSseEventSink(new MessageBodyRegistry()));
        broadcaster.close();
        broadcaster.close();
        broadcaster.close(true);

        assertEquals(1, closes.size(), "the close listeners run once");
        var event = sse.newEvent(HELLO);
        assertThrows(IllegalStateException.class, () -> broadcaster.broadcast(event));
        assertThrows(IllegalStateException.class, () -> broadcaster.onClose(closes::add));
        assertThrows(IllegalStateException.class, () -> broadcaster.onError((_, _) -> { }));
    }

    private static void closeQuietly(SseEventSink sink) {
        try {
            sink.close();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
