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

import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * cassini#56: a JAX-RS response header that Chappe refuses must end the request with a 500,
 * one log line that names the header without echoing its value and, in streaming mode,
 * release the pipe so the thread writing the entity terminates.
 *
 * <p>The tests driven by {@link #POISON} make the Chappe builder fail through a test seam, so
 * they hold on any Chappe version. The tests driven by {@link #rejectedByChappe()} use the real
 * builder and are skipped when the installed Chappe still accepts the value (pre CHAPPE-008).
 */
class ResponseHeaderRejectionTest {

    private static final String LOGGER = ChappeHttpAdapter.class.getName();
    private static final String POISON = "X-Poison";
    private static final String POISON_VALUE = "secret-token-value";
    private static final Duration WRITER_DEADLINE = Duration.ofSeconds(10);

    // ---- seam-driven: hold on any Chappe version ---------------------------------------

    @Test
    void buffered_failing_header_answers_500_and_logs_the_name_only() throws Exception {
        var adapter = new ChappeHttpAdapter(bufferedEngine(POISON, POISON_VALUE),
                ChappeHttpAdapter.Scoped.IDENTITY, failingOn(POISON, IllegalArgumentException::new));
        try (var logs = LogCapture.of(LOGGER)) {
            Response response = adapter.handle(request());
            assertEquals(500, response.status().code());
            assertSingleDiagnostic(logs, POISON, POISON_VALUE);
        }
    }

    @Test
    void buffered_failure_other_than_illegal_argument_is_handled_too() throws Exception {
        var adapter = new ChappeHttpAdapter(bufferedEngine(POISON, POISON_VALUE),
                ChappeHttpAdapter.Scoped.IDENTITY, failingOn(POISON, NullPointerException::new));
        try (var logs = LogCapture.of(LOGGER)) {
            Response response = adapter.handle(request());
            assertEquals(500, response.status().code());
            assertSingleDiagnostic(logs, POISON, POISON_VALUE);
        }
    }

    @Test
    void streaming_failing_header_answers_500_and_releases_the_writer() throws Exception {
        var writer = new AtomicReference<Thread>();
        var writeFailure = new AtomicReference<Throwable>();
        var adapter = new ChappeHttpAdapter(streamingEngine(POISON, POISON_VALUE, writer, writeFailure),
                ChappeHttpAdapter.Scoped.IDENTITY, failingOn(POISON, IllegalArgumentException::new));
        try (var logs = LogCapture.of(LOGGER)) {
            Response response = adapter.handle(request());
            assertEquals(500, response.status().code());
            assertWriterTerminated(writer, writeFailure);
            assertSingleDiagnostic(logs, POISON, POISON_VALUE);
        }
    }

    @Test
    void streaming_failure_other_than_illegal_argument_releases_the_writer() throws Exception {
        var writer = new AtomicReference<Thread>();
        var writeFailure = new AtomicReference<Throwable>();
        var adapter = new ChappeHttpAdapter(streamingEngine(POISON, POISON_VALUE, writer, writeFailure),
                ChappeHttpAdapter.Scoped.IDENTITY, failingOn(POISON, IllegalStateException::new));
        try (var logs = LogCapture.of(LOGGER)) {
            Response response = adapter.handle(request());
            assertEquals(500, response.status().code());
            assertWriterTerminated(writer, writeFailure);
            assertSingleDiagnostic(logs, POISON, POISON_VALUE);
        }
    }

    // ---- real Chappe builder: skipped if the installed Chappe accepts the field --------

    static Stream<Arguments> rejectedByChappe() {
        return Stream.of(
                Arguments.of("invalid name", "Bad Name", "plain"),
                Arguments.of("CR/LF value", "X-Note", "a\r\nX-Injected: 1"),
                Arguments.of("non-Latin-1 value", "Content-Disposition",
                        "attachment; filename=\"Отчёт-€.pdf\""),
                Arguments.of("null value", "X-Note", null));
    }

    @ParameterizedTest(name = "buffered, {0}")
    @MethodSource("rejectedByChappe")
    void buffered_header_rejected_by_chappe(String label, String name, String value) throws Exception {
        assumeChappeRejects(name, value);
        var adapter = new ChappeHttpAdapter(bufferedEngine(name, value));
        try (var logs = LogCapture.of(LOGGER)) {
            Response response = adapter.handle(request());
            assertEquals(500, response.status().code());
            assertSingleDiagnostic(logs, name, value);
        }
    }

    @ParameterizedTest(name = "streaming, {0}")
    @MethodSource("rejectedByChappe")
    void streaming_header_rejected_by_chappe(String label, String name, String value) throws Exception {
        // The streaming snapshot (List.copyOf) cannot carry a null value: nothing reaches Chappe.
        assumeTrue(value != null, "a null value never reaches the streaming path");
        assumeChappeRejects(name, value);
        var writer = new AtomicReference<Thread>();
        var writeFailure = new AtomicReference<Throwable>();
        var adapter = new ChappeHttpAdapter(streamingEngine(name, value, writer, writeFailure));
        try (var logs = LogCapture.of(LOGGER)) {
            Response response = adapter.handle(request());
            assertEquals(500, response.status().code());
            assertWriterTerminated(writer, writeFailure);
            assertSingleDiagnostic(logs, name, value);
        }
    }

    @Test
    void non_latin1_value_diagnostic_points_to_rfc_8187() throws Exception {
        String value = "attachment; filename=\"€.pdf\"";
        var adapter = new ChappeHttpAdapter(bufferedEngine("Content-Disposition", value),
                ChappeHttpAdapter.Scoped.IDENTITY, failingOn("Content-Disposition", IllegalArgumentException::new));
        try (var logs = LogCapture.of(LOGGER)) {
            adapter.handle(request());
            String message = assertSingleDiagnostic(logs, "Content-Disposition", value);
            assertTrue(message.contains("RFC 8187"), message);
            assertTrue(message.contains("filename*=UTF-8''"), message);
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private static void assumeChappeRejects(String name, String value) {
        boolean rejected;
        try {
            Response.builder().status(StatusCode.of(200)).header(name, value).body(Body.empty()).build();
            rejected = false;
        } catch (RuntimeException e) {
            rejected = true;
        }
        assumeTrue(rejected, "the installed Chappe accepts this header (pre CHAPPE-008)");
    }

    /** Engine answering 200 with a body and the given response header (buffered mode). */
    private static CassiniHttpAdapter bufferedEngine(String name, String value) {
        return exchange -> {
            exchange.setStatus(200);
            var values = new ArrayList<String>();
            values.add(value);
            exchange.responseHeaders().put(name, values);
            try {
                exchange.responseBody().write("hello".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    /**
     * Engine opening a streaming response with the given header, then writing chunks until a
     * write fails — enough chunks to fill the bounded pipe, so it blocks forever if nobody
     * releases the pipe.
     */
    private static CassiniHttpAdapter streamingEngine(String name, String value,
                                                      AtomicReference<Thread> writer,
                                                      AtomicReference<Throwable> writeFailure) {
        return exchange -> {
            writer.set(Thread.currentThread());
            var headers = new LinkedHashMap<String, List<String>>();
            headers.put("Content-Type", List.of("text/event-stream"));
            headers.put(name, List.of(value));
            var sink = exchange.openForStreaming(200, headers);
            byte[] chunk = new byte[1024];
            try {
                while (true) {
                    sink.writeChunk(chunk).toCompletableFuture().join();
                }
            } catch (CompletionException e) {
                writeFailure.set(e.getCause());
            }
            return CompletableFuture.completedFuture(null);
        };
    }

    private static void assertWriterTerminated(AtomicReference<Thread> writer,
                                               AtomicReference<Throwable> writeFailure)
            throws InterruptedException {
        Thread t = writer.get();
        assertNotNull(t, "the engine never ran");
        assertTrue(t.join(WRITER_DEADLINE), "the entity-writing thread is still blocked on the pipe");
        assertInstanceOf(IOException.class, writeFailure.get(), "the writer must see an IOException");
    }

    /**
     * Exactly one WARNING-or-above record, naming the header and echoing neither the value
     * nor the exception message (which a Chappe builder may build from the value).
     *
     * @return the message of that record
     */
    private static String assertSingleDiagnostic(LogCapture logs, String name, String value) {
        List<LogRecord> records = logs.atOrAbove(Level.WARNING);
        assertEquals(1, records.size(), () -> "expected one diagnostic, got " + records.stream()
                .map(LogRecord::getMessage).toList());
        LogRecord r = records.getFirst();
        String message = r.getMessage();
        assertTrue(message.contains(name), message);
        if (value != null) {
            assertFalse(message.contains(value), "the header value must never be logged: " + message);
            for (Throwable t = r.getThrown(); t != null; t = t.getCause()) {
                String m = String.valueOf(t.getMessage());
                assertFalse(m.contains(value), "the header value leaks through the logged throwable: " + m);
            }
        }
        return message;
    }

    /** A real Chappe builder whose {@code header(poison, ...)} throws, with the value in the message. */
    private static Supplier<Response.Builder> failingOn(String poison,
                                                        java.util.function.Function<String, RuntimeException> failure) {
        return () -> new Response.Builder() {
            private final Response.Builder delegate = Response.builder();
            @Override public Response.Builder status(StatusCode status) { delegate.status(status); return this; }
            @Override public Response.Builder header(String name, String value) {
                if (poison.equals(name)) throw failure.apply("rejected value " + value);
                delegate.header(name, value);
                return this;
            }
            @Override public Response.Builder headers(Headers headers) { delegate.headers(headers); return this; }
            @Override public Response.Builder trailer(String name, String value) { delegate.trailer(name, value); return this; }
            @Override public Response.Builder trailers(Headers trailers) { delegate.trailers(trailers); return this; }
            @Override public Response.Builder body(Body body) { delegate.body(body); return this; }
            @Override public Response.Builder body(String text) { delegate.body(text); return this; }
            @Override public Response.Builder body(byte[] bytes) { delegate.body(bytes); return this; }
            @Override public Response build() { return delegate.build(); }
        };
    }

    private static Request request() {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create("/files/report"); }
            @Override public String path() { return "/files/report"; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return Headers.empty(); }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
        };
    }
}
