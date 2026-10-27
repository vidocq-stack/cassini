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
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * §6.6.1: a {@code @PreMatching} filter runs once per request, before routing. The adapter ran
 * it before matching, and the invoker ran it a second time before negotiation, each time with a
 * fresh {@code ContainerRequestContext}: a filter that counts, sets a property or activates a
 * scope saw two requests where there was one.
 */
class PreMatchingFilterRunsOnceTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @BeforeEach
    void reset() {
        CountingFilter.REQUESTS.set(0);
        CountingFilter.RESPONSES.set(0);
    }

    @Path("/things")
    public static class ThingResource {
        @GET
        public String list() {
            return "[]";
        }
    }

    @Provider
    @PreMatching
    public static class CountingFilter implements ContainerRequestFilter, ContainerResponseFilter {
        static final AtomicInteger REQUESTS = new AtomicInteger();
        static final AtomicInteger RESPONSES = new AtomicInteger();

        @Override
        public void filter(ContainerRequestContext ctx) {
            REQUESTS.incrementAndGet();
        }

        @Override
        public void filter(ContainerRequestContext ctx, ContainerResponseContext response) {
            RESPONSES.incrementAndGet();
        }
    }

    private static CassiniStack stack() {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(ThingResource.class, CountingFilter.class);
            }
        }).build();
    }

    @Test
    void aPreMatchingFilterRunsOncePerRequest() {
        InMemoryExchange exchange = InMemoryExchange.get("/things");
        stack().adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals(200, exchange.status());
        assertEquals(1, CountingFilter.REQUESTS.get(), "pre-matching request filter runs per request");
        assertEquals(1, CountingFilter.RESPONSES.get(), "response filter runs per request");
    }

    @Test
    void aPreMatchingFilterRunsOnceForAnUnmatchedRequestToo() {
        InMemoryExchange exchange = InMemoryExchange.get("/nowhere");
        stack().adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals(404, exchange.status());
        assertEquals(1, CountingFilter.REQUESTS.get(), "pre-matching request filter runs per request");
    }
}
