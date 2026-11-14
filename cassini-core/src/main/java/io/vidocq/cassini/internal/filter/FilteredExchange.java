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
package io.vidocq.cassini.internal.filter;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.spi.http.CassiniStreamingSink;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * The exchange of a request as its request filters left it (Jakarta REST §6.2, CASSINI-056): the headers, the entity
 * stream, the method and the request URI of the filters' {@link CassiniRequestContext}, each where a filter read or
 * changed it, and the transport's exchange for everything else, the response included. The rest of the request (the
 * routing of an {@code Object} locator's sub-resource, the arguments and the entity of the resource method, the
 * {@code @Context} values) reads this exchange, so it sees what the filters changed.
 */
public final class FilteredExchange implements CassiniHttpExchange {

    private final CassiniHttpExchange delegate;
    /** The filters' copy of the headers, {@code null} when no filter asked for it. */
    private final MultivaluedMap<String, String> headers;
    /** The stream a filter set, {@code null} when none did. */
    private final InputStream body;
    /** The method a pre-matching filter set, {@code null} when none did. */
    private final String method;
    /** The request URI a pre-matching filter set, {@code null} when none did. */
    private final URI requestUri;

    FilteredExchange(CassiniHttpExchange delegate, MultivaluedMap<String, String> headers, InputStream body,
                     String method, URI requestUri) {
        this.delegate = delegate;
        this.headers = headers;
        this.body = body;
        this.method = method;
        this.requestUri = requestUri;
    }

    /**
     * §6.6.1: the routing path of a request URI a pre-matching filter set, without the context path; {@code path} when
     * no filter set one.
     */
    public static String routingPath(URI requestUri, String contextPath, String path) {
        if (requestUri == null) return path;
        String rerouted = requestUri.getRawPath() != null ? requestUri.getRawPath() : requestUri.getPath();
        if (rerouted == null) return "/";
        if (contextPath != null && !contextPath.isEmpty() && !"/".equals(contextPath)
                && rerouted.startsWith(contextPath)) {
            rerouted = rerouted.substring(contextPath.length());
            return rerouted.isEmpty() ? "/" : rerouted;
        }
        return rerouted;
    }

    @Override public String method() { return method != null ? method : delegate.method(); }

    @Override public URI requestUri() { return requestUri != null ? requestUri : delegate.requestUri(); }

    /** The transport's: the base URI of the application stays the same whatever request URI a filter sets. */
    @Override public String requestUriRaw() { return delegate.requestUriRaw(); }

    @Override public Map<String, List<String>> requestHeaders() {
        return headers != null ? headers : delegate.requestHeaders();
    }

    @Override public String firstHeader(String name) {
        return headers != null ? headers.getFirst(name) : delegate.firstHeader(name);
    }

    @Override public List<String> headers(String name) {
        if (headers == null) return delegate.headers(name);
        List<String> values = headers.get(name);
        return values == null ? List.of() : values;
    }

    @Override public Map<String, List<String>> queryParams() {
        return requestUri != null ? CassiniHttpExchange.super.queryParams() : delegate.queryParams();
    }

    @Override public InputStream requestBody() { return body != null ? body : delegate.requestBody(); }

    /** Unknown for a stream a filter set: the entity is read to its end. */
    @Override public long contentLength() { return body != null ? -1L : delegate.contentLength(); }

    @Override public String contextPath() { return delegate.contextPath(); }

    @Override public String routingPath() {
        return requestUri != null ? routingPath(requestUri, contextPath(), null) : delegate.routingPath();
    }

    @Override public void setStatus(int code) { delegate.setStatus(code); }

    @Override public Map<String, List<String>> responseHeaders() { return delegate.responseHeaders(); }

    @Override public OutputStream responseBody() { return delegate.responseBody(); }

    @Override public void writeResponseBody(byte[] bytes) throws IOException { delegate.writeResponseBody(bytes); }

    @Override public void writeResponseBody(byte[] bytes, int offset, int length) throws IOException {
        delegate.writeResponseBody(bytes, offset, length);
    }

    @Override public void putResponseHeaders(Map<String, List<String>> responseHeaders) {
        delegate.putResponseHeaders(responseHeaders);
    }

    @Override public SocketAddress remoteAddress() { return delegate.remoteAddress(); }

    @Override public boolean isSecure() { return delegate.isSecure(); }

    @Override public String authScheme() { return delegate.authScheme(); }

    @Override public Principal userPrincipal() { return delegate.userPrincipal(); }

    @Override public boolean isUserInRole(String role) { return delegate.isUserInRole(role); }

    @Override public void setAttribute(String key, Object value) { delegate.setAttribute(key, value); }

    @Override public Object getAttribute(String key) { return delegate.getAttribute(key); }

    @Override public boolean onClientDisconnect(Runnable callback) { return delegate.onClientDisconnect(callback); }

    @Override public CassiniStreamingSink openForStreaming(int status, Map<String, List<String>> responseHeaders) {
        return delegate.openForStreaming(status, responseHeaders);
    }
}
