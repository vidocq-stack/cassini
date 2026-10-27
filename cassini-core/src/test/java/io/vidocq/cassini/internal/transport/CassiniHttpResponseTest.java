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
package io.vidocq.cassini.internal.transport;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** A response keeps the array its body was written into, unused tail included. */
class CassiniHttpResponseTest {

    private static final byte[] BUFFER = "hello-----".getBytes(StandardCharsets.UTF_8);

    @Test
    void aBodyRangeIsKeptAndCopiedOnlyWhenAskedForExactly() {
        CassiniHttpResponse r = CassiniHttpResponse.builder().body(BUFFER, 5).build();
        assertSame(BUFFER, r.bodyArray());
        assertEquals(5, r.bodyLength());
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), r.body());
    }

    @Test
    void aWholeArrayIsTheBody() {
        byte[] whole = {1, 2};
        assertSame(whole, CassiniHttpResponse.builder().body(whole).build().body());
        assertEquals(0, CassiniHttpResponse.builder().body(null).build().body().length);
        assertEquals(0, CassiniHttpResponse.builder().body(BUFFER, 3).body(null, 3).build().bodyLength());
    }

    @Test
    void writeToWritesTheRangeOnly() throws Exception {
        Exchange exchange = new Exchange();
        CassiniHttpResponse.builder().status(201).header("X-A", "1").body(BUFFER, 5).build().writeTo(exchange);
        assertEquals(201, exchange.status);
        assertEquals(List.of("1"), exchange.headers.get("X-A"));
        assertEquals("hello", exchange.body.toString(StandardCharsets.UTF_8));
        Exchange empty = new Exchange();
        CassiniHttpResponse.status(204).writeTo(empty);
        assertEquals(0, empty.body.size());
    }

    @Test
    void theExchangeDefaultsWriteTheRangeAndAddTheHeaders() throws Exception {
        Exchange exchange = new Exchange();
        exchange.responseHeaders().put("X-A", List.of("1"));
        exchange.putResponseHeaders(Map.of("X-B", List.of("2")));
        exchange.writeResponseBody(BUFFER, 0, 5);
        exchange.writeResponseBody(new byte[] {'!'}, 0, 1);
        assertEquals(Map.of("X-A", List.of("1"), "X-B", List.of("2")), exchange.headers);
        assertEquals("hello!", exchange.body.toString(StandardCharsets.UTF_8));
    }

    /** Collects what a response writes. */
    private static final class Exchange implements CassiniHttpExchange {
        int status;
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        @Override public String method() { return "GET"; }
        @Override public URI requestUri() { return URI.create("/"); }
        @Override public String requestUriRaw() { return "/"; }
        @Override public Map<String, List<String>> requestHeaders() { return Map.of(); }
        @Override public InputStream requestBody() { return InputStream.nullInputStream(); }
        @Override public void setStatus(int code) { status = code; }
        @Override public Map<String, List<String>> responseHeaders() { return headers; }
        @Override public OutputStream responseBody() { return body; }
        @Override public SocketAddress remoteAddress() { return null; }
        @Override public boolean isSecure() { return false; }
        @Override public String authScheme() { return null; }
        @Override public Principal userPrincipal() { return null; }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public void setAttribute(String key, Object value) { /* no attributes */ }
        @Override public Object getAttribute(String key) { return null; }
    }
}
