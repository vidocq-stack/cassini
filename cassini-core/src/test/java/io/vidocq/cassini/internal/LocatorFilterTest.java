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
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Jakarta REST 4.0 §6.5: the container filters apply to the resource methods a sub-resource locator leads to, as to
 * any resource method. A global filter applies to all of them (§6.5.1), a name-bound one to those whose method or
 * class carries its binding (§6.5.2), and the response filters process a mapped response too (§4.4, §6.7.1).
 * Covered on the static path (a locator that returns a typed sub-resource) and on the dynamic one (a locator that
 * returns {@code Object}, an instance or a class, §3.4.1), where the resource method is only known once the locator
 * ran (CASSINI-050).
 */
class LocatorFilterTest {

    private static final String TEXT = MediaType.TEXT_PLAIN;
    private static final String GLOBAL_REQUEST = "global request";
    private static final String AUDITED_REQUEST = "audited request";
    private static final String GLOBAL_RESPONSE = "X-Global";
    private static final String AUDITED_RESPONSE = "X-Audited";
    private static final String YES = "yes";

    /** What the request filters saw, in order: one entry per filter run. */
    private static final List<String> TRAIL = new CopyOnWriteArrayList<>();

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @BeforeEach
    void clearTrail() {
        TRAIL.clear();
    }

    // ---------------------------------------------------------------- fixtures

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Audited { }

    /** A post-matching request filter bound globally; it names the resource method it was matched for. */
    @Provider
    public static final class GlobalRequestFilter implements ContainerRequestFilter {
        @Context
        ResourceInfo resourceInfo;

        @Override
        public void filter(ContainerRequestContext request) {
            TRAIL.add(GLOBAL_REQUEST + " " + resourceInfo.getResourceMethod().getName());
        }
    }

    @Provider
    @Audited
    public static final class AuditedRequestFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            TRAIL.add(AUDITED_REQUEST);
        }
    }

    @Provider
    public static final class GlobalResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle(GLOBAL_RESPONSE, YES);
        }
    }

    @Provider
    @Audited
    public static final class AuditedResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle(AUDITED_RESPONSE, YES);
        }
    }

    /** An exception the application maps itself. */
    public static final class ItemFailure extends RuntimeException {
        ItemFailure(String message) { super(message); }
    }

    @Provider
    public static final class ItemFailureMapper implements ExceptionMapper<ItemFailure> {
        @Override
        public Response toResponse(ItemFailure e) {
            return Response.status(409).type(MediaType.TEXT_PLAIN_TYPE).entity("mapped: " + e.getMessage()).build();
        }
    }

    public static class Item {
        @GET
        @Produces(TEXT)
        public String plain() {
            return "plain";
        }

        @GET
        @Path("audited")
        @Audited
        @Produces(TEXT)
        public String audited() {
            return "audited";
        }

        @GET
        @Path("mapped")
        @Audited
        @Produces(TEXT)
        public String mapped() {
            throw new ItemFailure("sub-resource method");
        }
    }

    /** The binding on the class applies to each of its resource methods. */
    @Audited
    public static class AuditedItem {
        @GET
        @Produces(TEXT)
        public String whole() {
            return "audited item";
        }
    }

    /** Locators that return a typed sub-resource: the static routes. */
    @Path("/typed")
    public static class TypedRoot {
        @Path("item")
        public Item item() {
            return new Item();
        }

        @Path("audited-item")
        public AuditedItem auditedItem() {
            return new AuditedItem();
        }

        @Path("failing")
        public Item failing() {
            throw new ItemFailure("locator");
        }
    }

    /** Locators that return {@code Object}: the dynamic dispatch (§3.4.1). */
    @Path("/object")
    public static class ObjectRoot {
        @Path("item")
        public Object item() {
            return new Item();
        }

        @Path("class")
        public Object itemClass() {
            return Item.class;
        }

        @Path("audited-item")
        public Object auditedItem() {
            return new AuditedItem();
        }

        @Path("failing")
        public Object failing() {
            throw new ItemFailure("locator");
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(TypedRoot.class, ObjectRoot.class, ItemFailureMapper.class,
                    GlobalRequestFilter.class, AuditedRequestFilter.class,
                    GlobalResponseFilter.class, AuditedResponseFilter.class);
        }
    }).build();

    private static InMemoryExchange get(String path) {
        InMemoryExchange exchange = InMemoryExchange.get(path);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/typed/item", "/object/item", "/object/class"})
    void theGlobalFiltersRunOnTheResourceMethodALocatorLeadsTo(String path) {
        InMemoryExchange ex = get(path);
        assertEquals(200, ex.status(), ex::responseText);
        assertEquals("plain", ex.responseText());
        assertEquals(List.of(GLOBAL_REQUEST + " plain"), List.copyOf(TRAIL), "the request filters, with the resource method");
        assertEquals(YES, ex.responseHeader(GLOBAL_RESPONSE));
        assertNull(ex.responseHeader(AUDITED_RESPONSE), "the method carries no binding");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/typed/item/audited", "/object/item/audited"})
    void theBoundFiltersRunOnAResourceMethodThatCarriesTheBinding(String path) {
        InMemoryExchange ex = get(path);
        assertEquals("audited", ex.responseText());
        assertEquals(Set.of(GLOBAL_REQUEST + " audited", AUDITED_REQUEST), Set.copyOf(TRAIL));
        assertEquals(2, TRAIL.size(), () -> "each request filter runs once: " + TRAIL);
        assertEquals(YES, ex.responseHeader(GLOBAL_RESPONSE));
        assertEquals(YES, ex.responseHeader(AUDITED_RESPONSE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/typed/audited-item", "/object/audited-item"})
    void theBoundFiltersRunOnAResourceMethodWhoseClassCarriesTheBinding(String path) {
        InMemoryExchange ex = get(path);
        assertEquals("audited item", ex.responseText());
        assertEquals(Set.of(GLOBAL_REQUEST + " whole", AUDITED_REQUEST), Set.copyOf(TRAIL));
        assertEquals(YES, ex.responseHeader(AUDITED_RESPONSE));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/typed/item/mapped", "/object/item/mapped"})
    void theResponseFiltersRunOnTheMappedResponseOfTheResourceMethod(String path) {
        InMemoryExchange ex = get(path);
        assertEquals(409, ex.status(), ex::responseText);
        assertEquals("mapped: sub-resource method", ex.responseText());
        assertEquals(Set.of(GLOBAL_REQUEST + " mapped", AUDITED_REQUEST), Set.copyOf(TRAIL));
        assertEquals(YES, ex.responseHeader(GLOBAL_RESPONSE));
        assertEquals(YES, ex.responseHeader(AUDITED_RESPONSE));
    }

    /**
     * §4.4 and §6.7.1: the mapped response of a locator's failure goes through the response filters. A dynamic
     * locator fails before any resource method is matched, so only the global ones apply.
     */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/typed/failing", "/object/failing"})
    void theGlobalResponseFiltersRunOnTheMappedResponseOfALocator(String path) {
        InMemoryExchange ex = get(path);
        assertEquals(409, ex.status(), ex::responseText);
        assertEquals("mapped: locator", ex.responseText());
        assertEquals(YES, ex.responseHeader(GLOBAL_RESPONSE));
        assertNull(ex.responseHeader(AUDITED_RESPONSE));
    }
}
