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
package io.vidocq.cassini.internal;

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CASSINI-046: a failure no {@code ExceptionMapper} handles (Jakarta REST 4.0 §3.3.4) answers a bare
 * {@code 500}, with neither the message nor the class of the failure in the response: they may carry SQL,
 * file paths or request values (CWE-209). The log keeps both, with the stack trace. A
 * {@code WebApplicationException}'s own response and the response of a mapper are the application's, and stay
 * as they are. Driven end to end through {@link DefaultCassiniHttpAdapter#dispatch} on an
 * {@link InMemoryExchange}.
 */
class UnmappedFailureResponseTest {

    /** What the tutorial application sent to its client: database text. */
    static final String SECRET = "ERROR: duplicate key value violates unique constraint \"books_isbn_key\""
            + " Detail: Key (isbn)=(9780321349606) already exists.";

    private static final String DISPATCH_LOGGER = DefaultCassiniHttpAdapter.class.getName();

    @BeforeAll
    static void runtimeDelegate() {
        // cassini-core does not register a RuntimeDelegate, the transports do; Response needs one.
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---------------------------------------------------------------- fixtures

    /** A checked exception, as a persistence layer throws one. */
    public static final class StoreException extends Exception {
        StoreException(String message) { super(message); }
    }

    /** An exception the application maps itself. */
    public static final class DuplicateKeyException extends RuntimeException {
        DuplicateKeyException(String message) { super(message); }
    }

    /** A {@code WebApplicationException} without a response: only a subclass can give one. */
    public static final class ResponselessException extends WebApplicationException {
        ResponselessException(String message) { super(message); }

        @Override
        public Response getResponse() { return null; }
    }

    /** An entity only {@link FailingWriter} can write. */
    public record Secretive(String value) {}

    @Provider
    @Produces(MediaType.TEXT_PLAIN)
    public static final class FailingWriter implements MessageBodyWriter<Secretive> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Secretive.class;
        }

        @Override
        public void writeTo(Secretive secretive, Class<?> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream out) {
            throw new IllegalStateException(SECRET);
        }
    }

    @Provider
    public static final class DuplicateKeyMapper implements ExceptionMapper<DuplicateKeyException> {
        @Override
        public Response toResponse(DuplicateKeyException e) {
            return Response.status(409).type(MediaType.TEXT_PLAIN_TYPE).entity("mapped: " + e.getMessage()).build();
        }
    }

    @Path("/fail")
    @Produces(MediaType.TEXT_PLAIN)
    public static class FailingResource {
        @GET
        @Path("runtime")
        public String runtime() {
            throw new IllegalStateException(SECRET);
        }

        @GET
        @Path("error")
        public String error() {
            throw new AssertionError(SECRET);
        }

        @GET
        @Path("checked")
        public String checked() throws StoreException {
            throw new StoreException(SECRET);
        }

        @GET
        @Path("async")
        public void async(@Suspended AsyncResponse response) {
            response.resume(new IllegalStateException(SECRET));
        }

        @GET
        @Path("async-error")
        public void asyncError(@Suspended AsyncResponse response) {
            response.resume(new AssertionError(SECRET));
        }

        @GET
        @Path("stage")
        public CompletionStage<String> stage() {
            return CompletableFuture.failedFuture(new IllegalStateException(SECRET));
        }

        @GET
        @Path("write")
        public Secretive write() {
            return new Secretive("never written");
        }

        @GET
        @Path("responseless")
        public String responseless() {
            throw new ResponselessException(SECRET);
        }

        @GET
        @Path("sse")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void sse(@Context SseEventSink sink) {
            throw new IllegalStateException(SECRET);
        }

        @GET
        @Path("mapped")
        public String mapped() {
            throw new DuplicateKeyException("isbn 9780321349606");
        }

        @GET
        @Path("wae-entity")
        public String waeEntity() {
            throw new WebApplicationException(Response.status(422).type(MediaType.TEXT_PLAIN_TYPE)
                    .entity("rejected: the isbn is taken").build());
        }
    }

    /** §3.4.1: a locator returning {@code Object} takes the DynamicLocatorDispatch path. */
    @Path("/dyn")
    public static class DynamicRoot {
        @Path("sub")
        public Object sub() {
            return new FailingSub();
        }
    }

    public static class FailingSub {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            throw new IllegalStateException(SECRET);
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(FailingResource.class, DynamicRoot.class, FailingWriter.class, DuplicateKeyMapper.class);
        }
    }).build();

    private static InMemoryExchange get(String path) {
        InMemoryExchange exchange = InMemoryExchange.get(path);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    private static List<Throwable> chain(Throwable t) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable c = t; c != null && !chain.contains(c); c = c.getCause()) chain.add(c);
        return chain;
    }

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/fail/runtime,      java.lang.IllegalStateException",
            "/fail/error,        java.lang.AssertionError",
            "/fail/checked,      io.vidocq.cassini.internal.UnmappedFailureResponseTest$StoreException",
            "/fail/async,        java.lang.IllegalStateException",
            "/fail/async-error,  java.lang.AssertionError",
            "/fail/stage,        java.lang.IllegalStateException",
            "/fail/write,        java.lang.IllegalStateException",
            "/fail/sse,          java.lang.IllegalStateException",
            "/dyn/sub,           java.lang.IllegalStateException",
    })
    void anUnmappedFailureAnswersABare500AndIsLoggedWithItsStack(String path, String failureClass) {
        try (LogCapture logs = LogCapture.of(DISPATCH_LOGGER)) {
            InMemoryExchange ex = get(path);

            assertEquals(500, ex.status());
            String body = ex.responseText();
            assertEquals("", body, "the 500 carries no exception text");
            assertFalse(body.contains(failureClass), body);
            assertNull(ex.responseContentType(), "no body, no Content-Type");

            List<LogRecord> errors = logs.atOrAbove(Level.SEVERE);
            assertEquals(1, errors.size(), "the failure is logged once at ERROR");
            Throwable logged = errors.get(0).getThrown();
            assertNotNull(logged, "with its stack trace");
            Throwable failure = chain(logged).stream()
                    .filter(t -> t.getClass().getName().equals(failureClass))
                    .findFirst().orElse(null);
            assertNotNull(failure, () -> "the log carries the " + failureClass + ": " + logged);
            assertEquals(SECRET, failure.getMessage(), "the log keeps the message");
            assertTrue(failure.getStackTrace().length > 0, "and the stack frames");
        }
    }

    @Test
    void aWebApplicationExceptionWithoutResponseAnswersABare500() {
        InMemoryExchange ex = get("/fail/responseless");
        assertEquals(500, ex.status());
        assertEquals("", ex.responseText(), "the 500 carries no exception text");
        assertNull(ex.responseContentType());
    }

    @Test
    void renderThrowableAnswersAnUnmappedFailureWithABare500() throws IOException {
        CassiniHttpResponse out = new Invoker(_ -> null).renderThrowable(new IllegalStateException(SECRET));
        assertEquals(500, out.status());
        assertEquals(0, out.bodyLength(), "the 500 carries no exception text");
        assertEquals(Map.of(), out.headers());
    }

    @Test
    void theResponseOfAnApplicationMapperIsKept() {
        InMemoryExchange ex = get("/fail/mapped");
        assertEquals(409, ex.status());
        assertEquals("mapped: isbn 9780321349606", ex.responseText());
    }

    @Test
    void theOwnResponseOfAWebApplicationExceptionIsKept() {
        InMemoryExchange ex = get("/fail/wae-entity");
        assertEquals(422, ex.status());
        assertEquals("rejected: the isbn is taken", ex.responseText());
    }
}
