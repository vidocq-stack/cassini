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
package io.vidocq.cassini.examples.chappe;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreetingResourceTest {

    private static ExampleServer server;
    private static HttpClient http;

    @BeforeAll
    static void start() throws Exception {
        server = new ExampleServer();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void close() throws Exception {
        server.close();
    }

    @Test
    void helloWorld() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/greetings")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("Hello, World!", resp.body());
    }

    @Test
    void helloName() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/greetings/Alice")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("Hello, Alice!", resp.body());
    }

    @Test
    void contentTypeIsTextPlain() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/greetings")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertTrue(resp.headers().firstValue("content-type")
                .map(ct -> ct.startsWith("text/plain")).orElse(false),
                "Expected text/plain content-type");
    }
}
