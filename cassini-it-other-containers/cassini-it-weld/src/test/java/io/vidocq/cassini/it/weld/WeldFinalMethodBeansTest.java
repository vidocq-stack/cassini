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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A normal-scoped provider and resource whose class has a final method are Weld beans: CDI 4.1 §3.10 refuses a lookup
 * of such a class, which made the container-neutral provider of cassini-cdi throw, and Cassini built them with
 * {@code new}, nothing injected (CASSINI-066).
 */
class WeldFinalMethodBeansTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private WeldContainer container;
    private Server server;
    private String base;

    @BeforeEach
    void start() {
        container = new Weld().initialize();
        var stack = CassiniStack.builder().application(new Application() {}).build();
        server = Server.builder().host("127.0.0.1").port(0).handler(new ChappeHttpAdapter(stack.adapter())).build();
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
            server = null;
        }
        if (container != null) {
            container.close();
            container = null;
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response;
    }

    @Test
    void theResourceIsInjectedAndTheBoundFilterRunsOnItsMethodsOnly() throws Exception {
        HttpResponse<String> bound = get("/sealed/bound");
        assertEquals("hello resource", bound.body());
        assertEquals("hello sealed", bound.headers().firstValue(SealedFilter.HEADER).orElse(null));
        HttpResponse<String> free = get("/sealed/free");
        assertEquals("hello resource", free.body());
        assertTrue(free.headers().firstValue(SealedFilter.HEADER).isEmpty(), "the name binding is kept");
    }
}
