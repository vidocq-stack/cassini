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

import io.vidocq.cassini.examples.chappe.resource.GreetingResource;
import io.vidocq.cassini.examples.chappe.resource.TodoResource;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Cassini + Chappe entry point (standalone, no CDI).
 *
 * <p>The Chappe {@link jakarta.ws.rs.ext.RuntimeDelegate} is discovered
 * automatically via ServiceLoader (declared in {@code cassini-chappe}).</p>
 */
public class Main {

    static void main(String[] args) throws Exception {
        var instance = SeBootstrap.start(new ExamplesApp(),
                SeBootstrap.Configuration.builder()
                        .host("0.0.0.0")
                        .port(8080)
                        .build())
                .toCompletableFuture()
                .get();

        System.out.println("Cassini Chappe example started on port "
                + instance.configuration().port());
        System.out.println("  GET  http://localhost:8080/greetings");
        System.out.println("  GET  http://localhost:8080/greetings/{name}");
        System.out.println("  GET  http://localhost:8080/todos");
        System.out.println("  POST http://localhost:8080/todos");
        System.out.println("Press CTRL-C to stop.");

        new CountDownLatch(1).await();
    }

    /**
     * JAX-RS Application declaring the example resources.
     */
    public static final class ExamplesApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(GreetingResource.class, TodoResource.class);
        }
    }
}
