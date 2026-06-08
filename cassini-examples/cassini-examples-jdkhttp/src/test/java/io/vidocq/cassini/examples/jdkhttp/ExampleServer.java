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
package io.vidocq.cassini.examples.jdkhttp;

import io.vidocq.cassini.examples.jdkhttp.resource.GreetingResource;
import io.vidocq.cassini.examples.jdkhttp.resource.TodoResource;
import io.vidocq.cassini.jdkhttp.JdkHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.Application;

import java.net.ServerSocket;
import java.util.Set;

/**
 * Test server — starts Cassini + JDK HttpServer on a random port.
 *
 * <p>Manual bootstrap via {@link CassiniStack#builder()} (no SeBootstrap
 * for the JDK transport — see {@link Main} for details).</p>
 */
public class ExampleServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;

    public ExampleServer() throws Exception {
        try (var ss = new ServerSocket(0)) {
            this.port = ss.getLocalPort();
        }

        var stack = CassiniStack.builder()
                .application(new Application() {
                    @Override public Set<Class<?>> getClasses() {
                        return Set.of(GreetingResource.class, TodoResource.class);
                    }
                })
                .build();

        this.server = new JdkHttpAdapter(stack.adapter()).serve(this.port);
    }

    public int port()       { return port; }
    public String baseUrl() { return "http://127.0.0.1:" + port; }

    @Override
    public void close() {
        server.stop(0);
    }
}
