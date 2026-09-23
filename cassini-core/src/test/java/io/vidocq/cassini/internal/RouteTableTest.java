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

import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.cassini.spi.http.RouteDescription;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cassini#41: a host reads the resolved route table through the public
 * {@link CassiniStack#routes()} façade, in match order, as plain values.
 */
class RouteTableTest {

    static final AtomicInteger RESOURCES_CREATED = new AtomicInteger();
    static final AtomicInteger PROVIDERS_CREATED = new AtomicInteger();

    @BeforeEach
    void reset() {
        RESOURCES_CREATED.set(0);
        PROVIDERS_CREATED.set(0);
    }

    // ---------------------------------------------------------------- fixtures

    @Path("/tasks")
    public static class TaskResource {
        public TaskResource() { RESOURCES_CREATED.incrementAndGet(); }

        @GET
        @Produces(MediaType.APPLICATION_JSON)
        public String list() { return "[]"; }

        @POST
        @Consumes(MediaType.APPLICATION_JSON)
        public void create(String body) {}

        @GET
        @Path("{id}")
        @Produces(MediaType.APPLICATION_JSON)
        public String one(@PathParam("id") String id) { return id; }

        @GET
        @Path("count")
        public String count() { return "0"; }

        /** Returns Object: the target class is only known at request time. */
        @Path("any")
        public Object any() { return new Comment(); }

        /** Returns a scannable class: its methods are resolved at boot. */
        @Path("comments")
        public Comment comments() { return new Comment(); }
    }

    public static class Comment {
        @GET
        public String text() { return "c"; }
    }

    @Provider
    public static class AuditFilter implements ContainerRequestFilter {
        public AuditFilter() { PROVIDERS_CREATED.incrementAndGet(); }

        @Override
        public void filter(ContainerRequestContext ctx) {}
    }

    private static CassiniStack stack() {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(TaskResource.class, AuditFilter.class);
            }
        }).build();
    }

    // ------------------------------------------------------------------- tests

    @Test
    void lists_every_route_in_match_order() {
        List<RouteDescription> routes = stack().routes();

        // §3.7.2: most literal characters first, so /tasks/{id} (7) precedes /tasks (6).
        // GET and POST /tasks tie; their relative order follows reflection and is not asserted.
        assertEquals(List.of("/tasks/comments", "/tasks/count", "/tasks/any", "/tasks/{id}", "/tasks", "/tasks"),
                routes.stream().map(RouteDescription::path).toList());
        assertEquals(Set.of("GET", "POST"), Set.of(routes.get(4).httpMethod(), routes.get(5).httpMethod()));
    }

    @Test
    void describes_a_route_with_plain_values() {
        RouteDescription one = stack().routes().stream()
                .filter(r -> r.path().equals("/tasks/{id}"))
                .findFirst().orElseThrow();

        assertEquals(new RouteDescription("GET", "/tasks/{id}", TaskResource.class.getName(), "one",
                Set.of(MediaType.APPLICATION_JSON), Set.of()), one);
    }

    @Test
    void a_route_reached_through_a_locator_names_the_sub_resource_method() {
        RouteDescription comments = stack().routes().stream()
                .filter(r -> r.path().equals("/tasks/comments"))
                .findFirst().orElseThrow();

        assertEquals("GET", comments.httpMethod());
        assertEquals(Comment.class.getName(), comments.resourceClass());
        assertEquals("text", comments.methodName());
    }

    @Test
    void a_sub_resource_locator_keeps_its_place_with_an_empty_method() {
        List<RouteDescription> any = stack().routes().stream()
                .filter(r -> r.path().startsWith("/tasks/any"))
                .toList();

        assertEquals(1, any.size(), "one entry per locator, not one per internal catch-all variant");
        assertEquals("", any.getFirst().httpMethod());
        assertEquals(TaskResource.class.getName(), any.getFirst().resourceClass());
        assertEquals("any", any.getFirst().methodName());
    }

    /** The example of usage.adoc#route-table. */
    @Path("/users")
    public static class UsersResource {
        @GET @Produces(MediaType.APPLICATION_JSON)
        public String list() { return "[]"; }

        @Path("/{id}")
        public UserResource user(@PathParam("id") long id) { return new UserResource(); }
    }

    public static class UserResource {
        @GET @Produces(MediaType.APPLICATION_JSON)
        public String get() { return "{}"; }

        @jakarta.ws.rs.PUT @Consumes(MediaType.APPLICATION_JSON)
        public void update(String body) {}
    }

    @Test
    void the_documented_example_holds() {
        List<RouteDescription> routes = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(UsersResource.class);
            }
        }).build().routes();

        assertEquals(List.of("/users/{id}", "/users/{id}", "/users"),
                routes.stream().map(RouteDescription::path).toList());
        assertEquals(Set.of("GET " + UserResource.class.getName() + "#get",
                        "PUT " + UserResource.class.getName() + "#update"),
                Set.of(describe(routes.get(0)), describe(routes.get(1))));
        assertEquals("GET " + UsersResource.class.getName() + "#list", describe(routes.get(2)));
    }

    private static String describe(RouteDescription r) {
        return r.httpMethod() + " " + r.resourceClass() + "#" + r.methodName();
    }

    @Test
    void the_table_is_immutable() {
        List<RouteDescription> routes = stack().routes();

        assertThrows(UnsupportedOperationException.class, routes::clear);
        assertThrows(UnsupportedOperationException.class, () -> routes.getFirst().produces().clear());
    }

    @Test
    void reading_the_table_creates_no_instance() {
        CassiniStack stack = stack();
        int resources = RESOURCES_CREATED.get();
        int providers = PROVIDERS_CREATED.get();

        stack.routes();
        stack.routes();

        assertEquals(0, resources, "building the stack creates no resource instance");
        assertEquals(resources, RESOURCES_CREATED.get());
        assertEquals(providers, PROVIDERS_CREATED.get());
    }
}
