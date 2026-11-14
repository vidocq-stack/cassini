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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Test server — starts Cassini + JDK HttpServer on a random port.
 *
 * <p>Manual bootstrap via {@link CassiniStack#builder()} (no SeBootstrap
 * for the JDK transport — see {@link Main} for details).</p>
 */
public class ExampleServer implements AutoCloseable {

    private static final String LOOPBACK = "127.0.0.1";

    private final HttpServer server;
    private final int port;

    public ExampleServer() throws Exception {
        var stack = CassiniStack.builder()
                .application(new Application() {
                    @Override public Set<Class<?>> getClasses() {
                        return Set.of(GreetingResource.class, TodoResource.class);
                    }
                })
                .build();

        // Port 0 on the address the tests call: the server keeps the free port the system gives it, and no other
        // listener can take 127.0.0.1 on that port (CASSINI-048). JdkHttpAdapter.serve(int) binds the wildcard
        // address, which a listener on 127.0.0.1 and the same port shadows, so the test binds the server itself.
        this.server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(LOOPBACK), 0), 0);
        server.createContext("/", new JdkHttpAdapter(stack.adapter()).asHandler());
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        this.port = server.getAddress().getPort();
    }

    public int port()       { return port; }
    public String baseUrl() { return "http://" + LOOPBACK + ":" + port; }

    @Override
    public void close() {
        server.stop(0);
    }
}
