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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ContextResolver} is not among the {@code @Context} types of §9.2: a parameter of that type ends in the
 * "Unsupported @Context type" error, like any other type outside the list, instead of receiving the
 * {@code Providers} and failing with a {@code ClassCastException} (CASSINI-008, cassini#117).
 */
class ContextResolverParameterTest {

    private static final AtomicReference<Throwable> SEEN = new AtomicReference<>();

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/cr")
    public static class Resource {
        @GET
        public String get(@Context ContextResolver<?> resolver) {
            return "reached";
        }
    }

    @Provider
    public static class Recorder implements ExceptionMapper<Throwable> {
        @Override
        public Response toResponse(Throwable failure) {
            SEEN.set(failure);
            return Response.serverError().build();
        }
    }

    @Test
    void aContextResolverParameterIsAnUnsupportedContextType() {
        CassiniStack stack = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(Resource.class, Recorder.class);
            }
        }).build();
        var exchange = InMemoryExchange.request("GET", "/cr", null, null);
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals(500, exchange.status());
        var failure = assertInstanceOf(WebApplicationException.class, SEEN.get());
        assertTrue(failure.getMessage().contains("Unsupported @Context type"), failure.getMessage());
    }
}
