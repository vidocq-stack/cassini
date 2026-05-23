/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Configuration;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Implémentation Cassini de {@link ClientBuilder} — découverte via
 * {@code META-INF/services/jakarta.ws.rs.client.ClientBuilder} (classpath) et via
 * {@code provides} JPMS (module-path).
 *
 * <p>Le constructeur public sans argument est requis par le contrat
 * {@code java.util.ServiceLoader}.</p>
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
        return new CassiniClient(configuration, sslContext, hostnameVerifier);
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
