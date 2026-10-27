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
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link Invocation}: built invocations, run synchronously or submitted. */
class InvocationTest {

    private static final long TIMEOUT_S = 5;
    private static final String POST_ECHO = "POST:x";
    private static final String GET_ECHO = "GET:";

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
        return client.target(server.baseUrl()).path("/i").request();
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.get(TIMEOUT_S, TimeUnit.SECONDS);
    }

    @Test
    void built_invocations_run_their_method_and_entity() {
        assertEquals(GET_ECHO, request().buildGet().invoke().readEntity(String.class));
        assertEquals("DELETE:", request().buildDelete().invoke(String.class));
        assertEquals(POST_ECHO, request().buildPost(Entity.text("x")).invoke(String.class));
        assertEquals("PUT:x", request().buildPut(Entity.text("x")).invoke(new GenericType<>(String.class)));
        assertEquals("PATCH:", request().build("PATCH").invoke(String.class));
        assertEquals("PATCH:y", request().build("PATCH", Entity.text("y")).invoke(String.class));
    }

    @Test
    void an_invocation_can_run_twice() {
        Invocation invocation = request().buildPost(Entity.text("x"));
        assertEquals(POST_ECHO, invocation.invoke(String.class));
        assertEquals(POST_ECHO, invocation.invoke(String.class));
    }

    @Test
    void submitted_invocations_complete_their_future() throws Exception {
        Invocation invocation = request().buildPost(Entity.text("x"));
        assertEquals(POST_ECHO, await(invocation.submit()).readEntity(String.class));
        assertEquals(POST_ECHO, await(invocation.submit(String.class)));
        assertEquals(POST_ECHO, await(invocation.submit(new GenericType<>(String.class))));
    }

    @Test
    void submitted_invocation_with_a_callback_completes_it() throws Exception {
        CompletableFuture<String> result = new CompletableFuture<>();
        Future<String> future = request().buildGet().submit(new InvocationCallback<String>() {
            @Override public void completed(String entity) { result.complete(entity); }
            @Override public void failed(Throwable throwable) { result.completeExceptionally(throwable); }
        });
        assertEquals(GET_ECHO, result.get(TIMEOUT_S, TimeUnit.SECONDS));
        assertEquals(GET_ECHO, await(future));
    }

    @Test
    void response_type_returns_the_response() {
        Response response = request().buildGet().invoke(Response.class);
        assertEquals(200, response.getStatus());
    }
}
