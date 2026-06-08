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
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Cassini implementation of {@link ClientBuilder} — discovered via
 * {@code META-INF/services/jakarta.ws.rs.client.ClientBuilder} (classpath) and
 * via {@code provides} JPMS (module-path).
 *
 * <p>The nullary public constructor is required by the
 * {@code java.util.ServiceLoader} contract.</p>
 */
public final class CassiniClientBuilder extends ClientBuilder {

    private CassiniClientConfiguration configuration = new CassiniClientConfiguration();
    private SSLContext sslContext;
    private HostnameVerifier hostnameVerifier;

    public CassiniClientBuilder() {
        // ServiceLoader contract: nullary public constructor required.
    }

    @Override
    public ClientBuilder withConfig(Configuration config) {
        if (config instanceof CassiniClientConfiguration ccc) {
            this.configuration = ccc;
        } else if (config != null) {
            CassiniClientConfiguration copy = new CassiniClientConfiguration();
            for (String name : config.getPropertyNames()) copy.putProperty(name, config.getProperty(name));
            for (Object instance : config.getInstances()) copy.registerComponent(instance, config.getContracts(instance.getClass()));
            this.configuration = copy;
        }
        return this;
    }

    @Override
    public ClientBuilder sslContext(SSLContext sslContext) {
        this.sslContext = sslContext;
        return this;
    }

    @Override
    public ClientBuilder keyStore(KeyStore keyStore, char[] password) {
        return this;
    }

    @Override
    public ClientBuilder trustStore(KeyStore trustStore) {
        return this;
    }

    @Override
    public ClientBuilder hostnameVerifier(HostnameVerifier verifier) {
        this.hostnameVerifier = verifier;
        return this;
    }

    @Override
    public ClientBuilder executorService(ExecutorService executor) {
        configuration.setExecutorService(executor);
        return this;
    }

    @Override
    public ClientBuilder scheduledExecutorService(ScheduledExecutorService executor) {
        return this;
    }

    @Override
    public ClientBuilder connectTimeout(long timeout, TimeUnit unit) {
        configuration.setConnectTimeoutMs(unit.toMillis(timeout));
        return this;
    }

    @Override
    public ClientBuilder readTimeout(long timeout, TimeUnit unit) {
        configuration.setReadTimeoutMs(unit.toMillis(timeout));
        return this;
    }

    @Override
    public Client build() {
        CassiniClient client = new CassiniClient(configuration, sslContext, hostnameVerifier);
        // Auto-discovery of jakarta.ws.rs.core.Feature via ServiceLoader:
        // allows third-party modules (humboldt-rest, future OTel/auth/log filters) to
        // self-register without explicit caller intervention. MP Telemetry 2.1
        // requires this behavior for tests that call ClientBuilder.newClient() directly.
        CassiniClientFeatureContext featureCtx = new CassiniClientFeatureContext(client);
        for (Feature feature : ServiceLoader.load(Feature.class, Thread.currentThread().getContextClassLoader())) {
            if (configuration.isRegistered(feature.getClass())) continue;
            feature.configure(featureCtx);
        }
        return client;
    }

    @Override
    public Configuration getConfiguration() {
        return configuration;
    }

    @Override
    public ClientBuilder property(String name, Object value) {
        configuration.putProperty(name, value);
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass) {
        configuration.registerComponent(componentClass, null);
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, int priority) {
        configuration.registerComponent(componentClass, priorityMap(componentClass.getInterfaces(), priority));
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Class<?>... contractTypes) {
        configuration.registerComponent(componentClass, defaultPriorityMap(contractTypes));
        return this;
    }

    @Override
    public ClientBuilder register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        configuration.registerComponent(componentClass, contracts);
        return this;
    }

    @Override
    public ClientBuilder register(Object component) {
        configuration.registerComponent(component, null);
        return this;
    }

    @Override
    public ClientBuilder register(Object component, int priority) {
        configuration.registerComponent(component, priorityMap(component.getClass().getInterfaces(), priority));
        return this;
    }

    @Override
    public ClientBuilder register(Object component, Class<?>... contractTypes) {
        configuration.registerComponent(component, defaultPriorityMap(contractTypes));
        return this;
    }

    @Override
    public ClientBuilder register(Object component, Map<Class<?>, Integer> contracts) {
        configuration.registerComponent(component, contracts);
        return this;
    }

    private static Map<Class<?>, Integer> priorityMap(Class<?>[] contracts, int priority) {
        Map<Class<?>, Integer> m = new HashMap<>();
        for (Class<?> c : contracts) m.put(c, priority);
        return m;
    }

    private static Map<Class<?>, Integer> defaultPriorityMap(Class<?>[] contracts) {
        return priorityMap(contracts, jakarta.ws.rs.Priorities.USER);
    }
}
