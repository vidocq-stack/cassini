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
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * §3.7.2: among the methods of one path and verb, the most specific {@code @Consumes} that matches
 * the request Content-Type wins, then the most specific {@code @Produces} for the Accept header.
 */
class RouteNegotiationTest {

    static class Handlers {
        public void anyType() { /* routing fixture: never invoked */ }
        public void anySubtype() { /* routing fixture: never invoked */ }
        public void exact() { /* routing fixture: never invoked */ }
    }

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static MatchResult consuming(String verb, String methodName, String consumes) throws Exception {
        var m = Handlers.class.getDeclaredMethod(methodName);
        return new MatchResult(new ResourceMethod(Handlers.class, m, verb, UriTemplate.compile("/things"),
                Set.of(), Set.of(consumes)), Map.of());
    }

    private static MatchResult producing(String methodName, String produces) throws Exception {
        var m = Handlers.class.getDeclaredMethod(methodName);
        return new MatchResult(new ResourceMethod(Handlers.class, m, "GET", UriTemplate.compile("/things"),
                Set.of(produces), Set.of()), Map.of());
    }

    private static List<MatchResult> consumers() throws Exception {
        return List.of(consuming("POST", "anyType", "*/*"), consuming("POST", "anySubtype", "text/*"),
                consuming("POST", "exact", "text/plain"));
    }

    private static String picked(List<MatchResult> candidates, InMemoryExchange request) {
        return RouteNegotiation.pickBestMatch(candidates, request).method().javaMethod().getName();
    }

    @Test
    void the_most_specific_consumes_that_matches_the_content_type_wins() throws Exception {
        assertEquals("exact", picked(consumers(), InMemoryExchange.post("/things", "text/plain", "x")));
        assertEquals("anySubtype", picked(consumers(), InMemoryExchange.post("/things", "text/html", "x")));
        assertEquals("anyType", picked(consumers(), InMemoryExchange.post("/things", "application/json", "x")));
    }

    @Test
    void the_most_specific_produces_wins_when_the_client_accepts_anything() throws Exception {
        List<MatchResult> candidates = List.of(producing("anyType", "*/*"), producing("anySubtype", "text/*"),
                producing("exact", "text/plain"));
        assertEquals("exact", picked(candidates, InMemoryExchange.get("/things").header("Accept", "*/*")));
    }
}
