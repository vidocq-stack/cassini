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
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** {@link AsyncInvoker}: futures and callbacks, against a live server. */
class AsyncInvokerTest {

    private static final GenericType<String> STRING = new GenericType<>(String.class);
    private static final String PATCH = "PATCH";
    private static final String GET_ECHO = "GET:";
    private static final long TIMEOUT_S = 5;

    private static RecordingServer server;
    private static Client client;

    @BeforeAll
    static void start() throws Exception {
        server = new RecordingServer((exchange, request) -> {
            int status = request.uri().endsWith("/missing") ? 404 : 200;
            RecordingServer.send(exchange, status, Map.of("Content-Type", "text/plain"),
                    request.method() + ":" + request.body());
        });
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    private static AsyncInvoker async() {
        return client.target(server.baseUrl()).path("/a").request().async();
    }

    private static Entity<String> text() {
        return Entity.text("x");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.get(TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static String body(Future<Response> future) throws Exception {
        return await(future).readEntity(String.class);
    }

    @Test
    void untyped_futures_complete_with_the_response() throws Exception {
        assertEquals(GET_ECHO, body(async().get()));
        assertEquals("PUT:x", body(async().put(text())));
        assertEquals("POST:x", body(async().post(text())));
        assertEquals("DELETE:", body(async().delete()));
        assertEquals(200, await(async().head()).getStatus());
        assertEquals("OPTIONS:", body(async().options()));
        assertEquals("TRACE:", body(async().trace()));
        assertEquals("PATCH:", body(async().method(PATCH)));
        assertEquals("PATCH:x", body(async().method(PATCH, text())));
    }

    @Test
    void class_typed_futures_complete_with_the_entity() throws Exception {
        assertEquals(GET_ECHO, await(async().get(String.class)));
        assertEquals("PUT:x", await(async().put(text(), String.class)));
        assertEquals("POST:x", await(async().post(text(), String.class)));
        assertEquals("DELETE:", await(async().delete(String.class)));
        assertEquals("OPTIONS:", await(async().options(String.class)));
        assertEquals("TRACE:", await(async().trace(String.class)));
        assertEquals("PATCH:", await(async().method(PATCH, String.class)));
        assertEquals("PATCH:x", await(async().method(PATCH, text(), String.class)));
    }

    @Test
    void generic_typed_futures_complete_with_the_entity() throws Exception {
        assertEquals(GET_ECHO, await(async().get(STRING)));
        assertEquals("PUT:x", await(async().put(text(), STRING)));
        assertEquals("POST:x", await(async().post(text(), STRING)));
        assertEquals("DELETE:", await(async().delete(STRING)));
        assertEquals("OPTIONS:", await(async().options(STRING)));
        assertEquals("TRACE:", await(async().trace(STRING)));
        assertEquals("PATCH:", await(async().method(PATCH, STRING)));
        assertEquals("PATCH:x", await(async().method(PATCH, text(), STRING)));
    }

    @Test
    void response_callbacks_receive_the_response() throws Exception {
        assertEquals(GET_ECHO, callback(c -> async().get(c)).readEntity(String.class));
        assertEquals("PUT:x", callback(c -> async().put(text(), c)).readEntity(String.class));
        assertEquals("POST:x", callback(c -> async().post(text(), c)).readEntity(String.class));
        assertEquals("DELETE:", callback(c -> async().delete(c)).readEntity(String.class));
        assertEquals(200, callback(c -> async().head(c)).getStatus());
        assertEquals("OPTIONS:", callback(c -> async().options(c)).readEntity(String.class));
        assertEquals("TRACE:", callback(c -> async().trace(c)).readEntity(String.class));
        assertEquals("PATCH:", callback(c -> async().method(PATCH, c)).readEntity(String.class));
        assertEquals("PATCH:x", callback(c -> async().method(PATCH, text(), c)).readEntity(String.class));
    }

    @Test
    void typed_callback_receives_the_entity_read_as_its_type() throws Exception {
        StringCallback callback = new StringCallback();
        Future<String> future = async().get(callback);
        assertEquals(GET_ECHO, callback.result.get(TIMEOUT_S, TimeUnit.SECONDS));
        assertEquals(GET_ECHO, await(future));
    }

    @Test
    void callback_type_is_found_on_a_superclass() throws Exception {
        StringCallback callback = new StringCallback() { };
        async().post(text(), callback);
        assertEquals("POST:x", callback.result.get(TIMEOUT_S, TimeUnit.SECONDS));
    }

    @Test
    void callback_with_an_unresolved_type_receives_the_response() throws Exception {
        GenericCallback<Response> callback = new GenericCallback<>();
        async().get(callback);
        assertEquals(200, callback.result.get(TIMEOUT_S, TimeUnit.SECONDS).getStatus());
    }

    @Test
    void typed_callback_fails_on_an_error_status() {
        StringCallback callback = new StringCallback();
        client.target(server.baseUrl()).path("/missing").request().async().get(callback);
        ExecutionException e = assertThrows(ExecutionException.class,
                () -> callback.result.get(TIMEOUT_S, TimeUnit.SECONDS));
        assertInstanceOf(NotFoundException.class, e.getCause());
    }

    @Test
    void processing_failure_reaches_the_future_and_the_callback_unwrapped() throws Exception {
        String deadUrl;
        try (RecordingServer dead = RecordingServer.echo()) {
            deadUrl = dead.baseUrl();
        }
        AsyncInvoker invoker = client.target(deadUrl).request().async();

        Future<Response> future = invoker.get();
        ExecutionException e = assertThrows(ExecutionException.class, () -> await(future));
        assertInstanceOf(ProcessingException.class, e.getCause());

        ResponseCallback callback = new ResponseCallback();
        invoker.get(callback);
        ExecutionException failed = assertThrows(ExecutionException.class,
                () -> callback.result.get(TIMEOUT_S, TimeUnit.SECONDS));
        assertInstanceOf(ProcessingException.class, failed.getCause());
    }

    // ---- callbacks ---------------------------------------------------------------------------

    @FunctionalInterface
    private interface Call {
        Future<Response> invoke(ResponseCallback callback);
    }

    private static Response callback(Call call) throws Exception {
        ResponseCallback callback = new ResponseCallback();
        Future<Response> future = call.invoke(callback);
        Response response = callback.result.get(TIMEOUT_S, TimeUnit.SECONDS);
        await(future);
        return response;
    }

    private static class ResponseCallback implements InvocationCallback<Response> {
        final CompletableFuture<Response> result = new CompletableFuture<>();
        @Override public void completed(Response response) { result.complete(response); }
        @Override public void failed(Throwable throwable) { result.completeExceptionally(throwable); }
    }

    private static class StringCallback implements InvocationCallback<String> {
        final CompletableFuture<String> result = new CompletableFuture<>();
        @Override public void completed(String entity) { result.complete(entity); }
        @Override public void failed(Throwable throwable) { result.completeExceptionally(throwable); }
    }

    private static class GenericCallback<T> implements InvocationCallback<T> {
        final CompletableFuture<T> result = new CompletableFuture<>();
        @Override public void completed(T entity) { result.complete(entity); }
        @Override public void failed(Throwable throwable) { result.completeExceptionally(throwable); }
    }
}
