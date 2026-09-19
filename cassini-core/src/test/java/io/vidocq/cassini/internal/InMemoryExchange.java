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
        this.method = method;
        this.uri = URI.create("http://127.0.0.1" + path);
        this.body = body;
        if (contentType != null) requestHeaders.put("Content-Type", List.of(contentType));
        requestHeaders.put("Content-Length", List.of(String.valueOf(body == null ? 0 : body.length)));
    }

    /** A {@code POST} with the given {@code Content-Type} and UTF-8 body ({@code ""} = empty body). */
    static InMemoryExchange post(String path, String contentType, String body) {
        return new InMemoryExchange("POST", path, contentType, body.getBytes(StandardCharsets.UTF_8));
    }

    /** A {@code GET} with no body. */
    static InMemoryExchange get(String path) {
        return new InMemoryExchange("GET", path, null, null);
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
    @Override public InputStream requestBody() { return body == null ? null : new ByteArrayInputStream(body); }
    @Override public void setStatus(int code) { this.status = code; }
    @Override public Map<String, List<String>> responseHeaders() { return responseHeaders; }
    @Override public OutputStream responseBody() { return responseBody; }
    @Override public SocketAddress remoteAddress() { return null; }
    @Override public boolean isSecure() { return false; }
    @Override public String authScheme() { return null; }
    @Override public Principal userPrincipal() { return null; }
    @Override public boolean isUserInRole(String role) { return false; }
    @Override public void setAttribute(String key, Object value) { attributes.put(key, value); }
    @Override public Object getAttribute(String key) { return attributes.get(key); }
}
