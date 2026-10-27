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

import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A {@link CassiniHttpExchange} held entirely in memory: the request is given up
 * front, the response is collected for assertions. Lets a test drive
 * {@code DefaultCassiniHttpAdapter.dispatch} end to end without opening a socket.
 */
final class InMemoryExchange implements CassiniHttpExchange {

    private final String method;
    private final URI uri;
    private final Map<String, List<String>> requestHeaders = new LinkedHashMap<>();
    private final byte[] body;
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private int status = -1;

    private InMemoryExchange(String method, String path, String contentType, byte[] body) {
        this(method, "127.0.0.1", path, contentType, body);
    }

    private InMemoryExchange(String method, String authority, String path, String contentType, byte[] body) {
        this.method = method;
        this.uri = URI.create("http://" + authority + path);
        this.body = body;
        if (contentType != null) requestHeaders.put("Content-Type", List.of(contentType));
        requestHeaders.put("Content-Length", List.of(String.valueOf(body == null ? 0 : body.length)));
    }

    /** A {@code POST} with the given {@code Content-Type} and UTF-8 body ({@code ""} = empty body). */
    static InMemoryExchange post(String path, String contentType, String body) {
        return new InMemoryExchange("POST", path, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /** A {@code PUT} with the given {@code Content-Type} and UTF-8 body. */
    static InMemoryExchange put(String path, String contentType, String body) {
        return new InMemoryExchange("PUT", path, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /** A {@code GET} with no body. */
    static InMemoryExchange get(String path) {
        return new InMemoryExchange("GET", path, null, null);
    }

    /** A {@code GET} with no body, addressed to {@code authority} ({@code host[:port]}). */
    static InMemoryExchange get(String authority, String path) {
        return new InMemoryExchange("GET", authority, path, null, null);
    }

    /** Any method, with an optional {@code Content-Type} and UTF-8 body ({@code null} = no body). */
    static InMemoryExchange request(String method, String path, String contentType, String body) {
        return new InMemoryExchange(method, path, contentType,
                body == null ? null : body.getBytes(StandardCharsets.UTF_8));
    }

    /** Adds a request header value. */
    InMemoryExchange header(String name, String value) {
        requestHeaders.computeIfAbsent(name, _ -> new java.util.ArrayList<>()).add(value);
        return this;
    }

    /** Replaces every value of a request header; null removes it. */
    InMemoryExchange replaceHeader(String name, String value) {
        if (value == null) requestHeaders.remove(name);
        else requestHeaders.put(name, new java.util.ArrayList<>(List.of(value)));
        return this;
    }

    private String contextPath = "";
    private boolean failingBody;

    /** Serves the request under an application prefix (e.g. {@code /app}). */
    InMemoryExchange contextPath(String prefix) {
        this.contextPath = prefix;
        return this;
    }

    /** A request whose body fails on the first read, as a connection cut mid-body does. */
    InMemoryExchange failingBody() {
        this.failingBody = true;
        return this;
    }

    @Override public String contextPath() { return contextPath; }

    /** The first value of a response header, or null. */
    String responseHeader(String name) {
        for (var e : responseHeaders.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
        }
        return null;
    }

    int status() { return status; }

    String responseText() { return responseBody.toString(StandardCharsets.UTF_8); }

    String responseContentType() {
        List<String> v = responseHeaders.get("Content-Type");
        return v == null || v.isEmpty() ? null : v.get(0);
    }

    @Override public String method() { return method; }
    @Override public URI requestUri() { return uri; }
    @Override public String requestUriRaw() { return uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()); }
    @Override public Map<String, List<String>> requestHeaders() { return requestHeaders; }
    @Override public InputStream requestBody() {
        if (failingBody) {
            return new InputStream() {
                @Override public int read() throws java.io.IOException {
                    throw new java.io.IOException("connection reset");
                }
            };
        }
        return body == null ? null : new ByteArrayInputStream(body);
    }
    @Override public void setStatus(int code) { this.status = code; }
    @Override public Map<String, List<String>> responseHeaders() { return responseHeaders; }
    @Override public OutputStream responseBody() { return responseBody; }

    /** How many times the dispatch handed the whole response body at once. */
    int wholeBodyWrites;

    @Override public void writeResponseBody(byte[] body) throws java.io.IOException {
        wholeBodyWrites++;
        responseBody.write(body);
    }
    @Override public SocketAddress remoteAddress() { return null; }
    @Override public boolean isSecure() { return false; }
    @Override public String authScheme() { return null; }
    @Override public Principal userPrincipal() { return null; }
    @Override public boolean isUserInRole(String role) { return false; }
    @Override public void setAttribute(String key, Object value) { attributes.put(key, value); }
    @Override public Object getAttribute(String key) { return attributes.get(key); }
}
