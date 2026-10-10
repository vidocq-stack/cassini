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
import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Cassini on Chappe with Weld SE as the CDI container (vidocq-workspace#15, cassini#53): the
 * container-neutral BeanProvider of cassini-cdi resolves the resources as Weld beans, activates the
 * request context per request, and Weld's interceptors apply. Nothing of Vauban is used.
 */
class WeldPortabilityTest {

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

    @Test
    void vaubanIsNotOnTheClassPath() {
        assertThrows(ClassNotFoundException.class,
                () -> Class.forName("io.vidocq.vauban.core.container.VaubanContainer"));
    }

    @Test
    void resourceGetsInjection() throws Exception {
        assertEquals("hello weld", get("/greetings/weld"));
    }

    @Test
    void requestScopedBeanIsOnePerRequest() throws Exception {
        String[] firstRequest = get("/greetings/token").split("\\|");
        String[] secondRequest = get("/greetings/token").split("\\|");

        assertEquals(firstRequest[0], firstRequest[1], "one instance within a request");
        assertNotEquals(firstRequest[0], secondRequest[0], "a new instance for each request");
    }

    @Test
    void interceptorOnResourceMethodApplies() throws Exception {
        assertEquals("HELLO WELD", get("/greetings/shout/weld"));
    }

    private static String get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }
}
