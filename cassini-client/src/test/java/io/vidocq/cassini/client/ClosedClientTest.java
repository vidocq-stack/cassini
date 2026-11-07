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
package io.vidocq.cassini.client;

import io.vidocq.cassini.client.support.RecordingServer;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CASSINI-024 item 5: once a {@link Client} is closed, everything derived from it is closed
 * with it ({@link Client#close()} Javadoc: "invoking any other method on the client instance
 * would result in an IllegalStateException"): the targets and invocation builders created
 * before the close do not keep sending requests, nor accept configuration.
 */
class ClosedClientTest {

    private static RecordingServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static Client closed() {
        Client client = ClientBuilder.newClient();
        client.close();
        return client;
    }

    @Test
    void closing_twice_is_ignored() {
        Client client = closed();
        assertDoesNotThrow(client::close);
    }

    @Test
    void a_new_target_is_refused() {
        Client client = closed();
        String url = server.baseUrl();
        assertThrows(IllegalStateException.class, () -> client.target(url));
    }

    @Test
    void register_and_property_on_the_client_are_refused() {
        Client client = closed();
        assertThrows(IllegalStateException.class, () -> client.property("p", "v"));
        assertThrows(IllegalStateException.class, () -> client.register(Object.class));
    }

    @Test
    void an_existing_target_cannot_build_a_request() {
        Client client = ClientBuilder.newClient();
        WebTarget target = client.target(server.baseUrl());
        client.close();
        assertThrows(IllegalStateException.class, target::request);
        assertThrows(IllegalStateException.class, () -> target.path("x"));
    }

    @Test
    void an_existing_target_refuses_register_and_property() {
        Client client = ClientBuilder.newClient();
        WebTarget target = client.target(server.baseUrl());
        client.close();
        assertThrows(IllegalStateException.class, () -> target.property("p", "v"));
        assertThrows(IllegalStateException.class, () -> target.register(Object.class));
    }

    @Test
    void an_existing_request_builder_does_not_send() {
        Client client = ClientBuilder.newClient();
        Invocation.Builder builder = client.target(server.baseUrl()).request();
        client.close();
        assertThrows(IllegalStateException.class, builder::get);
        Entity<String> entity = Entity.text("x");
        assertThrows(IllegalStateException.class, () -> builder.post(entity));
    }

    @Test
    void an_existing_invocation_does_not_send() {
        Client client = ClientBuilder.newClient();
        Invocation invocation = client.target(server.baseUrl()).request().buildGet();
        client.close();
        assertThrows(IllegalStateException.class, invocation::invoke);
    }

    @Test
    void an_existing_async_invoker_does_not_send() {
        Client client = ClientBuilder.newClient();
        Invocation.Builder builder = client.target(server.baseUrl()).request();
        client.close();
        AsyncInvoker async = builder.async();
        assertThrows(IllegalStateException.class, async::get);
    }

    @Test
    void an_open_client_still_works() {
        try (Client client = ClientBuilder.newClient()) {
            assertEquals(200, client.target(server.baseUrl()).request().get().getStatus());
        }
    }
}
