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
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;

import java.util.Map;

/**
 * {@link FeatureContext} adapter that delegates all
 * {@code register}/{@code property} operations to the target {@link Client} — used by
 * {@link CassiniClient#build()} to invoke
 * {@link jakarta.ws.rs.core.Feature#configure(FeatureContext)} on each Feature
 * discovered via {@link java.util.ServiceLoader}.
 */
final class CassiniClientFeatureContext implements FeatureContext {

    private final Client client;

    CassiniClientFeatureContext(Client client) { this.client = client; }

    @Override public Configuration getConfiguration() { return client.getConfiguration(); }

    @Override public FeatureContext property(String name, Object value) { client.property(name, value); return this; }

    @Override public FeatureContext register(Class<?> componentClass) { client.register(componentClass); return this; }
    @Override public FeatureContext register(Class<?> componentClass, int priority) { client.register(componentClass, priority); return this; }
    @Override public FeatureContext register(Class<?> componentClass, Class<?>... contracts) { client.register(componentClass, contracts); return this; }
    @Override public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) { client.register(componentClass, contracts); return this; }
    @Override public FeatureContext register(Object component) { client.register(component); return this; }
    @Override public FeatureContext register(Object component, int priority) { client.register(component, priority); return this; }
    @Override public FeatureContext register(Object component, Class<?>... contracts) { client.register(component, contracts); return this; }
    @Override public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) { client.register(component, contracts); return this; }
}
