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

import jakarta.ws.rs.SeBootstrap;

/**
 * AutoCloseable test server — starts Cassini Chappe on a random port.
 *
 * <p>Usage in JUnit 5:</p>
 * <pre>{@code
 * private static ExampleServer server;
 *
 * @BeforeAll
 * static void start() throws Exception { server = new ExampleServer(); }
 *
 * @AfterAll
 * static void close() throws Exception { server.close(); }
 * }</pre>
 */
public class ExampleServer implements AutoCloseable {

    private final SeBootstrap.Instance instance;
    private final int port;

    public ExampleServer() throws Exception {
        // FREE_PORT: the runtime takes a free port on the host and reports it. The test no longer picks a port on
        // every address, closes it and has the server bind it again later, which let another process take it in
        // between (CASSINI-048).
        this.instance = SeBootstrap.start(new Main.ExamplesApp(),
                SeBootstrap.Configuration.builder()
                        .host("127.0.0.1")
                        .port(SeBootstrap.Configuration.FREE_PORT)
                        .build())
                .toCompletableFuture()
                .get();
        this.port = instance.configuration().port();
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Override
    public void close() throws Exception {
        instance.stop().toCompletableFuture().get();
    }
}
