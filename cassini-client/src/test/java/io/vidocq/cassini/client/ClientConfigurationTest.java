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
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.Test;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClientBuilder} and {@link Configuration} of the built {@link Client}. */
class ClientConfigurationTest {

    private static final String NAME = "p";
    private static final String VALUE = "v";
    private static final String LATE = "late";

    @Test
    void client_configuration_is_a_client_one() {
        try (Client client = ClientBuilder.newClient()) {
            Configuration config = client.getConfiguration();
            assertEquals(RuntimeType.CLIENT, config.getRuntimeType());
            assertTrue(config.getProperties().isEmpty());
            assertTrue(config.getClasses().isEmpty());
            assertTrue(config.getInstances().isEmpty());
            assertTrue(config.getContracts(Marker.class).isEmpty());
            assertFalse(config.isEnabled(UnusedFeature.class));
            assertFalse(config.isEnabled(new UnusedFeature()));
        }
    }

    @Test
    void properties_set_on_the_builder_reach_the_client() {
        try (Client client = ClientBuilder.newBuilder().property(NAME, VALUE).build()) {
            assertEquals(VALUE, client.getConfiguration().getProperty(NAME));
            assertEquals(Set.of(NAME), Set.copyOf(client.getConfiguration().getPropertyNames()));
            assertEquals(Map.of(NAME, VALUE), client.getConfiguration().getProperties());
            assertSame(client, client.property("q", 1));
            assertEquals(1, client.getConfiguration().getProperty("q"));
        }
    }

    @Test
    void builder_registrations_record_classes_instances_and_contracts() {
        Marker marker = new Marker();
        ClientBuilder builder = ClientBuilder.newBuilder()
                .register(Marker.class)
                .register(Other.class, 7)
                .register(Third.class, ClientRequestFilter.class)
                .register(Fourth.class, Map.of(ClientRequestFilter.class, 9));
        Configuration config = builder.getConfiguration();
        assertTrue(config.isRegistered(Marker.class));
        assertEquals(Map.of(ClientRequestFilter.class, 7), config.getContracts(Other.class));
        assertEquals(Map.of(ClientRequestFilter.class, Priorities.USER), config.getContracts(Third.class));
        assertEquals(Map.of(ClientRequestFilter.class, 9), config.getContracts(Fourth.class));
        assertFalse(config.isRegistered(marker));

        builder.register(marker).register(new Other(), 3).register(new Third(), ClientRequestFilter.class)
                .register(new Fourth(), Map.of(ClientRequestFilter.class, 4));
        assertTrue(config.isRegistered(marker));
        assertEquals(Map.of(ClientRequestFilter.class, 3), config.getContracts(Other.class));
        assertEquals(Map.of(ClientRequestFilter.class, 4), config.getContracts(Fourth.class));
        assertEquals(4, config.getClasses().size());
        assertEquals(4, config.getInstances().size());
    }

    @Test
    void client_registrations_record_classes_instances_and_contracts() {
        try (Client client = ClientBuilder.newClient()) {
            client.register(Marker.class).register(Other.class, 7).register(Third.class, ClientRequestFilter.class)
                    .register(Fourth.class, Map.of(ClientRequestFilter.class, 9));
            Configuration config = client.getConfiguration();
            assertTrue(config.isRegistered(Fourth.class));
            assertEquals(Map.of(ClientRequestFilter.class, 7), config.getContracts(Other.class));

            Marker marker = new Marker();
            client.register(marker).register(new Other(), 3).register(new Third(), ClientRequestFilter.class)
                    .register(new Fourth(), Map.of(ClientRequestFilter.class, 4));
            assertTrue(config.isRegistered(marker));
            assertEquals(Map.of(ClientRequestFilter.class, 3), config.getContracts(Other.class));
        }
    }

    @Test
    void a_class_without_a_nullary_constructor_cannot_be_registered() {
        ClientBuilder builder = ClientBuilder.newBuilder();
        assertThrows(IllegalArgumentException.class, () -> builder.register(NoDefaultConstructor.class));
    }

    @Test
    void registered_priority_orders_the_request_filters() throws Exception {
        List<String> order = new ArrayList<>();
        try (RecordingServer server = RecordingServer.echo();
             Client client = ClientBuilder.newClient()) {
            client.register((ClientRequestFilter) ctx -> order.add(LATE), 900);
            client.register(new Recording(order), Map.of(ClientRequestFilter.class, 100));
            client.target(server.baseUrl()).request().get();
        }
        assertEquals(List.of("early", LATE), order);
    }

    @Test
    void with_config_copies_another_configuration() {
        Configuration source = ClientBuilder.newBuilder().property(NAME, VALUE)
                .register(new Other(), 5).getConfiguration();
        try (Client client = ClientBuilder.newBuilder().withConfig(new ForeignConfiguration(source)).build()) {
            assertEquals(VALUE, client.getConfiguration().getProperty(NAME));
            assertEquals(Map.of(ClientRequestFilter.class, 5), client.getConfiguration().getContracts(Other.class));
        }
        try (Client client = ClientBuilder.newBuilder().withConfig(source).build()) {
            assertEquals(VALUE, client.getConfiguration().getProperty(NAME));
        }
        try (Client client = ClientBuilder.newBuilder().property(NAME, VALUE).withConfig(null).build()) {
            assertEquals(VALUE, client.getConfiguration().getProperty(NAME));
        }
    }

    @Test
    void ssl_settings_are_exposed_by_the_client() throws Exception {
        SSLContext ssl = SSLContext.getDefault();
        HostnameVerifier verifier = (_, _) -> true;
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        ClientBuilder builder = ClientBuilder.newBuilder();
        assertSame(builder, builder.keyStore(store, new char[0]));
        assertSame(builder, builder.trustStore(store));
        try (Client client = builder.sslContext(ssl).hostnameVerifier(verifier).build()) {
            assertSame(ssl, client.getSslContext());
            assertSame(verifier, client.getHostnameVerifier());
        }
        try (Client client = ClientBuilder.newClient()) {
            assertNull(client.getSslContext());
            assertNull(client.getHostnameVerifier());
        }
    }

    @Test
    void read_timeout_fails_a_slow_response() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        try (RecordingServer server = new RecordingServer((exchange, _) -> {
            awaitQuietly(release);
            RecordingServer.send(exchange, 200, Map.of(), LATE);
        }); Client client = ClientBuilder.newBuilder().readTimeout(100, TimeUnit.MILLISECONDS)
                .connectTimeout(2, TimeUnit.SECONDS).build()) {
            var builder = client.target(server.baseUrl()).request();
            assertThrows(ProcessingException.class, builder::get);
        } finally {
            release.countDown();
        }
    }

    @Test
    void executor_service_backs_the_http_client() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        ClientBuilder builder = ClientBuilder.newBuilder();
        assertSame(builder, builder.scheduledExecutorService(scheduler));
        try (RecordingServer server = RecordingServer.echo();
             Client client = builder.executorService(executor).build()) {
            assertEquals("GET:", client.target(server.baseUrl()).request().get(String.class));
        } finally {
            executor.shutdown();
            scheduler.shutdown();
        }
    }

    @Test
    void closed_client_refuses_new_targets() {
        Client client = ClientBuilder.newClient();
        client.close();
        client.close();
        assertThrows(IllegalStateException.class, () -> client.target("http://localhost/"));
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    public static class Marker {
    }

    public static class Other implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) { /* registration fixture */ }
    }

    public static class Third implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) { /* registration fixture */ }
    }

    public static class Fourth implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) { /* registration fixture */ }
    }

    public static class NoDefaultConstructor {
        private final String value;
        public NoDefaultConstructor(String value) { this.value = value; }
        @Override public String toString() { return value; }
    }

    @Priority(Priorities.USER)
    static class Recording implements ClientRequestFilter {
        private final List<String> order;
        Recording(List<String> order) { this.order = order; }
        @Override public void filter(ClientRequestContext ctx) { order.add("early"); }
    }

    public static class UnusedFeature implements Feature {
        @Override public boolean configure(FeatureContext context) { return true; }
    }

    /** A {@link Configuration} from another implementation: {@code withConfig} must copy it. */
    private record ForeignConfiguration(Configuration delegate) implements Configuration {
        @Override public RuntimeType getRuntimeType() { return delegate.getRuntimeType(); }
        @Override public Map<String, Object> getProperties() { return delegate.getProperties(); }
        @Override public Object getProperty(String name) { return delegate.getProperty(name); }
        @Override public Collection<String> getPropertyNames() { return delegate.getPropertyNames(); }
        @Override public boolean isEnabled(Feature feature) { return delegate.isEnabled(feature); }
        @Override public boolean isEnabled(Class<? extends Feature> featureClass) { return delegate.isEnabled(featureClass); }
        @Override public boolean isRegistered(Object component) { return delegate.isRegistered(component); }
        @Override public boolean isRegistered(Class<?> componentClass) { return delegate.isRegistered(componentClass); }
        @Override public Map<Class<?>, Integer> getContracts(Class<?> componentClass) { return delegate.getContracts(componentClass); }
        @Override public Set<Class<?>> getClasses() { return delegate.getClasses(); }
        @Override public Set<Object> getInstances() { return delegate.getInstances(); }
    }
}
