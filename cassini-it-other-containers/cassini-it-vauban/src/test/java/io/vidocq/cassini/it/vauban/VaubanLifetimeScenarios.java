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
package io.vidocq.cassini.it.vauban;

import io.vidocq.cassini.cdi.CassiniScopeExtension;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.ws.rs.core.Application;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The request scope of Vauban lives for the whole request and ends with it, whichever way the request
 * ends (CASSINI-007, cassini#116): the entity writers still see it, and a 404, an unmapped failure or a
 * response resumed from another thread leave neither a scope active nor one ended twice. The subclasses
 * differ in who binds the scope around the request: the filter of cassini-cdi-vauban, or the hook of the
 * Vidocq runtime ({@code RequestContext#runInScope}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class VaubanLifetimeScenarios {

    private final HttpClient http = HttpClient.newHttpClient();
    private VaubanContainer container;
    private Server server;
    private String base;

    /** The Chappe handler for the stack, given the running container. */
    abstract ChappeHttpAdapter handler(CassiniStack stack, VaubanContainer container);

    @BeforeAll
    void start() throws Exception {
        container = VaubanContainer.builder()
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(RequestToken.class)
                .addBeanClass(ScopeEvents.class)
                .addBeanClass(LifetimeResource.class)
                .build();
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var stack = CassiniStack.builder().application(new Application() {}).build();
        server = Server.builder().host("127.0.0.1").port(port).handler(handler(stack, container)).build();
        server.start();
        base = "http://127.0.0.1:" + port;
    }

    @AfterAll
    void stop() {
        if (server != null) {
            server.stop();
        }
        if (container != null) {
            container.close();
        }
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Runs a request and returns how many request scopes it started and ended. */
    private int[] scopes(String path, int expectedStatus) throws Exception {
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

    /** Whichever way a request ends, its scope starts once and ends once. */
    @ParameterizedTest(name = "{0} answers {1}")
    @CsvSource({
        "/lifetime/stream,  200",
        "/lifetime/missing, 404",
        "/lifetime/boom,    500",
        "/lifetime/later,   200",
    })
    void theScopeEndsOnceWhicheverWayTheRequestEnds(String path, int status) throws Exception {
        int[] counts = scopes(path, status);
        assertEquals(1, counts[0], "started once");
        assertEquals(1, counts[1], "ended once");
    }
}
