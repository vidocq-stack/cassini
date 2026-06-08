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
package io.vidocq.cassini.examples.vauban;

import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.net.ServerSocket;

/**
 * Test server — starts Vauban CDI + Chappe composite handler (static content
 * on {@code /} + Cassini on {@code /api/*}) on a random port.
 */
public class ExampleServer implements AutoCloseable {

    private final VaubanContainer container;
    private final Server server;
    private final int port;

    public ExampleServer() throws Exception {
        try (var ss = new ServerSocket(0)) {
            this.port = ss.getLocalPort();
        }

        this.container = VaubanContainer.builder()
                .scanClasspath()
                .build();

        this.server = Server.builder()
                .host("127.0.0.1").port(this.port)
                .handler(VaubanApp.composeHandler())
                .build();
        this.server.start();
    }

    public int port()                  { return port; }
    public String baseUrl()            { return "http://127.0.0.1:" + port; }
    public String apiUrl()             { return baseUrl() + VaubanApp.API_PREFIX; }
    public VaubanContainer container() { return container; }

    @Override
    public void close() {
        try { server.stop(); }
        finally { container.close(); }
    }
}
