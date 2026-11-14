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
package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The SE bootstrap instance of the JDK transport: the port it reports is the one its server listens on. */
class JdkHttpSeBootstrapTest {

    private static final String HELLO = "hello";
    private static final String LOOPBACK = "127.0.0.1";

    @Path(HELLO)
    public static class HelloResource {
        @GET
        public String hello() { return HELLO; }
    }

    public static class PlainApplication extends Application {
        @Override public Set<Class<?>> getClasses() { return Set.of(HelloResource.class); }
    }

    /**
     * An application whose build listens on a loopback port, as another process can while an instance starts:
     * the instance builds the application's stack before its server listens.
     */
    public static final class PortTakingApplication extends Application {
        private final int port;
        private ServerSocketChannel taken;

        PortTakingApplication(int port) { this.port = port; }

        @Override public synchronized Set<Class<?>> getClasses() {
            if (taken == null) {
                taken = listenOn(port);
            }
            return Set.of(HelloResource.class);
        }

        synchronized boolean tookThePort() { return taken != null && taken.isOpen(); }

        synchronized void release() throws IOException {
            if (taken != null) taken.close();
        }

        /** A loopback listener on {@code port}, or {@code null} when the port is already used. */
        private static ServerSocketChannel listenOn(int port) {
            try {
                ServerSocketChannel channel = ServerSocketChannel.open();
                try {
                    channel.bind(new InetSocketAddress(LOOPBACK, port));
                    return channel;
                } catch (IOException _) {
                    channel.close();
                    return null;
                }
            } catch (IOException _) {
                return null;
            }
        }
    }

    /**
     * The loopback port the system hands out next on macOS, which allocates its ephemeral ports in sequence; on
     * a system that picks them at random it is one more port among others.
     */
    private static int nextEphemeralLoopbackPort() throws IOException {
        try (ServerSocketChannel probe = ServerSocketChannel.open()) {
            probe.bind(new InetSocketAddress(LOOPBACK, 0));
            int port = ((InetSocketAddress) probe.getLocalAddress()).getPort();
            return port < 65_535 ? port + 1 : port;
        }
    }

    private static SeBootstrap.Instance start(Application application, SeBootstrap.Configuration config)
            throws Exception {
        return new JdkHttpRuntimeDelegate().bootstrap(application, config)
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private static void stop(SeBootstrap.Instance instance) throws Exception {
        instance.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** The body of a {@code GET path} on the instance's loopback address, over a plain HTTP/1.1 socket. */
    private static String get(SeBootstrap.Instance instance, String path) throws IOException {
        try (Socket socket = new Socket(LOOPBACK, instance.configuration().port())) {
            // a listener that is not the instance accepts the connection and never answers
            socket.setSoTimeout(10_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(raw.startsWith("HTTP/1.1 200"), raw);
            return raw.substring(raw.indexOf("\r\n\r\n") + 4);
        }
    }

    /**
     * {@code FREE_PORT} (CASSINI-049): the server binds a free port itself, on the loopback address of the
     * {@code localhost} it reports. Before, the instance picked a port, built the stack, then bound that port on
     * every address: the application's build takes the port the system hands out next, and on macOS the bind on
     * every address succeeded beside that listener, which then took the clients of {@code 127.0.0.1}.
     */
    @Test
    void a_free_port_is_bound_by_the_server_itself_on_the_loopback_address() throws Exception {
        PortTakingApplication application = new PortTakingApplication(nextEphemeralLoopbackPort());
        SeBootstrap.Configuration config = SeBootstrap.Configuration.builder()
                .port(SeBootstrap.Configuration.FREE_PORT).build();
        try {
            SeBootstrap.Instance instance = start(application, config);
            try {
                assumeTrue(application.tookThePort(), "another process already listens on the port to take");
                assertEquals(instance.unwrap(HttpServer.class).getAddress().getPort(), instance.configuration().port(),
                        "the configuration reports the bound port");
                assertEquals(HELLO, get(instance, "/hello"));
            } finally {
                stop(instance);
            }
        } finally {
            application.release();
        }
    }

    /** No port at all ({@code DEFAULT_PORT}) is served on a free port too, and reported as such. */
    @Test
    void without_a_port_the_instance_listens_on_a_free_loopback_port() throws Exception {
        SeBootstrap.Instance instance = start(new PlainApplication(), SeBootstrap.Configuration.builder().build());
        try {
            InetSocketAddress bound = instance.unwrap(HttpServer.class).getAddress();
            assertTrue(bound.getAddress().isLoopbackAddress(), "bound on " + bound);
            assertEquals(bound.getPort(), instance.configuration().port());
            assertEquals("localhost", instance.configuration().host());
            assertEquals(HELLO, get(instance, "/hello"));
        } finally {
            stop(instance);
        }
    }
}
