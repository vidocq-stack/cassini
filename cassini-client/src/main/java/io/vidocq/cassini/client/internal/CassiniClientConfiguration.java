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

import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;

/**
 * Implémentation de {@link Configuration} pour le {@link jakarta.ws.rs.client.ClientBuilder}
 * Cassini. Stocke les composants enregistrés, les properties, et les paramètres transport
 * (timeouts, executor). Les filtres CLIENT seront appliqués au pipeline en M2d.2 (cf.
 * {@code ClientFilterChain}).
 */
final class CassiniClientConfiguration implements Configuration {

    private final Map<String, Object> properties = new HashMap<>();
    private final Map<Class<?>, Object> instancesByClass = new LinkedHashMap<>();
    private final Map<Class<?>, Map<Class<?>, Integer>> contracts = new HashMap<>();
    private final Set<Feature> enabledFeatures = Collections.newSetFromMap(new IdentityHashMap<>());

    private long connectTimeoutMs = -1;
    private long readTimeoutMs = -1;
    private ExecutorService executorService;

    void putProperty(String name, Object value) {
        properties.put(name, value);
    }

    void registerComponent(Object instance, Map<Class<?>, Integer> componentContracts) {
        if (instance == null) return;
        instancesByClass.put(instance.getClass(), instance);
        if (componentContracts != null) contracts.put(instance.getClass(), componentContracts);
    }

    void registerComponent(Class<?> componentClass, Map<Class<?>, Integer> componentContracts) {
        if (componentClass == null) return;
        try {
            Object instance = componentClass.getDeclaredConstructor().newInstance();
            registerComponent(instance, componentContracts);
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot instantiate provider " + componentClass.getName(), e);
        }
    }

    long getConnectTimeoutMs() { return connectTimeoutMs; }
    void setConnectTimeoutMs(long ms) { this.connectTimeoutMs = ms; }

    long getReadTimeoutMs() { return readTimeoutMs; }
    void setReadTimeoutMs(long ms) { this.readTimeoutMs = ms; }

    ExecutorService getExecutorService() { return executorService; }
    void setExecutorService(ExecutorService executor) { this.executorService = executor; }

    @Override public RuntimeType getRuntimeType() { return RuntimeType.CLIENT; }
    @Override public Map<String, Object> getProperties() { return Collections.unmodifiableMap(properties); }
    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return Collections.unmodifiableSet(properties.keySet()); }
    @Override public boolean isEnabled(Feature feature) { return enabledFeatures.contains(feature); }
    @Override public boolean isEnabled(Class<? extends Feature> featureClass) {
        for (Feature f : enabledFeatures) {
            if (featureClass.isInstance(f)) return true;
        }
        return false;
    }
    @Override public boolean isRegistered(Object component) { return instancesByClass.containsValue(component); }
    @Override public boolean isRegistered(Class<?> componentClass) { return instancesByClass.containsKey(componentClass); }
    @Override public Map<Class<?>, Integer> getContracts(Class<?> componentClass) {
        Map<Class<?>, Integer> c = contracts.get(componentClass);
        return c == null ? Map.of() : Collections.unmodifiableMap(c);
    }
    @Override public Set<Class<?>> getClasses() { return Collections.unmodifiableSet(instancesByClass.keySet()); }
    @Override public Set<Object> getInstances() { return Set.copyOf(instancesByClass.values()); }
}
