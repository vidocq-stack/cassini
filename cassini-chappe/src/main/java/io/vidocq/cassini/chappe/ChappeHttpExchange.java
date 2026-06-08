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

import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Chappe → {@link CassiniHttpExchange} adapter.
 *
 * <p>Reads lazily from {@link Request} and collects status/headers/body in memory;
 * the final Chappe {@link Response} is built by {@link ChappeHttpAdapter}.
 */
public final class ChappeHttpExchange implements CassiniHttpExchange {

    private final Request request;
    private final String contextPath;
    private int responseStatus = 200;
    private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
    private final Map<String, Object> attributes = new java.util.HashMap<>();

    public ChappeHttpExchange(Request request) {
        this(request, request.contextPath());
    }

    public ChappeHttpExchange(Request request, String contextPath) {
        this.request = request;
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    public Request chappeRequest() { return request; }
    public int collectedStatus() { return responseStatus; }
    public byte[] collectedBody() { return responseBody.toByteArray(); }
    public Map<String, List<String>> collectedHeaders() { return responseHeaders; }

    @Override public String method() {
        return request.method() == null ? "GET" : request.method().toString();
    }

    @Override public URI requestUri() { return request.uri(); }

    @Override public String requestUriRaw() {
        URI u = request.uri();
        return u == null ? request.path() : u.toString();
    }

    @Override public Map<String, List<String>> requestHeaders() {
        Map<String, List<String>> m = new LinkedHashMap<>();
        for (var e : request.headers()) {
            m.computeIfAbsent(e.name(), _ -> new ArrayList<>()).add(e.value());
        }
        return m;
    }

    @Override public InputStream requestBody() {
        var body = request.body();
        return body == null ? InputStream.nullInputStream() : body.asInputStream();
    }

    @Override public long contentLength() {
        var body = request.body();
        return body == null ? -1L : body.contentLength();
    }

    @Override public String contextPath() { return contextPath; }

    @Override public String routingPath() {
        // Chappe provides pathInfo(), which is already stripped of the contextPath.
        String pi = request.pathInfo();
        if (pi == null || pi.isEmpty()) return "/";
        return pi;
    }

    @Override public void setStatus(int code) { this.responseStatus = code; }

    @Override public Map<String, List<String>> responseHeaders() { return responseHeaders; }

    @Override public OutputStream responseBody() { return responseBody; }

    @Override public SocketAddress remoteAddress() { return null; }

    @Override public boolean isSecure() { return request.isSecure(); }

    @Override public String authScheme() { return null; }

    @Override public Principal userPrincipal() { return null; }

    @Override public boolean isUserInRole(String role) { return false; }

    @Override public void setAttribute(String key, Object value) { attributes.put(key, value); }
    @Override public Object getAttribute(String key) { return attributes.get(key); }
}
