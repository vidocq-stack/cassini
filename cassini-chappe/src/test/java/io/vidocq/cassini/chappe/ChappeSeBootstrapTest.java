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
package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Server;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SE bootstrap instance of the Chappe transport: it starts, unwraps to its server and stops. */
class ChappeSeBootstrapTest {

    private static final String HELLO = "hello";

    @Path(HELLO)
    public static class HelloResource {
        @GET
        public String hello() { return HELLO; }
    }

    @ApplicationPath("api/")
    public static class PrefixedApplication extends Application {
        @Override public Set<Class<?>> getClasses() { return Set.of(HelloResource.class); }
    }

    public static class PlainApplication extends Application {
        @Override public Set<Class<?>> getClasses() { return Set.of(HelloResource.class); }
    }

    private static SeBootstrap.Instance start(Application application, SeBootstrap.Configuration config)
            throws Exception {
        return new ChappeRuntimeDelegate().bootstrap(application, config)
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    /** Status code and body of a {@code GET path} on the instance, over a plain HTTP/1.1 socket. */
    private record Reply(int status, String body) {}

    private static Reply get(SeBootstrap.Instance instance, String path) throws Exception {
        int port = (Integer) instance.configuration().property(SeBootstrap.Configuration.PORT);
        try (Socket socket = new Socket("127.0.0.1", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = socket.getInputStream();
            String raw = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            int status = Integer.parseInt(raw.substring(9, 12));
            int bodyStart = raw.indexOf("\r\n\r\n");
            return new Reply(status, bodyStart < 0 ? "" : raw.substring(bodyStart + 4));
        }
    }

    @Test
    void the_root_path_and_the_application_path_prefix_every_route() throws Exception {
        SeBootstrap.Configuration config = SeBootstrap.Configuration.builder().port(0).rootPath("root/").build();
        SeBootstrap.Instance instance = start(new PrefixedApplication(), config);
        try {
            Reply hello = get(instance, "/root/api/hello");
            assertEquals(200, hello.status());
            assertEquals(HELLO, hello.body());
            assertEquals(404, get(instance, "/hello").status());
            assertEquals("root/", instance.configuration().property(SeBootstrap.Configuration.ROOT_PATH));
            assertEquals("localhost", instance.configuration().property(SeBootstrap.Configuration.HOST));
        } finally {
            instance.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void the_prefix_alone_reaches_the_application_root() throws Exception {
        SeBootstrap.Configuration config = SeBootstrap.Configuration.builder().port(0).rootPath("/root").build();
        SeBootstrap.Instance instance = start(new PlainApplication(), config);
        try {
            assertEquals(404, get(instance, "/root").status());
            assertEquals(HELLO, get(instance, "/root/hello").body());
        } finally {
            instance.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void without_any_prefix_the_routes_are_served_as_declared() throws Exception {
        SeBootstrap.Configuration config = SeBootstrap.Configuration.builder().port(0).rootPath("/").build();
        SeBootstrap.Instance instance = start(new PlainApplication(), config);
        try {
            assertEquals(HELLO, get(instance, "/hello").body());
        } finally {
            instance.stop().toCompletableFuture().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    void a_started_instance_unwraps_to_its_chappe_server_and_stops() throws Exception {
        SeBootstrap.Configuration config = SeBootstrap.Configuration.builder().port(0).build();
        SeBootstrap.Instance instance = new ChappeRuntimeDelegate()
                .bootstrap(new Application(), config).toCompletableFuture().get(30, TimeUnit.SECONDS);
        try {
            assertNotNull(instance.unwrap(Server.class));
            int port = (Integer) instance.configuration().property(SeBootstrap.Configuration.PORT);
            assertTrue(port > 0, "the effective configuration carries the port picked: " + port);
            assertThrows(IllegalArgumentException.class, () -> instance.unwrap(String.class));
        } finally {
            assertNotNull(instance.stop().toCompletableFuture().get(30, TimeUnit.SECONDS));
        }
    }
}
