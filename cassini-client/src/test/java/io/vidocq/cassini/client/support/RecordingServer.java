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
package io.vidocq.cassini.client.support;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link FakeHttpServer} that records the last request it received (method, URI,
 * headers, body) and answers through a {@link Responder}.
 */
public final class RecordingServer implements AutoCloseable {

    /** A request as the server saw it; {@link Headers} looks names up case-insensitively. */
    public record Request(String method, String uri, Headers headers, String body) {
        public String header(String name) { return headers.getFirst(name); }
    }

    /** Writes the reply for a recorded request. */
    @FunctionalInterface
    public interface Responder {
        void reply(HttpExchange exchange, Request request) throws IOException;
    }

    private final AtomicReference<Request> last = new AtomicReference<>();
    private final FakeHttpServer server;

    public RecordingServer(Responder responder) throws IOException {
        this.server = new FakeHttpServer(exchange -> {
            byte[] in;
            try (var is = exchange.getRequestBody()) { in = is.readAllBytes(); }
            Request request = new Request(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    exchange.getRequestHeaders(), new String(in, StandardCharsets.UTF_8));
            last.set(request);
            responder.reply(exchange, request);
        });
    }

    /** Replies {@code 200 text/plain} with {@code "<METHOD>:<request body>"}. */
    public static RecordingServer echo() throws IOException {
        return new RecordingServer((exchange, request) ->
                send(exchange, 200, Map.of("Content-Type", "text/plain"), request.method() + ":" + request.body()));
    }

    /** Replies with a fixed status, headers and body to every request. */
    public static RecordingServer fixed(int status, Map<String, String> headers, String body) throws IOException {
        return new RecordingServer((exchange, _) -> send(exchange, status, headers, body));
    }

    /** Sends a reply; an empty body or a HEAD request is sent without a body. */
    public static void send(HttpExchange exchange, int status, Map<String, String> headers, String body)
            throws IOException {
        headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || "HEAD".equals(exchange.getRequestMethod())) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) { os.write(bytes); }
    }

    public Request last() { return last.get(); }

    public String baseUrl() { return server.baseUrl(); }

    @Override public void close() { server.close(); }
}
