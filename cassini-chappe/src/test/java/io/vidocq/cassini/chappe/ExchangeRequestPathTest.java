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
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * What the Chappe exchange keeps instead of copying or parsing: the raw request target as a
 * string, the response header map, and the entity buffer with its unused tail.
 */
class ExchangeRequestPathTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void theRawUriOfAnOriginFormTargetHasTheSchemeAndHost() {
        var exchange = new ChappeHttpExchange(request("/things", "a=%20b", Headers.of("Host", "example.org:8080"), true));
        assertEquals("https://example.org:8080/things?a=%20b", exchange.requestUriRaw());
    }

    @Test
    void theRawUriOfAnAbsoluteTargetIsTheTarget() {
        var exchange = new ChappeHttpExchange(request("HTTP://proxy.example.org/x", null, Headers.of("Host", "h"), false));
        assertEquals("HTTP://proxy.example.org/x", exchange.requestUriRaw());
    }

    @Test
    void theRawUriWithoutHostIsTheTarget() {
        var exchange = new ChappeHttpExchange(request("things", null, Headers.empty(), false));
        assertEquals("things", exchange.requestUriRaw());
        var withHost = new ChappeHttpExchange(request("things", null, Headers.of("Host", "h"), false));
        assertEquals("http://h/things", withHost.requestUriRaw());
        var emptyHost = new ChappeHttpExchange(request("/t", null, Headers.of("Host", ""), false));
        assertEquals("/t", emptyHost.requestUriRaw());
    }

    @Test
    void theRawUriDoesNotBuildTheUri() {
        var exchange = new ChappeHttpExchange(request("/things", null, Headers.of("Host", "h"), false));
        exchange.requestUriRaw();
        assertEquals(0, uriCalls);
    }

    @Test
    void responseHeadersHandedOverAreKept() {
        var exchange = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", new ArrayList<>(List.of("text/plain")));
        exchange.putResponseHeaders(headers);
        assertSame(headers, exchange.collectedHeaders());
    }

    @Test
    void responseHeadersHandedOverAfterOthersAreAdded() {
        var exchange = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        exchange.responseHeaders().put("X-A", List.of("1"));
        exchange.putResponseHeaders(Map.of("X-B", List.of("2")));
        assertEquals(Map.of("X-A", List.of("1"), "X-B", List.of("2")), exchange.collectedHeaders());
    }

    @Test
    void aBodyRangeIsKeptWithItsArray() throws IOException {
        var exchange = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        byte[] buffer = bytes("hello-----");
        exchange.writeResponseBody(buffer, 0, 5);
        Body body = exchange.collectedChappeBody();
        assertEquals(5, body.contentLength());
        assertArrayEquals(bytes("hello"), body.asInputStream().readAllBytes());
        assertArrayEquals(bytes("hello"), published(body));
        assertArrayEquals(bytes("hello"), exchange.collectedBody());
    }

    @Test
    void aWholeArrayBecomesAnArrayBody() throws IOException {
        var exchange = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        byte[] buffer = bytes("hello");
        exchange.writeResponseBody(buffer, 0, 5);
        assertSame(buffer, exchange.collectedBody());
        assertArrayEquals(buffer, exchange.collectedChappeBody().asInputStream().readAllBytes());
    }

    @Test
    void writesAroundARangeKeepTheirOrder() throws IOException {
        var exchange = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        exchange.writeResponseBody(bytes("xab--"), 1, 2);
        exchange.responseBody().write(bytes("cd"));
        assertArrayEquals(bytes("abcd"), exchange.collectedChappeBody().asInputStream().readAllBytes());
    }

    @Test
    void emptyBodiesAreEmpty() {
        var nothing = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        assertEquals(0, nothing.collectedChappeBody().contentLength());
        var emptyRange = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        emptyRange.writeResponseBody(new byte[8], 0, 0);
        assertEquals(0, emptyRange.collectedChappeBody().contentLength());
    }

    @Test
    void aRangeOutsideTheArrayIsRefused() {
        var exchange = new ChappeHttpExchange(request("/", null, Headers.empty(), false));
        byte[] buffer = new byte[4];
        assertThrows(IndexOutOfBoundsException.class, () -> exchange.writeResponseBody(buffer, 2, 3));
    }

    /** The bytes the body publishes; a cancelled subscription publishes nothing more. */
    private static byte[] published(Body body) {
        var out = new java.io.ByteArrayOutputStream();
        body.asPublisher().subscribe(new Flow.Subscriber<>() {
            @Override public void onSubscribe(Flow.Subscription s) {
                s.request(1);
                s.request(1);
                s.cancel();
            }
            @Override public void onNext(ByteBuffer item) {
                byte[] b = new byte[item.remaining()];
                item.get(b);
                out.writeBytes(b);
            }
            @Override public void onError(Throwable throwable) { throw new AssertionError(throwable); }
            @Override public void onComplete() { /* nothing to release */ }
        });
        return out.toByteArray();
    }

    private int uriCalls;

    private Request request(String path, String query, Headers headers, boolean secure) {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() {
                uriCalls++;
                return URI.create(path);
            }
            @Override public String path() { return path; }
            @Override public String query() { return query; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return headers; }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
            @Override public boolean isSecure() { return secure; }
        };
    }
}
