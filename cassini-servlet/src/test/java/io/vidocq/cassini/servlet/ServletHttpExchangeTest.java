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
package io.vidocq.cassini.servlet;

import io.vidocq.cassini.spi.http.CassiniStreamingSink;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServletHttpExchangeTest {

    private final Fakes.Req req = new Fakes.Req();
    private final Fakes.Res res = new Fakes.Res();

    private ServletHttpExchange exchange() {
        return new ServletHttpExchange(req.proxy(), res.proxy());
    }

    @Test
    void theRequestLineAndTheHeadersAreRead() {
        req.method = "POST";
        req.requestUri = "/app/api/items/a%2Fb";
        req.queryString = "q=1&q=2";
        req.header("Content-Type", "application/json").header("X-Many", "a").header("X-Many", "b");
        ServletHttpExchange e = exchange();
        assertEquals("POST", e.method());
        assertEquals("/app/api/items/a%2Fb?q=1&q=2", e.requestUriRaw());
        assertEquals("/app/api/items/a%2Fb", e.requestUri().getRawPath());
        assertEquals("application/json", e.firstHeader("content-type"));
        assertEquals(List.of("a", "b"), e.headers("X-MANY"));
        assertEquals(List.of("1", "2"), e.queryParams().get("q"));
    }

    @Test
    void theRoutingPathIsWhatFollowsTheServletMappingAndIsNotDecoded() {
        req.contextPath = "/app";
        req.servletPath = "/api";
        req.requestUri = "/app/api/items/a%2Fb";
        ServletHttpExchange e = exchange();
        assertEquals("/app/api", e.contextPath());
        assertEquals("/items/a%2Fb", e.routingPath());
    }

    @Test
    void anEmptyRoutingPathIsTheRoot() {
        req.contextPath = "/app";
        req.servletPath = "/api";
        req.requestUri = "/app/api";
        assertEquals("/", exchange().routingPath());
    }

    @Test
    void theContainerAnswersWhoTheCallerIs() {
        Principal alice = () -> "alice";
        req.principal = alice;
        req.roles = Set.of("admin");
        req.authType = "BASIC";
        req.secure = true;
        ServletHttpExchange e = exchange();
        assertEquals(alice, e.userPrincipal());
        assertTrue(e.isUserInRole("admin"));
        assertFalse(e.isUserInRole("guest"));
        assertEquals("BASIC", e.authScheme());
        assertTrue(e.isSecure());
        assertNotNull(e.remoteAddress());
    }

    @Test
    void anUnauthenticatedCallerHasNoPrincipalNorScheme() {
        ServletHttpExchange e = exchange();
        assertNull(e.userPrincipal());
        assertNull(e.authScheme());
        assertFalse(e.isUserInRole("admin"));
    }

    @Test
    void attributesAreKeptOnTheExchangeNotOnTheRequest() {
        ServletHttpExchange e = exchange();
        e.setAttribute("k", "v");
        assertEquals("v", e.getAttribute("k"));
        e.setAttribute("k", null);
        assertNull(e.getAttribute("k"));
        assertTrue(req.attributes.isEmpty());
    }

    @Test
    void theBodyReadsFromTheRequest() throws IOException {
        req.body = "hello".getBytes(StandardCharsets.UTF_8);
        assertEquals("hello", new String(exchange().requestBody().readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void nothingReachesTheResponseBeforeCommit() throws IOException {
        ServletHttpExchange e = exchange();
        e.setStatus(201);
        e.responseHeaders().put("X-A", List.of("1", "2"));
        e.responseBody().write("body".getBytes(StandardCharsets.UTF_8));
        assertFalse(res.committed);
        e.commit();
        assertEquals(201, res.status);
        assertEquals(Map.of("X-A", List.of("1", "2")), res.headers);
        assertEquals("body", res.body.toString(StandardCharsets.UTF_8));
    }

    @Test
    void streamingSendsTheHeadersAtOnceThenTheChunks() throws Exception {
        ServletHttpExchange e = exchange();
        CassiniStreamingSink sink = e.openForStreaming(200, Map.of("Content-Type", List.of("text/event-stream")));
        assertNotNull(sink);
        assertTrue(res.committed);
        assertEquals(List.of("text/event-stream"), res.headers.get("Content-Type"));
        sink.writeChunk("data: 1\n\n".getBytes(StandardCharsets.UTF_8)).toCompletableFuture().get();
        sink.flush().toCompletableFuture().get();
        assertEquals("data: 1\n\n", res.body.toString(StandardCharsets.UTF_8));
        assertTrue(sink.isOpen());
        sink.close().toCompletableFuture().get();
        assertFalse(sink.isOpen());
    }
}
