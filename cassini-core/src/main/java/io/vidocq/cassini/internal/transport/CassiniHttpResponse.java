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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transport-neutral HTTP response (no transport dependency).
 *
 * <p>Built by the {@code Invoker} from a JAX-RS resource method result,
 * then written to a {@link io.vidocq.cassini.spi.http.CassiniHttpExchange}
 * by the adapter (Chappe, JDK HttpServer, ...).
 *
 * <p>The body is a pre-serialised {@code byte[]}. For streaming (SSE,
 * StreamingOutput), a dedicated type will be added later (M2i).
 */
public final class CassiniHttpResponse {

    public static final class Builder {
        private int status = 200;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();
        private byte[] body = new byte[0];

        public Builder status(int s) { this.status = s; return this; }
        public Builder header(String name, String value) {
            headers.computeIfAbsent(name, _ -> new java.util.ArrayList<>()).add(value);
            return this;
        }
        public Builder body(byte[] data) {
            this.body = data == null ? new byte[0] : data;
            return this;
        }
        public CassiniHttpResponse build() { return new CassiniHttpResponse(status, headers, body); }
    }

    public static Builder builder() { return new Builder(); }

    public static CassiniHttpResponse status(int code) {
        return new Builder().status(code).build();
    }

    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;

    private CassiniHttpResponse(int status, Map<String, List<String>> headers, byte[] body) {
        this.status = status;
        this.headers = headers;
        this.body = body;
    }

    public int status() { return status; }
    public Map<String, List<String>> headers() { return headers; }
    public byte[] body() { return body; }

    /**
     * Copies this result to a {@link io.vidocq.cassini.spi.http.CassiniHttpExchange} —
     * used by transport adapters to write the final HTTP response.
     */
    public void writeTo(io.vidocq.cassini.spi.http.CassiniHttpExchange exchange) throws java.io.IOException {
        exchange.setStatus(status);
        Map<String, List<String>> respHeaders = exchange.responseHeaders();
        for (var e : headers.entrySet()) {
            respHeaders.put(e.getKey(), new java.util.ArrayList<>(e.getValue()));
        }
        if (body != null && body.length > 0) {
            exchange.responseBody().write(body);
        }
    }
}
