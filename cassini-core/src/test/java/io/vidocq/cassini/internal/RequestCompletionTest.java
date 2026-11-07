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
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A filter hands the request an end-of-request action through the {@code io.vidocq.cassini.requestCompleted}
 * property: Cassini runs it once, at the very end of the dispatch, on every path, after the entity is written
 * (CASSINI-007, cassini#116). The request scope of a CDI container ends there, not in a response filter, so the
 * entity writers, the writer interceptors and the exception mappers of a failed write still see it.
 */
class RequestCompletionTest {

    static final List<String> EVENTS = new CopyOnWriteArrayList<>();
    static final List<Thread> COMPLETED_ON = new CopyOnWriteArrayList<>();

    /** A snapshot of what happened, for the assertions. */
    private static List<String> events() {
        return List.copyOf(EVENTS);
    }

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @BeforeEach
    void reset() {
        EVENTS.clear();
        COMPLETED_ON.clear();
    }

    @Provider
    @PreMatching
    public static class Registers implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            request.setProperty(RequestCompletion.PROPERTY, (Runnable) () -> {
                EVENTS.add("completed");
                COMPLETED_ON.add(Thread.currentThread());
            });
        }
    }

    @Provider
    @PreMatching
    public static class RegistersAFailingAction implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            request.setProperty(RequestCompletion.PROPERTY, (Runnable) () -> {
                throw new IllegalStateException("the action fails");
            });
        }
    }

    @Provider
    public static class MapsWriteFailures implements ExceptionMapper<IllegalStateException> {
        @Override
        public Response toResponse(IllegalStateException failure) {
            EVENTS.add("mapped");
            return Response.status(502).entity("mapped").build();
        }
    }

    /** An error the JVM would not recover from, to stand for the ones nobody may swallow. */
    static final class Fatal extends Error {
        Fatal(String message) {
            super(message);
        }
    }

    @Provider
    @PreMatching
    public static class RegistersAnActionThatThrowsAnError implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            request.setProperty(RequestCompletion.PROPERTY, (Runnable) () -> {
                throw new Fatal("the action");
            });
        }
    }

    /** Registers the failing action, then fails itself: a filter is not guarded the way a resource method is. */
    @Provider
    @PreMatching
    public static class FailsAfterRegisteringAnActionThatFails extends RegistersAnActionThatThrowsAnError {
        @Override
        public void filter(ContainerRequestContext request) {
            super.filter(request);
            throw new Fatal("the request");
        }
    }

    @Path("/done")
    public static class Resource {

        @GET
        @Path("write")
        public Response write() {
            StreamingOutput body = out -> {
                EVENTS.add("written");
                out.write('x');
            };
            return Response.ok(body).build();
        }

        @GET
        @Path("write-fails")
        public Response writeFails() {
            StreamingOutput body = out -> {
                EVENTS.add("written");
                throw new IllegalStateException("the write fails");
            };
            return Response.ok(body).build();
        }

        @GET
        @Path("write-io-fails")
        public Response writeIoFails() {
            StreamingOutput body = out -> {
                EVENTS.add("written");
                throw new IOException("the connection is gone");
            };
            return Response.ok(body).build();
        }

        @GET
        @Path("boom")
        public String boom() {
            throw new IllegalStateException("not mapped");
        }

        @GET
        @Path("later")
        public void later(@Suspended AsyncResponse response) {
            Thread.ofVirtual().start(() -> {
                EVENTS.add("resumed");
                response.resume("late");
            });
        }
    }

    private static InMemoryExchange get(String path, Class<?>... providers) {
        Set<Class<?>> classes = new java.util.HashSet<>(Set.of(providers));
        classes.add(Resource.class);
        CassiniStack stack = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return classes;
            }
        }).build();
        InMemoryExchange exchange = InMemoryExchange.request("GET", path, null, null);
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    @Test
    void theActionRunsAfterTheEntityIsWritten() {
        InMemoryExchange exchange = get("/done/write", Registers.class);
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals(List.of("written", "completed"), events());
    }

    @Test
    void theActionRunsWhenNoRouteMatches() {
        InMemoryExchange exchange = get("/done/missing", Registers.class);
        assertEquals(404, exchange.status());
        assertEquals(List.of("completed"), events());
    }

    @Test
    void theActionRunsWhenAnUnmappedFailureEndsInA500() {
        InMemoryExchange exchange = get("/done/boom", Registers.class);
        assertEquals(500, exchange.status());
        assertEquals(List.of("completed"), events());
    }

    @Test
    void theActionRunsAfterTheMapperOfAFailedWrite() {
        InMemoryExchange exchange = get("/done/write-fails", Registers.class, MapsWriteFailures.class);
        assertEquals(List.of("written", "mapped", "completed"), events(), exchange::responseText);
    }

    @Test
    void theActionRunsWhenTheTransportFailsTheWrite() {
        InMemoryExchange exchange = get("/done/write-io-fails", Registers.class);
        assertEquals(500, exchange.status());
        assertEquals(List.of("written", "completed"), events());
    }

    @Test
    void theActionRunsOnTheDispatchThreadAfterAnAsyncResponseResumedElsewhere() {
        Thread dispatcher = Thread.currentThread();
        InMemoryExchange exchange = get("/done/later", Registers.class);
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals(List.of("resumed", "completed"), events());
        assertSame(dispatcher, COMPLETED_ON.get(0));
    }

    @Test
    void theActionRunsOnce() {
        get("/done/write", Registers.class);
        assertEquals(1, EVENTS.stream().filter("completed"::equals).count());
    }

    @Test
    void anActionThatFailsDoesNotChangeTheResponse() {
        InMemoryExchange exchange = get("/done/write", RegistersAFailingAction.class);
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals(List.of("written"), events());
    }

    @Test
    void anErrorOfTheActionDoesNotMaskTheErrorOfTheRequest() {
        Fatal original = assertThrows(Fatal.class, () -> get("/done/write", FailsAfterRegisteringAnActionThatFails.class));
        assertEquals("the request", original.getMessage());
        assertEquals(1, original.getSuppressed().length, "the action's error is kept, not lost");
        assertEquals("the action", original.getSuppressed()[0].getMessage());
    }

    @Test
    void anErrorOfTheActionIsNotSwallowedWhenTheRequestSucceeded() {
        Fatal failure = assertThrows(Fatal.class, () -> get("/done/write", RegistersAnActionThatThrowsAnError.class));
        assertEquals("the action", failure.getMessage());
    }
}
