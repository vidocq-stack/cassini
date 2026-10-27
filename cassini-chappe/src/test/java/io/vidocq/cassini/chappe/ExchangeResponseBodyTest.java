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
package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Cassini hands the whole response entity to the exchange at once. The Chappe exchange copied it
 * into a growing ByteArrayOutputStream, then copied that again for Chappe's Body: two copies of
 * every response, and the growth copies of the stream on top for a large one. It now keeps the
 * array it is given.
 */
class ExchangeResponseBodyTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theWholeBodyIsKeptAsItIsGiven() {
        var exchange = new ChappeHttpExchange(request());
        byte[] body = bytes("[{\"id\":1}]");
        exchange.writeResponseBody(body);
        assertSame(body, exchange.collectedBody());
    }

    @Test
    void writesAfterTheWholeBodyFollowIt() throws IOException {
        var exchange = new ChappeHttpExchange(request());
        exchange.writeResponseBody(bytes("ab"));
        exchange.responseBody().write(bytes("cd"));
        exchange.writeResponseBody(bytes("ef"));
        assertArrayEquals(bytes("abcdef"), exchange.collectedBody());
    }

    @Test
    void writesBeforeTheWholeBodyComeFirst() throws IOException {
        var exchange = new ChappeHttpExchange(request());
        exchange.responseBody().write(bytes("ab"));
        exchange.writeResponseBody(bytes("cd"));
        assertArrayEquals(bytes("abcd"), exchange.collectedBody());
    }

    private static Request request() {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create("/things"); }
            @Override public String path() { return "/things"; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return Headers.empty(); }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
        };
    }
}
