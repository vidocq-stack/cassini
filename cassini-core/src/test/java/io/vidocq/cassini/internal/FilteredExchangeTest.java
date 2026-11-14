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

import io.vidocq.cassini.internal.context.CassiniUriInfo;
import io.vidocq.cassini.internal.filter.CassiniRequestContext;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * CASSINI-056: the exchange the rest of a request reads once its request filters ran
 * ({@link CassiniRequestContext#filteredExchange()}) gives what they changed, and the transport's exchange for the
 * rest, the response included.
 */
class FilteredExchangeTest {

    private static final String TEXT = "text/plain";
    private static final String X_LIST = "X-List";
    private static final String X_ADDED = "X-Added";
    private static final String PATH = "/api/things/7?q=1";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static CassiniRequestContext context(InMemoryExchange exchange) {
        return CassiniRequestContext.create(exchange, new CassiniUriInfo(exchange, "/api", Map.of()));
    }

    private static InMemoryExchange post() {
        return InMemoryExchange.request("POST", PATH, TEXT, "body").header(X_LIST, "a").contextPath("/api");
    }

    private static String text(CassiniHttpExchange exchange) throws IOException {
        return new String(exchange.requestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void anUntouchedRequestKeepsItsExchange() {
        InMemoryExchange exchange = post();
        assertSame(exchange, context(exchange).filteredExchange());
        CassiniRequestContext sameMethod = context(exchange);
        sameMethod.setMethod("POST");
        assertSame(exchange, sameMethod.filteredExchange(), "the method a filter set is the one it had");
    }

    @Test
    void itGivesWhatTheFiltersChanged() throws IOException {
        InMemoryExchange exchange = post();
        CassiniRequestContext context = context(exchange);
        context.getHeaders().putSingle(X_ADDED, "yes");
        context.setEntityStream(new ByteArrayInputStream("changed".getBytes(StandardCharsets.UTF_8)));
        context.setMethod("PUT");
        context.setRequestUri(URI.create("http://127.0.0.1/api/other?q=2"));
        CassiniHttpExchange filtered = context.filteredExchange();

        assertEquals("PUT", filtered.method());
        assertEquals(URI.create("http://127.0.0.1/api/other?q=2"), filtered.requestUri());
        assertEquals(exchange.requestUriRaw(), filtered.requestUriRaw(), "the base URI stays the transport's");
        assertEquals("/other", filtered.routingPath());
        assertEquals(Map.of("q", List.of("2")), filtered.queryParams());
        assertEquals("yes", filtered.firstHeader("x-added"));
        assertEquals(List.of("a"), filtered.headers(X_LIST));
        assertEquals(List.of(), filtered.headers("X-Absent"));
        assertEquals(List.of("yes"), filtered.requestHeaders().get(X_ADDED));
        assertEquals(-1L, filtered.contentLength(), "the length of a filter's stream is unknown");
        assertEquals("changed", text(filtered));
        assertEquals("/api", filtered.contextPath());
    }

    @Test
    void itGivesTheTransportsExchangeForTheRest() throws IOException {
        InMemoryExchange exchange = post();
        CassiniRequestContext context = context(exchange);
        context.setEntityStream(new ByteArrayInputStream(new byte[0]));
        CassiniHttpExchange filtered = context.filteredExchange();

        assertEquals("POST", filtered.method());
        assertEquals(exchange.requestUri(), filtered.requestUri());
        assertEquals("/things/7", filtered.routingPath());
        assertEquals(exchange.queryParams(), filtered.queryParams());
        assertEquals("a", filtered.firstHeader(X_LIST));
        assertEquals(List.of("a"), filtered.headers(X_LIST));
        assertSame(exchange.requestHeaders(), filtered.requestHeaders());
        assertNull(filtered.remoteAddress());
        assertEquals(exchange.isSecure(), filtered.isSecure());
        assertNull(filtered.authScheme());
        assertNull(filtered.userPrincipal());
        assertEquals(exchange.isUserInRole("admin"), filtered.isUserInRole("admin"));
        filtered.setAttribute("k", "v");
        assertEquals("v", exchange.getAttribute("k"));
        assertEquals("v", filtered.getAttribute("k"));
        assertEquals(exchange.onClientDisconnect(() -> { }), filtered.onClientDisconnect(() -> { }));
        assertSame(exchange.responseHeaders(), filtered.responseHeaders());
        filtered.setStatus(201);
        filtered.putResponseHeaders(Map.of("X-Out", List.of("1")));
        filtered.writeResponseBody(new byte[]{'a'});
        filtered.writeResponseBody(new byte[]{'b', 'c'}, 1, 1);
        filtered.responseBody().write('d');
        assertEquals(201, exchange.status());
        assertEquals("1", exchange.responseHeader("X-Out"));
        assertEquals("acd", exchange.responseText());
        assertNull(filtered.openForStreaming(200, Map.of()));
    }

    @Test
    void readingTheHeadersKeepsTheTransportsEntity() throws IOException {
        InMemoryExchange exchange = post();
        CassiniRequestContext context = context(exchange);
        assertEquals("a", context.getHeaderString(X_LIST));
        CassiniHttpExchange filtered = context.filteredExchange();
        assertEquals(4L, filtered.contentLength(), "the transport's length for the transport's stream");
        assertEquals("body", text(filtered));
    }
}
