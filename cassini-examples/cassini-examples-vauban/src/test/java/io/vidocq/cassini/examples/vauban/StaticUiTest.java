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
package io.vidocq.cassini.examples.vauban;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that the composite handler serves the static UI on {@code /}
 * alongside the REST API on {@code /api/*}.
 */
class StaticUiTest {

    private static ExampleServer server;
    private static HttpClient http;

    @BeforeAll
    static void start() throws Exception {
        server = new ExampleServer();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void close() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rootServesIndexHtml() throws Exception {
        var resp = get("/");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                "Should be text/html, got: " + resp.headers().firstValue("Content-Type"));
        assertTrue(resp.body().contains("<title>Cassini"), "Should contain title");
    }

    @Test
    void servesStyleCss() throws Exception {
        var resp = get("/style.css");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        assertTrue(resp.body().contains(":root"), "CSS should contain :root");
    }

    @Test
    void servesAppJs() throws Exception {
        var resp = get("/app.js");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("javascript"));
        assertTrue(resp.body().contains("loadTodos"), "JS should contain loadTodos function");
    }

    @Test
    void unknownStaticReturns404() throws Exception {
        var resp = get("/does-not-exist.html");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void apiAndStaticCoexist() throws Exception {
        // The static content on /
        var html = get("/");
        assertEquals(200, html.statusCode());
        // The API on /api/todos
        var api = get("/api/todos");
        assertEquals(200, api.statusCode());
        assertTrue(api.headers().firstValue("Content-Type").orElse("").contains("json"));
    }
}
