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

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.spi.http.CassiniStreamingSink;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.Principal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A {@link CassiniHttpExchange} over a Servlet request and response.
 *
 * <p>Who the caller is comes from the container: the principal, the authentication scheme and the roles are the
 * ones its security (a constraint, a Jakarta Security mechanism) established before this servlet ran. The response is
 * collected and written by {@link #commit()}, once the dispatch ended, unless the resource method streams.
 */
public final class ServletHttpExchange implements CassiniHttpExchange {

    private final HttpServletRequest request;
    private final HttpServletResponse response;
    private final String prefix;
    private int status = 200;
    private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    private final ByteArrayOutputStream body = new ByteArrayOutputStream();
    /** Thread-safe, as the SPI promises: the request's thread and the threads of an async response share it. */
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile boolean streaming;

    /**
     * @param request  the Servlet request
     * @param response the Servlet response
     */
    public ServletHttpExchange(HttpServletRequest request, HttpServletResponse response) {
        this.request = request;
        this.response = response;
        this.prefix = nullToEmpty(request.getContextPath()) + nullToEmpty(request.getServletPath());
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String method() {
        return request.getMethod();
    }

    @Override
    public String requestUriRaw() {
        String query = request.getQueryString();
        return query == null ? request.getRequestURI() : request.getRequestURI() + "?" + query;
    }

    @Override
    public URI requestUri() {
        try {
            return new URI(requestUriRaw());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("The container gave a request URI that is not a URI", e);
        }
    }

    @Override
    public Map<String, List<String>> requestHeaders() {
        Map<String, List<String>> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (String name : Collections.list(request.getHeaderNames())) {
            out.put(name, Collections.list(request.getHeaders(name)));
        }
        return out;
    }

    @Override
    public InputStream requestBody() {
        try {
            return request.getInputStream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The context path and the servlet mapping: what the application is mounted under. */
    @Override
    public String contextPath() {
        return prefix;
    }

    /** What follows the mount, as the client wrote it (not decoded). */
    @Override
    public String routingPath() {
        String path = request.getRequestURI();
        if (path == null || path.isEmpty()) {
            return "/";
        }
        if (!prefix.isEmpty() && path.startsWith(prefix)) {
            path = path.substring(prefix.length());
        }
        return path.isEmpty() ? "/" : path;
    }

    @Override
    public void setStatus(int code) {
        this.status = code;
    }

    @Override
    public Map<String, List<String>> responseHeaders() {
        return responseHeaders;
    }

    @Override
    public OutputStream responseBody() {
        return body;
    }

    @Override
    public SocketAddress remoteAddress() {
        return InetSocketAddress.createUnresolved(request.getRemoteAddr(), request.getRemotePort());
    }

    @Override
    public boolean isSecure() {
        return request.isSecure();
    }

    @Override
    public String authScheme() {
        return request.getAuthType();
    }

    @Override
    public Principal userPrincipal() {
        return request.getUserPrincipal();
    }

    @Override
    public boolean isUserInRole(String role) {
        return request.isUserInRole(role);
    }

    /** A {@code null} value removes the attribute. */
    @Override
    public void setAttribute(String key, Object value) {
        if (value == null) {
            attributes.remove(key);
        } else {
            attributes.put(key, value);
        }
    }

    @Override
    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Writes the collected status, headers and body to the Servlet response; does nothing when the response was
     * streamed.
     *
     * @throws IOException when the container cannot take them
     */
    public void commit() throws IOException {
        if (streaming) {
            return;
        }
        response.setStatus(status);
        responseHeaders.forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
        if (body.size() > 0) {
            body.writeTo(response.getOutputStream());
        }
    }

    @Override
    public CassiniStreamingSink openForStreaming(int code, Map<String, List<String>> headers) {
        try {
            response.setStatus(code);
            headers.forEach((name, values) -> values.forEach(v -> response.addHeader(name, v)));
            OutputStream out = response.getOutputStream();
            response.flushBuffer();
            streaming = true;
            return new Sink(out);
        } catch (IOException _) {
            return null;
        }
    }

    private final class Sink implements CassiniStreamingSink {
        private final OutputStream out;
        private volatile boolean open = true;

        Sink(OutputStream out) {
            this.out = out;
        }

        @Override
        public CompletionStage<Void> writeChunk(byte[] data) {
            try {
                out.write(data);
                return CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletionStage<Void> flush() {
            try {
                out.flush();
                response.flushBuffer();
                return CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public CompletionStage<Void> close() {
            open = false;
            try {
                out.close();
                return CompletableFuture.completedFuture(null);
            } catch (IOException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public boolean isOpen() {
            return open;
        }
    }
}
