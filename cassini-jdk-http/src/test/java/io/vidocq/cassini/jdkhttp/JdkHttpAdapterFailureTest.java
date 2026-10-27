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
package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpServer;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * cassini#58: a failing request must answer a 500 that carries no internal detail, and a response header the
 * wire cannot carry must answer a 500 too, with one log line naming the header and never its value — as the
 * Chappe adapter does since cassini#56.
 */
class JdkHttpAdapterFailureTest {

    private static final String LOGGER = JdkHttpAdapter.class.getName();
    private static final String SECRET = "secret-token-value";

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts the adapter on a free port over {@code engine}. */
    private URI serve(CassiniHttpAdapter engine) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", new JdkHttpAdapter(engine).asHandler());
        server.start();
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/resource");
    }

    private record Reply(int status, String body, String header) {}

    private static Reply get(URI uri, String headerName) throws IOException {
        HttpURLConnection c = (HttpURLConnection) uri.toURL().openConnection();
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        String body = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        return new Reply(status, body, c.getHeaderField(headerName));
    }

    /** An engine that answers 200 "hello" after letting {@code headers} add response headers. */
    private static CassiniHttpAdapter answering(Consumer<CassiniHttpExchange> headers) {
        return exchange -> {
            exchange.setStatus(200);
            headers.accept(exchange);
            try {
                exchange.responseBody().write("hello".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    private static LogRecord singleError(LogCapture logs) {
        List<LogRecord> errors = logs.atOrAbove(Level.SEVERE);
        assertEquals(1, errors.size(), () -> "expected one error line, got " + errors.stream()
                .map(LogRecord::getMessage).toList());
        return errors.getFirst();
    }

    private static void assertSecretNotLogged(LogRecord r) {
        assertFalse(r.getMessage().contains(SECRET), r.getMessage());
        for (Throwable t = r.getThrown(); t != null; t = t.getCause()) {
            assertFalse(String.valueOf(t.getMessage()).contains(SECRET), "logged throwable: " + t);
        }
    }

    @Test
    void a_failed_dispatch_answers_a_500_without_the_exception_message() throws Exception {
        CassiniHttpAdapter engine = exchange -> CompletableFuture.failedFuture(new IllegalStateException(SECRET));
        URI uri = serve(engine);
        try (var logs = LogCapture.of(LOGGER)) {
            Reply reply = get(uri, "Content-Type");
            assertEquals(500, reply.status());
            assertFalse(reply.body().contains(SECRET), "the body echoed the message: " + reply.body());
            LogRecord r = singleError(logs);
            assertTrue(r.getMessage().contains(IllegalStateException.class.getName()), r.getMessage());
            assertSecretNotLogged(r);
        }
    }

    @Test
    void a_dispatch_that_throws_answers_a_500_without_the_exception_message() throws Exception {
        CassiniHttpAdapter engine = new CassiniHttpAdapter() {
            @Override
            public CompletionStage<Void> dispatch(CassiniHttpExchange exchange) {
                throw new IllegalArgumentException(SECRET);
            }
        };
        URI uri = serve(engine);
        try (var logs = LogCapture.of(LOGGER)) {
            Reply reply = get(uri, "Content-Type");
            assertEquals(500, reply.status());
            assertFalse(reply.body().contains(SECRET), "the body echoed the message: " + reply.body());
            assertSecretNotLogged(singleError(logs));
        }
    }

    static Stream<Arguments> headersTheWireCannotCarry() {
        return Stream.of(
                Arguments.of("X-Poison", SECRET + " €"),          // not ISO-8859-1
                Arguments.of("X-Poison", SECRET + "\u0001"),           // control character
                Arguments.of("X-Poison", SECRET + "\u007F"),           // DEL
                Arguments.of("X Poison", SECRET),                      // not an RFC 9110 token
                Arguments.of("X-Poison", null));
    }

    @ParameterizedTest
    @MethodSource("headersTheWireCannotCarry")
    void a_header_the_wire_cannot_carry_answers_a_500_and_logs_the_name_only(String name, String value)
            throws Exception {
        URI uri = serve(answering(exchange -> exchange.responseHeaders()
                .computeIfAbsent(name, n -> new java.util.ArrayList<>()).add(value)));
        try (var logs = LogCapture.of(LOGGER)) {
            Reply reply = get(uri, name);
            assertEquals(500, reply.status());
            assertNull(reply.header(), "the refused header was sent");
            assertFalse(reply.body().contains("hello"), "the entity was sent with the 500");
            LogRecord r = singleError(logs);
            assertTrue(r.getMessage().contains(name), r.getMessage());
            assertSecretNotLogged(r);
        }
    }

    /**
     * Streaming sends its headers at once, so a header the wire cannot carry is refused before anything goes out:
     * no sink, which the SPI defines as "fall back to buffered mode", and one line naming the header.
     */
    @Test
    void streaming_refuses_a_header_the_wire_cannot_carry_before_sending_anything() throws Exception {
        var sink = new java.util.concurrent.atomic.AtomicReference<Object>("not called");
        URI uri = serve(exchange -> {
            sink.set(exchange.openForStreaming(200, java.util.Map.of("X-Poison", List.of(SECRET + " €"))));
            return CompletableFuture.completedFuture(null);
        });
        try (var logs = LogCapture.of(JdkHttpExchange.class.getName())) {
            Reply reply = get(uri, "X-Poison");
            assertNull(sink.get(), "a sink was opened over a refused header");
            assertNull(reply.header(), "the refused header was sent");
            LogRecord r = singleError(logs);
            assertTrue(r.getMessage().contains("X-Poison"), r.getMessage());
            assertSecretNotLogged(r);
        }
    }

    /**
     * A dispatch interrupted while the handler waits for it answers a 500, and the handler gives the thread its
     * interrupt status back instead of swallowing it.
     */
    @Test
    void an_interrupted_dispatch_answers_a_500_and_keeps_the_interrupt_status() throws Exception {
        var interruptedAfter = new CompletableFuture<Boolean>();
        var handler = new JdkHttpAdapter(exchange -> {
            Thread.currentThread().interrupt();
            return new CompletableFuture<>();
        }).asHandler();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                handler.handle(exchange);
            } finally {
                // Thread.interrupted() also clears the status, so the server thread goes on unharmed.
                interruptedAfter.complete(Thread.interrupted());
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/resource");
        try (var logs = LogCapture.of(LOGGER)) {
            assertEquals(500, get(uri, "Content-Type").status());
            assertTrue(interruptedAfter.get(10, java.util.concurrent.TimeUnit.SECONDS),
                    "the handler swallowed the interrupt status of its thread");
            singleError(logs);
        }
    }

    @Test
    void a_valid_response_goes_through_unchanged() throws Exception {
        URI uri = serve(answering(exchange -> exchange.responseHeaders()
                .put("X-Note", new java.util.ArrayList<>(List.of("café ok")))));
        try (var logs = LogCapture.of(LOGGER)) {
            Reply reply = get(uri, "X-Note");
            assertEquals(200, reply.status());
            assertEquals("hello", reply.body());
            assertEquals("café ok", reply.header(), "ISO-8859-1 text goes through");
            assertTrue(logs.atOrAbove(Level.SEVERE).isEmpty());
        }
    }
}
