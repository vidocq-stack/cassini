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
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.RxInvoker;
import jakarta.ws.rs.client.RxInvokerProvider;
import jakarta.ws.rs.client.SyncInvoker;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CASSINI-024 item 1: {@code Invocation.Builder.rx()} (mandatory, a {@link CompletionStageRxInvoker})
 * and {@code rx(Class)} (an {@link RxInvokerProvider} registered on the client), run on the
 * client's executor (virtual threads by default).
 */
class RxInvokerTest {

    /** Only a guard against a hang: no assertion depends on how long a call takes, so a loaded CI cannot trip it. */
    private static final long TIMEOUT_S = 60;

    private static RecordingServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new RecordingServer((exchange, request) -> {
            int status = request.uri().endsWith("/missing") ? 404 : 200;
            RecordingServer.send(exchange, status, Map.of("Content-Type", "text/plain"),
                    request.method() + ":" + request.body());
        });
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static <T> T await(CompletionStage<T> stage) throws Exception {
        return stage.toCompletableFuture().get(TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static Invocation.Builder request(Client client, String path) {
        return client.target(server.baseUrl() + path).request();
    }

    @Test
    void rx_get_completes_with_the_response() throws Exception {
        try (Client client = ClientBuilder.newClient()) {
            Response response = await(request(client, "/").rx().get());
            assertEquals(200, response.getStatus());
            assertEquals("GET:", response.readEntity(String.class));
        }
    }

    @Test
    void rx_get_typed_reads_the_entity() throws Exception {
        try (Client client = ClientBuilder.newClient()) {
            assertEquals("GET:", await(request(client, "/").rx().get(String.class)));
            assertEquals("GET:", await(request(client, "/").rx().get(new GenericType<String>() { })));
        }
    }

    @Test
    void rx_sends_the_entity_for_put_post_and_method() throws Exception {
        try (Client client = ClientBuilder.newClient()) {
            CompletionStageRxInvoker rx = request(client, "/").rx();
            assertEquals("PUT:x", await(rx.put(Entity.text("x"), String.class)));
            assertEquals("POST:x", await(rx.post(Entity.text("x"), String.class)));
            assertEquals("PATCH:x", await(rx.method("PATCH", Entity.text("x"), String.class)));
            assertEquals("DELETE:", await(rx.delete(String.class)));
            assertEquals(200, await(rx.head()).getStatus());
            assertEquals("OPTIONS:", await(rx.options(String.class)));
            assertEquals("TRACE:", await(rx.trace(String.class)));
        }
    }

    @Test
    void rx_typed_failure_status_fails_the_stage_with_a_web_application_exception() {
        try (Client client = ClientBuilder.newClient()) {
            CompletionStage<String> stage = request(client, "/missing").rx().get(String.class);
            ExecutionException e = assertThrows(ExecutionException.class,
                    () -> stage.toCompletableFuture().get(TIMEOUT_S, TimeUnit.SECONDS));
            assertInstanceOf(NotFoundException.class, e.getCause());
        }
    }

    @Test
    void rx_runs_the_filters_on_a_virtual_thread_by_default() throws Exception {
        AtomicReference<Thread> filterThread = new AtomicReference<>();
        try (Client client = ClientBuilder.newClient()
                .register((ClientRequestFilter) ctx -> filterThread.set(Thread.currentThread()))) {
            await(request(client, "/").rx().get());
            assertNotNull(filterThread.get());
            assertTrue(filterThread.get().isVirtual());
        }
    }

    @Test
    void rx_runs_on_the_executor_given_to_the_builder() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "my-rx-executor"));
        AtomicReference<String> filterThread = new AtomicReference<>();
        try (Client client = ClientBuilder.newBuilder().executorService(executor).build()
                .register((ClientRequestFilter) (ClientRequestContext ctx) ->
                        filterThread.set(Thread.currentThread().getName()))) {
            await(request(client, "/").rx().get());
            assertEquals("my-rx-executor", filterThread.get());
        } finally {
            executor.shutdown();
        }
    }

    @Test
    void rx_on_a_closed_client_is_refused() {
        Client client = ClientBuilder.newClient();
        Invocation.Builder builder = request(client, "/");
        client.close();
        CompletionStageRxInvoker rx = builder.rx();
        assertThrows(IllegalStateException.class, rx::get);
    }

    @Test
    void rx_class_gives_the_built_in_invoker_for_completion_stage() {
        try (Client client = ClientBuilder.newClient()) {
            assertNotNull(request(client, "/").rx(CompletionStageRxInvoker.class));
        }
    }

    @Test
    void rx_class_without_a_provider_is_an_illegal_state() {
        try (Client client = ClientBuilder.newClient()) {
            Invocation.Builder builder = request(client, "/");
            assertThrows(IllegalStateException.class, () -> builder.rx(CustomRxInvoker.class));
        }
    }

    private static final GenericType<String> STRING = new GenericType<>(String.class);

    private static String body(Response response) {
        try (response) {
            return response.readEntity(String.class);
        }
    }

    @Test
    void every_typed_and_untyped_variant_sends_the_method_and_the_entity() throws Exception {
        try (Client client = ClientBuilder.newClient()) {
            Entity<String> x = Entity.text("x");
            CompletionStageRxInvoker rx = request(client, "/").rx();
            assertEquals("PUT:x", body(await(rx.put(x))));
            assertEquals("PUT:x", await(rx.put(x, STRING)));
            assertEquals("POST:x", body(await(rx.post(x))));
            assertEquals("POST:x", await(rx.post(x, STRING)));
            assertEquals("DELETE:", body(await(rx.delete())));
            assertEquals("DELETE:", await(rx.delete(STRING)));
            assertEquals("OPTIONS:", body(await(rx.options())));
            assertEquals("OPTIONS:", await(rx.options(STRING)));
            assertEquals("TRACE:", body(await(rx.trace())));
            assertEquals("TRACE:", await(rx.trace(STRING)));
            assertEquals("PATCH:", body(await(rx.method("PATCH"))));
            assertEquals("PATCH:", await(rx.method("PATCH", String.class)));
            assertEquals("PATCH:", await(rx.method("PATCH", STRING)));
            assertEquals("PATCH:x", body(await(rx.method("PATCH", x))));
            assertEquals("PATCH:x", await(rx.method("PATCH", x, STRING)));
        }
    }

    @Test
    void rx_class_skips_the_registered_components_that_are_not_providers_for_the_type() {
        RxInvokerProvider<CustomRxInvoker> other = new RxInvokerProvider<>() {
            @Override public boolean isProviderFor(Class<?> clazz) { return false; }
            @Override public CustomRxInvoker getRxInvoker(SyncInvoker sync, ExecutorService exec) {
                throw new AssertionError("not a provider for this type");
            }
        };
        try (Client client = ClientBuilder.newClient().register(other)
                .register((ClientRequestFilter) ctx -> { })) {
            Invocation.Builder builder = request(client, "/");
            assertThrows(IllegalStateException.class, () -> builder.rx(CustomRxInvoker.class));
        }
    }

    /** A custom reactive type: any {@link RxInvoker} the application brings a provider for. */
    public interface CustomRxInvoker extends RxInvoker<String> { }

    @Test
    void rx_class_uses_the_registered_provider_with_the_client_executor() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<ExecutorService> receivedExecutor = new AtomicReference<>();
        AtomicReference<SyncInvoker> receivedInvoker = new AtomicReference<>();
        CustomRxInvoker custom = (CustomRxInvoker) Proxy.newProxyInstance(
                CustomRxInvoker.class.getClassLoader(), new Class<?>[] {CustomRxInvoker.class},
                (proxy, method, args) -> "custom");
        RxInvokerProvider<CustomRxInvoker> provider = new RxInvokerProvider<>() {
            @Override public boolean isProviderFor(Class<?> clazz) { return clazz == CustomRxInvoker.class; }
            @Override public CustomRxInvoker getRxInvoker(SyncInvoker sync, ExecutorService exec) {
                receivedInvoker.set(sync);
                receivedExecutor.set(exec);
                return custom;
            }
        };
        try (Client client = ClientBuilder.newBuilder().executorService(executor).build().register(provider)) {
            Invocation.Builder builder = request(client, "/");
            assertSame(custom, builder.rx(CustomRxInvoker.class));
            assertSame(builder, receivedInvoker.get());
            assertSame(executor, receivedExecutor.get());
        } finally {
            executor.shutdown();
        }
    }
}
