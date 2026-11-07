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
package io.vidocq.cassini.it.weld;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Server;
import jakarta.ws.rs.core.Application;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The request context of Weld lives for the whole request and ends with it, whichever way the request
 * ends (CASSINI-007, cassini#116): the entity writers still see it, and a 404, an unmapped failure or a
 * response resumed from another thread do not leave it active on the thread.
 */
class WeldRequestScopeLifetimeTest {

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static WeldContainer container;
    private static Server server;
    private static String base;

    @BeforeAll
    static void start() throws Exception {
        container = new Weld().initialize();
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var stack = CassiniStack.builder().application(new Application() {}).build();
        server = Server.builder().host("127.0.0.1").port(port)
                .handler(new ChappeHttpAdapter(stack.adapter()))
                .build();
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stop() {
        if (server != null) {
            server.stop();
        }
        if (container != null) {
            container.close();
        }
    }

    private static HttpResponse<String> get(String path) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Runs a request and returns how many request contexts it started and ended. */
    private static int[] contexts(String path, int expectedStatus) throws Exception {
        int started = ScopeEvents.INITIALIZED.get();
        int ended = ScopeEvents.DESTROYED.get();
        HttpResponse<String> response = get(path);
        assertEquals(expectedStatus, response.statusCode(), path + ": " + response.body());
        return new int[] {ScopeEvents.INITIALIZED.get() - started, ScopeEvents.DESTROYED.get() - ended};
    }

    @Test
    void anEntityWriterStillSeesTheRequestScope() throws Exception {
        HttpResponse<String> response = get("/lifetime/stream");
        assertEquals(200, response.statusCode(), response.body());
        assertFalse(response.body().isBlank(), "the streamed entity is the request token");
    }

    @Test
    void theContextEndsWithTheRequestOfAStreamedEntity() throws Exception {
        assertEquals(1, contexts("/lifetime/stream", 200)[1]);
    }

    @Test
    void theContextEndsWhenNoRouteMatches() throws Exception {
        int[] counts = contexts("/lifetime/missing", 404);
        assertEquals(counts[0], counts[1], "every context that started has ended");
        assertEquals(1, counts[1]);
    }

    @Test
    void theContextEndsWhenAnUnmappedFailureEndsInA500() throws Exception {
        int[] counts = contexts("/lifetime/boom", 500);
        assertEquals(counts[0], counts[1], "every context that started has ended");
        assertEquals(1, counts[1]);
    }

    @Test
    void theContextEndsOnceForAResponseResumedFromAnotherThread() throws Exception {
        int[] counts = contexts("/lifetime/later", 200);
        assertEquals(1, counts[0]);
        assertEquals(1, counts[1]);
    }
}
