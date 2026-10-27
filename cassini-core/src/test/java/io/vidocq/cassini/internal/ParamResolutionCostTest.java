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

import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arguments of a resource method are resolved on every request, from the annotations of its
 * parameters (§3.2). They were read again on every request: eleven {@code getAnnotation} calls per
 * parameter, each through a lock of the JDK, and {@code Parameter.getAnnotations()}, which the
 * JDK parses again from the class file on every call, for the entity parameter. They are now read
 * once per method.
 */
class ParamResolutionCostTest {

    @Path("/things")
    public static class Things {
        @GET @Path("/{id}") public String get(@PathParam("id") long id) { return "{}"; }
        @GET public String list(@QueryParam("limit") @DefaultValue("10") int limit) { return "[]"; }
        @POST public String create(String body) { return body; }
        @PUT @Path("/{id}") public String update(@PathParam("id") long id, String body) { return body; }
        @GET @Path("/odd") public void odd(@Context UriInfo uri, @Suspended AsyncResponse async,
                @Encoded @QueryParam("q") String q, @DefaultValue("x") String noSource) { }
    }

    private final UriRouter router = new UriRouter(List.copyOf(RouteRegistry.discover(Things.class)));

    private ParamExtractor.ResolvedArgs resolve(String verb, String path, InMemoryExchange exchange) {
        MatchResult match = router.match(verb, path).orElseThrow();
        return ParamExtractor.resolve(match.method(), match, exchange);
    }

    @Test
    void theArgumentsAreResolvedAsBefore() {
        assertArrayEquals(new Object[] {42L}, resolve("GET", "/things/42", InMemoryExchange.get("/things/42")).args());
        assertArrayEquals(new Object[] {10}, resolve("GET", "/things", InMemoryExchange.get("/things")).args());
        assertArrayEquals(new Object[] {7}, resolve("GET", "/things", InMemoryExchange.get("/things?limit=7")).args());
        ParamExtractor.ResolvedArgs body = resolve("POST", "/things", InMemoryExchange.post("/things", "text/plain", "x"));
        assertEquals(0, body.bodyIndex());
        ParamExtractor.ResolvedArgs update = resolve("PUT", "/things/42", InMemoryExchange.put("/things/42", "text/plain", "x"));
        assertEquals(42L, update.args()[0]);
        assertEquals(1, update.bodyIndex());
    }

    @Test
    void theParametersOfAMethodAreReadOnce() throws NoSuchMethodException {
        Method update = Things.class.getMethod("update", long.class, String.class);
        MethodParameters params = MethodParameters.of(update);
        assertSame(params, MethodParameters.of(update));
        assertEquals(2, params.size());
        assertEquals(MethodParameters.Source.PATH, params.get(0).source());
        assertEquals("id", params.get(0).name());
        assertEquals(MethodParameters.Source.ENTITY, params.get(1).source());
        assertEquals(-1, params.suspended());
        assertEquals(-1, params.sseSink());
    }

    @Test
    void eachParameterIsClassifiedByItsAnnotations() throws NoSuchMethodException {
        Method odd = Things.class.getMethod("odd", UriInfo.class, AsyncResponse.class, String.class, String.class);
        MethodParameters params = MethodParameters.of(odd);
        assertEquals(MethodParameters.Source.CONTEXT, params.get(0).source());
        assertEquals(MethodParameters.Source.SUSPENDED, params.get(1).source());
        assertEquals(1, params.suspended());
        assertEquals(MethodParameters.Source.QUERY, params.get(2).source());
        assertEquals(true, params.get(2).encoded());
        assertEquals(MethodParameters.Source.NONE, params.get(3).source(), "a jakarta.ws.rs annotation rules out the entity");
        assertEquals("x", params.get(3).defaultValue());
        assertNull(params.get(0).defaultValue());
    }

    @Test
    void aPathParameterIsResolvedWithoutReadingItsAnnotationsAgain() {
        InMemoryExchange exchange = InMemoryExchange.get("/things/42");
        MatchResult match = router.match("GET", "/things/42").orElseThrow();
        long perCall = Allocations.bytesPerCall(() -> ParamExtractor.resolve(match.method(), match, exchange), 2_000, 10_000);
        assertTrue(perCall < 96, "resolving @PathParam(\"id\") long allocates " + perCall + " bytes per call");
    }

    @Test
    void theEntityParameterIsFoundWithoutParsingTheAnnotationsAgain() {
        // The JDK parses the annotations of every parameter of the method again on each
        // Parameter.getAnnotations(), when one of them carries any: here, the @PathParam.
        InMemoryExchange exchange = InMemoryExchange.put("/things/42", "text/plain", "x");
        MatchResult match = router.match("PUT", "/things/42").orElseThrow();
        long perCall = Allocations.bytesPerCall(() -> ParamExtractor.resolve(match.method(), match, exchange), 2_000, 10_000);
        assertTrue(perCall < 96, "finding the entity parameter allocates " + perCall + " bytes per call");
    }
}
