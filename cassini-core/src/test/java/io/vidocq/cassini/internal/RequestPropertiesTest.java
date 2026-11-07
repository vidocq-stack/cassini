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
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The properties of a request are one store for the whole request: a property set by a pre-matching or a
 * post-matching request filter is read by the response filters, whichever context object each one receives
 * (CASSINI-007, cassini#116).
 */
class RequestPropertiesTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/props")
    public static class Resource {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Provider
    @PreMatching
    public static class Early implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            request.setProperty("early", "e");
            request.setProperty("gone", "g");
        }
    }

    @Provider
    public static class Late implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            request.setProperty("late", "l");
            request.removeProperty("gone");
        }
    }

    @Provider
    public static class Reader implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle("X-Props", request.getProperty("early") + "|" + request.getProperty("late")
                    + "|" + request.getProperty("gone") + "|" + request.getPropertyNames().stream().sorted().toList());
        }
    }

    private static InMemoryExchange get() {
        CassiniStack stack = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(Resource.class, Early.class, Late.class, Reader.class);
            }
        }).build();
        InMemoryExchange exchange = InMemoryExchange.request("GET", "/props", null, null);
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    @Test
    void theResponseFiltersSeeThePropertiesOfTheRequestFilters() {
        InMemoryExchange exchange = get();
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals("e|l|null|[early, late]", exchange.responseHeader("X-Props"));
    }
}
