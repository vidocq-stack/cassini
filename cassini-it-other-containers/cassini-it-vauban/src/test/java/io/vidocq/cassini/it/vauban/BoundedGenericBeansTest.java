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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A resource and a provider of a bounded generic class are served as the beans they are (CASSINI-061). CDI 4.1
 * §5.2.4 gives such a class the bean type {@code NumberResource<T extends Number>}, which the raw class does not
 * resolve. Both are {@code @Dependent}, the one scope §3.1 allows a generic managed bean: the resource declares it,
 * the provider takes it from {@code CassiniScopeExtension}. They live and die as a plain {@code @Dependent} resource
 * and provider do.
 */
class BoundedGenericBeansTest {

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
                .addBeanClass(NumberResource.class)
                .addBeanClass(NumberWriter.class)
                .addBeanClass(PlainResource.class)
                .addBeanClass(PlainFilter.class)
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
    void anIntegerOfTheBoundedGenericResourceGoesThroughTheBoundedGenericWriter() throws Exception {
        HttpResponse<String> response = get("/numbers");
        assertEquals("number:42", response.body());
        assertEquals("42", response.headers().firstValue(PlainFilter.HEADER).orElse(null));
    }

    @Test
    void theyAreBuiltAsOftenAsAPlainDependentResourceAndProvider() throws Exception {
        get("/numbers");
        get("/numbers");
        get("/plain");
        get("/plain");
        assertAll(
                () -> assertEquals(2, Lifecycles.createdCount(PlainResource.class), "one plain resource per request"),
                () -> assertEquals(2, Lifecycles.createdCount(NumberResource.class), "one resource per request"),
                () -> assertEquals(Lifecycles.createdCount(PlainFilter.class),
                        Lifecycles.createdCount(NumberWriter.class), "providers"));
    }

    /** A {@code @PreDestroy} runs, or does not, at the moments it does for the plain fixtures. */
    @Test
    void theyAreDestroyedWhenAPlainDependentResourceAndProviderAre() throws Exception {
        get("/numbers");
        get("/plain");
        assertAll("after the requests",
                () -> assertEquals(Lifecycles.destroyedCount(PlainResource.class),
                        Lifecycles.destroyedCount(NumberResource.class), "resources"),
                () -> assertEquals(Lifecycles.destroyedCount(PlainFilter.class),
                        Lifecycles.destroyedCount(NumberWriter.class), "providers"));
        stop();
        assertAll("once the container is closed",
                () -> assertEquals(Lifecycles.destroyedCount(PlainResource.class),
                        Lifecycles.destroyedCount(NumberResource.class), "resources"),
                () -> assertEquals(Lifecycles.destroyedCount(PlainFilter.class),
                        Lifecycles.destroyedCount(NumberWriter.class), "providers"));
    }
}
