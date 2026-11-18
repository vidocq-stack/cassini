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
import io.vidocq.vauban.core.container.VaubanContainerBuilder;
import jakarta.ws.rs.core.Application;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ScopelessNumberResource}, a bounded generic {@code @Path} class without a scope, gets {@code @Dependent} from
 * {@code CassiniScopeExtension}: CDI 4.1 §3.1 makes a generic managed bean {@code @Dependent}, and Vauban treats any
 * other scope as a definition error (Vauban BUG-20261117-35). The extension gave it {@code @RequestScoped}, which the
 * container now refuses at start (CASSINI-065). Its raw class resolves no bean through the {@code BeanManager}, and
 * Cassini finds the bean by its bean class (CASSINI-061). It is served as the {@code @Dependent} resource it is:
 * injected, its {@code @Context} field set, one instance per request, destroyed when its request ends as a plain
 * {@code @Dependent} resource is (CASSINI-062), with the extension run at start and frozen at build.
 */
class ScopelessBoundedGenericResourceTest {

    /** How the start gets what {@code CassiniScopeExtension} did. */
    enum ExtensionsAtStart {
        /** It runs the extension. */
        RUN,
        /** It reads the record a build froze, and runs no extension. */
        FROZEN
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private VaubanContainer container;
    private Server server;
    private String base;

    @TempDir
    Path output;

    private VaubanContainerBuilder deployment() {
        return VaubanContainer.builder()
                .classLoader(outputLoader())
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(Formats.class)
                .addBeanClass(ScopelessNumberResource.class)
                .addBeanClass(PlainResource.class)
                .addBeanClass(SingletonResource.class);
    }

    /** The loader of a start: the build's output first, where a record is, then the classes of this test. */
    private ClassLoader outputLoader() {
        try {
            return new URLClassLoader(new URL[] {output.toUri().toURL()}, getClass().getClassLoader());
        } catch (MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private void start(ExtensionsAtStart extensions) {
        if (extensions == ExtensionsAtStart.FROZEN) {
            var outcome = deployment().freezeExtensions(output);
            assertTrue(outcome.frozen(), outcome.reason());
        }
        Lifecycles.reset();
        container = deployment().build();
        assertEquals(extensions == ExtensionsAtStart.FROZEN, container.extensionsAtStart().frozen(), "frozen");
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

    @ParameterizedTest
    @EnumSource(ExtensionsAtStart.class)
    void itIsTheDependentResourceItsScopeMakesIt(ExtensionsAtStart extensions) throws Exception {
        start(extensions);
        String first = get("/scopeless-numbers");
        get("/plain");
        assertAll("after one request",
                () -> assertTrue(first.startsWith("scopeless:42:"), "injected: " + first),
                () -> assertTrue(first.endsWith("scopeless-numbers"), "its @Context field set: " + first),
                () -> assertEquals(1, Lifecycles.createdCount(ScopelessNumberResource.class), "created"));
        get("/scopeless-numbers");
        get("/plain");
        get("/singleton");
        get("/singleton");
        assertAll("after two requests",
                () -> assertEquals(1, Lifecycles.createdCount(SingletonResource.class), "one @Singleton resource"),
                () -> assertEquals(0, Lifecycles.destroyedCount(SingletonResource.class),
                        "a @Singleton resource no request ends"),
                () -> assertEquals(2, Lifecycles.createdCount(ScopelessNumberResource.class), "one per request"),
                () -> assertEquals(2, Lifecycles.destroyedCount(ScopelessNumberResource.class),
                        "destroyed when its request ends"),
                () -> assertEquals(2, Lifecycles.destroyedCount(PlainResource.class),
                        "a plain @Dependent resource destroyed when its request ends"));
    }
}
