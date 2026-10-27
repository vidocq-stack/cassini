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
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** {@link jakarta.ws.rs.client.SyncInvoker} verbs of {@link Invocation.Builder}, against a live server. */
class SyncInvokerTest {

    private static final GenericType<String> STRING = new GenericType<>(String.class);
    private static final String PATCH = "PATCH";
    private static final String BODY = "payload";
    private static final String GET_ECHO = "GET:";
    private static final String DELETE_ECHO = "DELETE:";
    private static final String OPTIONS_ECHO = "OPTIONS:";
    private static final String TRACE_ECHO = "TRACE:";
    private static final String PATCH_ECHO = "PATCH:";
    private static final String PUT_ECHO = "PUT:payload";
    private static final String POST_ECHO = "POST:payload";
    private static final String PATCH_BODY_ECHO = "PATCH:payload";

    private static RecordingServer server;
    private static Client client;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    private static Invocation.Builder request() {
        return client.target(server.baseUrl()).path("/echo").request();
    }

    private static Entity<String> text() {
        return Entity.text(BODY);
    }

    @Test
    void verbs_without_entity_send_their_method() {
        assertEquals(GET_ECHO, request().get().readEntity(String.class));
        assertEquals(DELETE_ECHO, request().delete().readEntity(String.class));
        assertEquals(OPTIONS_ECHO, request().options().readEntity(String.class));
        assertEquals(TRACE_ECHO, request().trace().readEntity(String.class));
        assertEquals(PATCH_ECHO, request().method(PATCH).readEntity(String.class));
        assertEquals("/echo", server.last().uri());
    }

    @Test
    void verbs_with_entity_send_the_body_and_its_content_type() {
        assertEquals(PUT_ECHO, request().put(text()).readEntity(String.class));
        assertEquals("text/plain", server.last().header("Content-Type"));
        assertEquals(POST_ECHO, request().post(text()).readEntity(String.class));
        assertEquals(PATCH_BODY_ECHO, request().method(PATCH, text()).readEntity(String.class));
    }

    @Test
    void head_returns_the_status_without_an_entity() {
        Response response = request().head();
        assertEquals(200, response.getStatus());
        assertFalse(response.hasEntity());
        assertEquals("HEAD", server.last().method());
    }

    @Test
    void typed_verbs_read_the_entity_as_the_requested_class() {
        assertEquals(GET_ECHO, request().get(String.class));
        assertEquals(PUT_ECHO, request().put(text(), String.class));
        assertEquals(POST_ECHO, request().post(text(), String.class));
        assertEquals(DELETE_ECHO, request().delete(String.class));
        assertEquals(OPTIONS_ECHO, request().options(String.class));
        assertEquals(TRACE_ECHO, request().trace(String.class));
        assertEquals(PATCH_ECHO, request().method(PATCH, String.class));
        assertEquals(PATCH_BODY_ECHO, request().method(PATCH, text(), String.class));
    }

    @Test
    void generic_typed_verbs_read_the_entity_as_the_requested_type() {
        assertEquals(GET_ECHO, request().get(STRING));
        assertEquals(PUT_ECHO, request().put(text(), STRING));
        assertEquals(POST_ECHO, request().post(text(), STRING));
        assertEquals(DELETE_ECHO, request().delete(STRING));
        assertEquals(OPTIONS_ECHO, request().options(STRING));
        assertEquals(TRACE_ECHO, request().trace(STRING));
        assertEquals(PATCH_ECHO, request().method(PATCH, STRING));
        assertEquals(PATCH_BODY_ECHO, request().method(PATCH, text(), STRING));
    }

    @Test
    void byte_array_entity_is_sent_as_is() {
        byte[] bytes = {1, 2, 3, 'z'};
        Response response = request().post(Entity.entity(bytes, MediaType.APPLICATION_OCTET_STREAM_TYPE));
        assertEquals(200, response.getStatus());
        assertEquals("application/octet-stream", server.last().header("Content-Type"));
        assertArrayEquals(bytes, server.last().body().getBytes(StandardCharsets.UTF_8));
    }
}
