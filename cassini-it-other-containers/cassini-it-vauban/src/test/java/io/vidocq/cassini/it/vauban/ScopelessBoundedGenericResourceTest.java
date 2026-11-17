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
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vauban BUG-20261117-35-aware, not BUG-35-proof. {@link ScopelessNumberResource}, a bounded generic {@code @Path}
 * class without a scope, gets {@code @RequestScoped} from {@code CassiniScopeExtension}: a definition error under CDI
 * 4.1 §3.1 that Vauban does not report. Its raw class resolves no bean through the {@code BeanManager}, on Vauban
 * main as on vauban#335, and Cassini finds the bean by its bean class (CASSINI-061). It is served as the
 * request-scoped bean it is: injected, its {@code @Context} field set on the contextual instance, and destroyed at the
 * end of the request. Before CASSINI-061 it answered {@code 500} on both: built by Cassini on vauban#335, its client
 * proxy not looked behind on Vauban main.
 *
 * <p>When Vauban reports BUG-35's definition error, this container no longer starts: the Cassini companion of BUG-35
 * gives a generic {@code @Path} class {@code @Dependent} and adapts or drops this test.</p>
 */
@Tag("vauban-BUG-20261117-35")
class ScopelessBoundedGenericResourceTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private VaubanContainer container;
    private Server server;
    private String base;

    @BeforeEach
    void start() {
        Lifecycles.reset();
        container = VaubanContainer.builder()
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(Formats.class)
                .addBeanClass(ScopelessNumberResource.class)
                .build();
        var stack = CassiniStack.builder().application(new Application() {}).build();
        // Port 0: the server keeps the free port the system gives it (CASSINI-048).
        server = Server.builder().host("127.0.0.1").port(0).handler(new ChappeHttpAdapter(stack.adapter())).build();
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop();
        }
        if (container != null) {
            container.close();
        }
    }

    private String get(String path) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path + ": " + response.body());
        return response.body();
    }

    @Test
    void itIsTheRequestScopedBeanItsScopeMakesIt() throws Exception {
        String first = get("/scopeless-numbers");
        assertAll("after one request",
                () -> assertTrue(first.startsWith("scopeless:42:"), "injected: " + first),
                () -> assertTrue(first.endsWith("scopeless-numbers"), "its @Context field set: " + first),
                () -> assertEquals(1, Lifecycles.createdCount(ScopelessNumberResource.class), "created"),
                () -> assertEquals(1, Lifecycles.destroyedCount(ScopelessNumberResource.class),
                        "destroyed at the end of the request"));
        get("/scopeless-numbers");
        assertAll("after two requests",
                () -> assertEquals(2, Lifecycles.createdCount(ScopelessNumberResource.class), "one per request"),
                () -> assertEquals(2, Lifecycles.destroyedCount(ScopelessNumberResource.class), "one per request"));
    }
}
