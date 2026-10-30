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

import io.vidocq.cassini.internal.gen.AdapterRegistry;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.gen.InjectionSupport;
import io.vidocq.cassini.spi.gen.ResourceAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Before a reader, a writer or a post-matching filter runs, Cassini injects its {@code @Context} fields (§9.2). A
 * provider that declares none has nothing to inject, so it gets no adapter: Cassini's own JSON-B reader and writer,
 * and the filters of the other bricks (Humboldt, Cervantes), used to get one generated at run time on the first
 * request (CASSINI-036, cassini#100).
 */
class ProviderContextInjectionTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/points")
    public static class Points {
        @GET
        @Produces("application/json")
        public List<Integer> get() {
            return List.of(1, 2);
        }

        @POST
        @Consumes("application/json")
        @Produces("application/json")
        public List<Integer> echo(List<Integer> point) {
            return List.of(point.get(1), point.get(0));
        }
    }

    /** The adapter the build leaves next to {@link Points}, so that only the providers can need one at run time. */
    @SuppressWarnings("java:S101") // the name the build gives an adapter
    public static final class Points$$CassiniAdapter implements ResourceAdapter {
        @Override
        public void injectFields(Object target, InjectionSupport support, boolean injectParams) {
            // Points has no field to inject.
        }

        @Override
        public Object invoke(int methodId, Object target, Object[] args) {
            throw new UnsupportedOperationException("dispatched by reflection: no key table");
        }
    }

    /** A post-matching filter with state but no {@code @Context} field, as the filters of the other bricks are. */
    @Provider
    public static class Counting implements ContainerRequestFilter {
        static final AtomicInteger CALLS = new AtomicInteger();
        private final String name = "counting";

        @Override
        public void filter(ContainerRequestContext request) {
            CALLS.incrementAndGet();
            request.setProperty(name, Boolean.TRUE);
        }
    }

    /** A post-matching filter that needs its {@code @Context} field: it must still be injected. */
    @Provider
    public static class Recording implements ContainerRequestFilter {
        static final AtomicReference<String> PATH = new AtomicReference<>();

        @Context
        private UriInfo uriInfo;

        @Override
        public void filter(ContainerRequestContext request) {
            PATH.set(uriInfo == null ? "none" : uriInfo.getPath());
        }
    }

    private static CassiniStack stack(Class<?>... classes) {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(classes);
            }
        }).build();
    }

    private static InMemoryExchange send(CassiniStack stack, InMemoryExchange exchange) {
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    @Test
    void providersWithoutContextFieldsGetNoAdapterGeneratedAtRunTime() {
        CassiniStack stack = stack(Points.class, Counting.class);
        AdapterRegistry.resetCounters();

        InMemoryExchange get = send(stack, InMemoryExchange.request("GET", "/points", null, null));
        InMemoryExchange post = send(stack, InMemoryExchange.request("POST", "/points", "application/json", "[3,4]"));

        assertEquals(200, get.status(), get::responseText);
        assertEquals("[1,2]", get.responseText());
        assertEquals("[4,3]", post.responseText());
        assertTrue(Counting.CALLS.get() >= 2, "the filter ran");
        assertEquals(0, AdapterRegistry.runtimeGeneratedHits(),
                "the JSON-B reader and writer and a filter without @Context need no adapter");
    }

    @Test
    void aProviderWithAContextFieldIsStillInjected() {
        CassiniStack stack = stack(Points.class, Recording.class);

        InMemoryExchange get = send(stack, InMemoryExchange.request("GET", "/points", null, null));

        assertEquals(200, get.status(), get::responseText);
        assertEquals("points", Recording.PATH.get());
    }
}
