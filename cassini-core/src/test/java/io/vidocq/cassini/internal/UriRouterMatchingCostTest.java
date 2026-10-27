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

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Routing (§3.7.2) runs the template regexes on every request. It ran the regex of every route
 * before looking at the verb, so a {@code GET /todos/42} also matched the PUT and DELETE
 * templates and built their parameter maps, matched the winner a second time for the raw
 * parameters even when the path carries no matrix parameter, and ran a regex for the literal
 * templates that a string comparison decides.
 */
class UriRouterMatchingCostTest {

    @Path("/health")
    public static class HealthResource {
        @GET public String health() { return "ok"; }
    }

    @Path("/todos")
    public static class TodoResource {
        @GET public String list() { return "[]"; }
        @POST public String create() { return "{}"; }
        @GET @Path("/{id}") public String get(@PathParam("id") long id) { return "{}"; }
        @PUT @Path("/{id}") public String update(@PathParam("id") long id) { return "{}"; }
        @DELETE @Path("/{id}") public void delete(@PathParam("id") long id) { }
    }

    private final UriRouter router = new UriRouter(List.copyOf(
            RouteRegistry.discover(HealthResource.class, TodoResource.class)));

    @Test
    void routesTheTodoApi() {
        assertEquals("get", router.match("GET", "/todos/42").orElseThrow().method().javaMethod().getName());
        assertEquals(Map.of("id", List.of("42")), router.match("GET", "/todos/42").orElseThrow().pathParams());
        assertEquals("update", router.match("PUT", "/todos/42").orElseThrow().method().javaMethod().getName());
        assertEquals("list", router.match("GET", "/todos").orElseThrow().method().javaMethod().getName());
        assertEquals("health", router.match("GET", "/health").orElseThrow().method().javaMethod().getName());
        assertTrue(router.match("GET", "/nowhere").isEmpty());
        assertEquals(List.of("GET", "PUT", "DELETE"), router.methodsAllowedFor("/todos/7"));
    }

    @Test
    void matrixParametersStillReachTheRawParameters() {
        MatchResult m = router.match("GET", "/todos/42;v=1").orElseThrow();
        assertEquals(List.of("42"), m.pathParams().get("id"));
        assertEquals(List.of("42;v=1"), m.rawPathParams().get("id"));
    }

    @Test
    void aRequestMatchesOnlyTheTemplatesOfItsVerbAndOnlyOnce() {
        long perCall = Allocations.bytesPerCall(() -> router.matchAll("GET", "/todos/42"), 2_000, 10_000);
        assertTrue(perCall < 1_024, "matchAll allocates " + perCall
                + " bytes for GET /todos/42: every template is matched whatever the verb, and the winner twice");
    }

    @Test
    void aLiteralTemplateNeedsNoRegex() {
        long perCall = Allocations.bytesPerCall(() -> router.matchAll("GET", "/health"), 2_000, 10_000);
        assertTrue(perCall < 512, "matchAll allocates " + perCall + " bytes for GET /health: a regex runs for a literal path");
    }
}
