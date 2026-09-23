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
import io.vidocq.cassini.spi.http.CassiniStatistics;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cassini#42: a stack counts the requests it serves, and a host reads the counts through
 * {@link CassiniStack#statistics()} without creating anything.
 */
class RequestStatisticsTest {

    static final AtomicInteger RESOURCES_CREATED = new AtomicInteger();
    static volatile CountDownLatch entered;
    static volatile CountDownLatch release;

    @BeforeAll
    static void runtimeDelegate() {
        // Response.status(..) needs one; the transports register it, cassini-core does not.
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @BeforeEach
    void reset() {
        RESOURCES_CREATED.set(0);
    }

    @Path("/things")
    public static class ThingResource {
        public ThingResource() { RESOURCES_CREATED.incrementAndGet(); }

        @GET
        public String list() { return "[]"; }

        @POST
        public Response create() { return Response.status(201).build(); }

        @GET
        @Path("broken")
        public String broken() { throw new IllegalStateException("boom"); }

        @GET
        @Path("slow")
        public String slow() throws InterruptedException {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return "done";
        }
    }

    private static CassiniStack stack(boolean statistics) {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(ThingResource.class);
            }
        }).statistics(statistics).build();
    }

    private static int dispatch(CassiniStack stack, InMemoryExchange exchange) {
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange.status();
    }

    @Test
    void counts_every_request_by_the_class_of_its_status() {
        CassiniStack stack = stack(true);
        CassiniStatistics stats = stack.statistics().orElseThrow();

        assertEquals(200, dispatch(stack, InMemoryExchange.get("/things")));
        assertEquals(200, dispatch(stack, InMemoryExchange.get("/things")));
        assertEquals(201, dispatch(stack, InMemoryExchange.post("/things", "text/plain", "x")));
        assertEquals(404, dispatch(stack, InMemoryExchange.get("/nowhere")));
        assertEquals(500, dispatch(stack, InMemoryExchange.get("/things/broken")));

        assertEquals(5, stats.requests());
        assertEquals(0, stats.inFlight());
        assertEquals(0, stats.responses(1));
        assertEquals(3, stats.responses(2));
        assertEquals(0, stats.responses(3));
        assertEquals(1, stats.responses(4));
        assertEquals(1, stats.responses(5));
    }

    @Test
    void times_the_requests() {
        CassiniStack stack = stack(true);
        CassiniStatistics stats = stack.statistics().orElseThrow();
        assertEquals(0, stats.totalNanos());
        assertEquals(0, stats.maxNanos());

        dispatch(stack, InMemoryExchange.get("/things"));
        dispatch(stack, InMemoryExchange.get("/things"));

        assertTrue(stats.maxNanos() > 0, "a request takes some time");
        assertTrue(stats.totalNanos() >= stats.maxNanos());
    }

    @Test
    void counts_a_request_in_flight_until_it_is_answered() throws Exception {
        CassiniStack stack = stack(true);
        CassiniStatistics stats = stack.statistics().orElseThrow();
        entered = new CountDownLatch(1);
        release = new CountDownLatch(1);

        Thread request = Thread.ofVirtual().start(() -> dispatch(stack, InMemoryExchange.get("/things/slow")));
        assertTrue(entered.await(10, TimeUnit.SECONDS));

        assertEquals(1, stats.inFlight());
        assertEquals(0, stats.requests(), "requests() counts completed requests only");

        release.countDown();
        request.join(10_000);
        assertEquals(0, stats.inFlight());
        assertEquals(1, stats.requests());
        assertEquals(1, stats.responses(2));
    }

    @Test
    void a_status_class_outside_one_to_five_is_rejected() {
        CassiniStatistics stats = stack(true).statistics().orElseThrow();

        assertThrows(IllegalArgumentException.class, () -> stats.responses(0));
        assertThrows(IllegalArgumentException.class, () -> stats.responses(6));
    }

    @Test
    void reading_creates_no_instance() {
        CassiniStack stack = stack(true);
        CassiniStatistics stats = stack.statistics().orElseThrow();

        stats.requests();
        stats.inFlight();
        stats.responses(2);
        stats.totalNanos();
        stats.maxNanos();
        stack.statistics();

        assertEquals(0, RESOURCES_CREATED.get());
    }

    @Test
    void a_stack_built_without_statistics_has_none() {
        CassiniStack stack = stack(false);

        assertTrue(stack.statistics().isEmpty());
        assertEquals(200, dispatch(stack, InMemoryExchange.get("/things")));
    }

    @Test
    void statistics_are_on_by_default() {
        CassiniStack stack = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(ThingResource.class);
            }
        }).build();

        assertTrue(stack.statistics().isPresent());
    }
}
