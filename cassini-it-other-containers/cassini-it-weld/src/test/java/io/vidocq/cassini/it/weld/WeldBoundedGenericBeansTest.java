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

import io.vidocq.cassini.cdi.CdiBeanProvider;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Server;
import jakarta.enterprise.inject.spi.CDI;
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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container-neutral provider of cassini-cdi serves a resource and a provider of a bounded generic class as the
 * Weld beans they are (CASSINI-061): Weld gives such a class the bean type {@code NumberResource<T extends Number>}
 * (CDI 4.1 §5.2.4), which the raw class does not resolve. They live and die as a plain {@code @Dependent} resource
 * and provider do.
 */
class WeldBoundedGenericBeansTest {

    private final HttpClient http = HttpClient.newHttpClient();
    private WeldContainer container;
    private Server server;
    private String base;

    @BeforeEach
    void start() {
        Lifecycles.reset();
        container = new Weld().initialize();
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

    @Test
    void aClassThatIsNoBeanIsStillNotResolvable() {
        var provider = new CdiBeanProvider(CDI.current());
        var failure = assertThrows(IllegalArgumentException.class, () -> provider.getBean(Lifecycles.class));
        assertTrue(failure.getMessage().startsWith("Not a resolvable CDI bean"), failure.getMessage());
    }

    /**
     * The lookup by bean class changes the type match only: it takes the beans a lookup by class takes, those that
     * have {@code @Default}. A class whose bean has only another qualifier is still built by Cassini, as before
     * CASSINI-061 (CASSINI-064).
     */
    @Test
    void aClassWhoseBeanHasOnlyAnotherQualifierIsStillNotResolvable() {
        var provider = new CdiBeanProvider(CDI.current());
        var classes = provider.getResourceClasses();
        assertTrue(classes.contains(TaggedResource.class), classes::toString);
        var failure = assertThrows(IllegalArgumentException.class, () -> provider.getBean(TaggedResource.class));
        assertTrue(failure.getMessage().startsWith("Not a resolvable CDI bean"), failure.getMessage());
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
