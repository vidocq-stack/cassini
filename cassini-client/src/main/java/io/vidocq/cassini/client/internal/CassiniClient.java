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
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

final class CassiniClient implements Client {

    private final CassiniClientConfiguration configuration;
    private final SSLContext sslContext;
    private final HostnameVerifier hostnameVerifier;
    private final HttpClient httpClient;
    private volatile boolean closed;

    CassiniClient(CassiniClientConfiguration configuration,
                  SSLContext sslContext,
                  HostnameVerifier hostnameVerifier) {
        this.configuration = configuration;
        this.sslContext = sslContext;
        this.hostnameVerifier = hostnameVerifier;
        var builder = HttpClient.newBuilder()
                .executor(configuration.getExecutorService() != null
                        ? configuration.getExecutorService()
                        : Executors.newVirtualThreadPerTaskExecutor())
                .version(HttpClient.Version.HTTP_2);
        if (configuration.getConnectTimeoutMs() > 0) {
            builder.connectTimeout(Duration.ofMillis(configuration.getConnectTimeoutMs()));
        }
        if (sslContext != null) builder.sslContext(sslContext);
        this.httpClient = builder.build();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        httpClient.close();
    }

    @Override
    public WebTarget target(String uri) {
        checkOpen();
        return new CassiniWebTarget(this, UriBuilder.fromUri(uri), new HashMap<>());
    }

    @Override
    public WebTarget target(URI uri) {
        checkOpen();
        return new CassiniWebTarget(this, UriBuilder.fromUri(uri), new HashMap<>());
    }

    @Override
    public WebTarget target(UriBuilder uriBuilder) {
        checkOpen();
        return new CassiniWebTarget(this, uriBuilder.clone(), new HashMap<>());
    }

    @Override
    public WebTarget target(Link link) {
        checkOpen();
        return new CassiniWebTarget(this, UriBuilder.fromLink(link), new HashMap<>());
    }

    @Override
    public Invocation.Builder invocation(Link link) {
        checkOpen();
        return target(link).request();
    }

    @Override public SSLContext getSslContext() { return sslContext; }
    @Override public HostnameVerifier getHostnameVerifier() { return hostnameVerifier; }
    @Override public Configuration getConfiguration() { return configuration; }

    HttpClient httpClient() { return httpClient; }
    CassiniClientConfiguration cassiniConfiguration() { return configuration; }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("Client closed");
    }

    @Override
    public Client property(String name, Object value) {
        configuration.putProperty(name, value);
        return this;
    }

    @Override public Client register(Class<?> componentClass) { configuration.registerComponent(componentClass, null); return this; }
    @Override public Client register(Class<?> componentClass, int priority) { configuration.registerComponent(componentClass, priorityMap(componentClass.getInterfaces(), priority)); return this; }
    @Override public Client register(Class<?> componentClass, Class<?>... contracts) { configuration.registerComponent(componentClass, defaultPriorityMap(contracts)); return this; }
    @Override public Client register(Class<?> componentClass, Map<Class<?>, Integer> contracts) { configuration.registerComponent(componentClass, contracts); return this; }
    @Override public Client register(Object component) { configuration.registerComponent(component, null); return this; }
    @Override public Client register(Object component, int priority) { configuration.registerComponent(component, priorityMap(component.getClass().getInterfaces(), priority)); return this; }
    @Override public Client register(Object component, Class<?>... contracts) { configuration.registerComponent(component, defaultPriorityMap(contracts)); return this; }
    @Override public Client register(Object component, Map<Class<?>, Integer> contracts) { configuration.registerComponent(component, contracts); return this; }

    private static Map<Class<?>, Integer> priorityMap(Class<?>[] contracts, int priority) {
        Map<Class<?>, Integer> m = new HashMap<>();
        for (Class<?> c : contracts) m.put(c, priority);
        return m;
    }

    private static Map<Class<?>, Integer> defaultPriorityMap(Class<?>[] contracts) {
        return priorityMap(contracts, jakarta.ws.rs.Priorities.USER);
    }
}
