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

import io.vidocq.cassini.examples.chappe.resource.AsyncResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §8.2 lifecycle callbacks end-to-end on the Chappe transport:
 * {@code CompletionCallback} on normal async completion, and
 * {@code ConnectionCallback} fired through the Chappe client-disconnect
 * probe when the client goes away while the response is suspended.
 */
class AsyncDisconnectTest {

    private static ExampleServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new ExampleServer();
    }

    @AfterAll
    static void close() throws Exception {
        server.close();
    }

    @Test
    void asyncResumeFromAnotherThreadCompletes() throws Exception {
        var events = AsyncResource.reset();
        var http = HttpClient.newHttpClient();
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/async/echo/ping")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode());
        assertEquals("async:ping", resp.body());
        assertTrue(events.completed().await(3, TimeUnit.SECONDS),
                "CompletionCallback should fire after the async response is delivered");
    }

    @Test
    void connectionCallbackFiresWhenClientDisconnectsWhileSuspended() throws Exception {
        var events = AsyncResource.reset();

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.getOutputStream().write(
                    ("GET /async/suspend HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            Thread.sleep(400); // let the request suspend and the probe arm
        } // client gone

        assertTrue(events.disconnected().await(3, TimeUnit.SECONDS),
                "ConnectionCallback.onDisconnect should fire when the client closes");
        assertTrue(events.completed().await(3, TimeUnit.SECONDS),
                "the suspended response should be released (CompletionCallback fired) "
                        + "instead of waiting for its timeout");
    }
}
