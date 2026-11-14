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
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Jakarta REST 4.0 §3.3.4 and §4.4: an exception a sub-resource locator throws is mapped as one a resource method
 * throws. An application {@code ExceptionMapper} answers it, a {@code WebApplicationException} answers its own
 * response, and a failure nothing maps answers a bare {@code 500} (CASSINI-046). Covered on the static path (a
 * locator that returns a typed sub-resource) and on the dynamic one (a locator that returns {@code Object}), for
 * the locator itself and for a method of the sub-resource it located (CASSINI-047).
 */
class LocatorExceptionMappingTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---------------------------------------------------------------- fixtures

    /** An exception the application maps itself. */
    public static final class LocatorFailure extends RuntimeException {
        LocatorFailure(String message) { super(message); }
    }

    @Provider
    public static final class LocatorFailureMapper implements ExceptionMapper<LocatorFailure> {
        @Override
        public Response toResponse(LocatorFailure e) {
            return Response.status(409).type(MediaType.TEXT_PLAIN_TYPE).entity("mapped: " + e.getMessage()).build();
        }
    }

    /** Marks every response it filters, so that a test can tell the response filters ran (§6.7). */
    @Provider
    public static final class MarkingFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle("X-Filtered", "yes");
        }
    }

    static WebApplicationException rejection(String thrower) {
        return new WebApplicationException(Response.status(422).type(MediaType.TEXT_PLAIN_TYPE)
                .entity("rejected by the " + thrower).build());
    }

    public static class Item {
        @GET
        @Produces(MediaType.TEXT_PLAIN)
        public String get() {
            return "item";
        }

        @GET
        @Path("mapped")
        @Produces(MediaType.TEXT_PLAIN)
        public String mapped() {
            throw new LocatorFailure("sub-resource method");
        }

        @GET
        @Path("rejected")
        @Produces(MediaType.TEXT_PLAIN)
        public String rejected() {
            throw rejection("sub-resource method");
        }

        @GET
        @Path("unmapped")
        @Produces(MediaType.TEXT_PLAIN)
        public String unmapped() {
            throw new IllegalStateException("sub-resource method");
        }
    }

    /** Locators that return a typed sub-resource: the static routes. */
    @Path("/typed")
    public static class TypedRoot {
        @Path("item")
        public Item item() {
            return new Item();
        }

        @Path("mapped")
        public Item mapped() {
            throw new LocatorFailure("locator");
        }

        @Path("rejected")
        public Item rejected() {
            throw rejection("locator");
        }

        @Path("unmapped")
        public Item unmapped() {
            throw new IllegalStateException("locator");
        }
    }

    /** Locators that return {@code Object}: the dynamic dispatch (§3.4.1). */
    @Path("/object")
    public static class ObjectRoot {
        @Path("item")
        public Object item() {
            return new Item();
        }

        @Path("mapped")
        public Object mapped() {
            throw new LocatorFailure("locator");
        }

        @Path("rejected")
        public Object rejected() {
            throw rejection("locator");
        }

        @Path("unmapped")
        public Object unmapped() {
            throw new IllegalStateException("locator");
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(TypedRoot.class, ObjectRoot.class, LocatorFailureMapper.class, MarkingFilter.class);
        }
    }).build();

    private static InMemoryExchange get(String path) {
        InMemoryExchange exchange = InMemoryExchange.get(path);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/typed/mapped,       locator",
            "/object/mapped,      locator",
            "/typed/item/mapped,  sub-resource method",
            "/object/item/mapped, sub-resource method",
    })
    void anExceptionTheApplicationMapsIsAnsweredByItsMapper(String path, String thrower) {
        InMemoryExchange ex = get(path);
        assertEquals(409, ex.status(), ex::responseText);
        assertEquals("mapped: " + thrower, ex.responseText());
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "/typed/rejected,       locator",
            "/object/rejected,      locator",
            "/typed/item/rejected,  sub-resource method",
            "/object/item/rejected, sub-resource method",
    })
    void aWebApplicationExceptionIsAnsweredWithItsOwnResponse(String path, String thrower) {
        InMemoryExchange ex = get(path);
        assertEquals(422, ex.status(), ex::responseText);
        assertEquals("rejected by the " + thrower, ex.responseText());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/typed/unmapped", "/object/unmapped", "/typed/item/unmapped", "/object/item/unmapped"})
    void aFailureNothingMapsAnswersABare500(String path) {
        InMemoryExchange ex = get(path);
        assertEquals(500, ex.status());
        assertEquals("", ex.responseText(), "the 500 carries no exception text");
        assertNull(ex.responseContentType());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/typed/item", "/typed/mapped", "/typed/item/mapped", "/typed/rejected"})
    void theResponseFiltersRunOnTheMappedResponseOfAStaticLocatorAsOnAResourceMethods(String path) {
        assertEquals("yes", get(path).responseHeader("X-Filtered"));
    }
}
