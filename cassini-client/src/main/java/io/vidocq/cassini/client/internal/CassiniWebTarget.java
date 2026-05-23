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

import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

final class CassiniWebTarget implements WebTarget {

    private final CassiniClient client;
    private final UriBuilder uriBuilder;
    private final Map<String, Object> templateValues;

    CassiniWebTarget(CassiniClient client, UriBuilder uriBuilder, Map<String, Object> templateValues) {
        this.client = client;
        this.uriBuilder = uriBuilder;
        this.templateValues = templateValues;
    }

    @Override
    public URI getUri() {
        return templateValues.isEmpty() ? uriBuilder.build() : uriBuilder.buildFromMap(templateValues);
    }

    @Override public UriBuilder getUriBuilder() { return uriBuilder.clone(); }

    @Override
    public WebTarget path(String path) {
        return new CassiniWebTarget(client, uriBuilder.clone().path(path), new HashMap<>(templateValues));
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value) {
        Map<String, Object> m = new HashMap<>(templateValues);
        m.put(name, value);
        return new CassiniWebTarget(client, uriBuilder.clone(), m);
    }

    @Override
    public WebTarget resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        return resolveTemplate(name, value);
    }

    @Override
    public WebTarget resolveTemplateFromEncoded(String name, Object value) {
        return resolveTemplate(name, value);
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues) {
        Map<String, Object> m = new HashMap<>(this.templateValues);
        m.putAll(templateValues);
        return new CassiniWebTarget(client, uriBuilder.clone(), m);
    }

    @Override
    public WebTarget resolveTemplates(Map<String, Object> templateValues, boolean encodeSlashInPath) {
        return resolveTemplates(templateValues);
    }

    @Override
    public WebTarget resolveTemplatesFromEncoded(Map<String, Object> templateValues) {
        return resolveTemplates(templateValues);
    }

    @Override
    public WebTarget matrixParam(String name, Object... values) {
        return new CassiniWebTarget(client, uriBuilder.clone().matrixParam(name, values), new HashMap<>(templateValues));
    }

    @Override
    public WebTarget queryParam(String name, Object... values) {
        return new CassiniWebTarget(client, uriBuilder.clone().queryParam(name, values), new HashMap<>(templateValues));
    }

    @Override
    public Invocation.Builder request() {
        return new CassiniInvocationBuilder(client, getUri(), new String[]{MediaType.WILDCARD});
    }

    @Override
    public Invocation.Builder request(String... acceptedResponseTypes) {
        return new CassiniInvocationBuilder(client, getUri(), acceptedResponseTypes);
    }

    @Override
    public Invocation.Builder request(MediaType... acceptedResponseTypes) {
        String[] s = new String[acceptedResponseTypes.length];
        for (int i = 0; i < acceptedResponseTypes.length; i++) s[i] = acceptedResponseTypes[i].toString();
        return new CassiniInvocationBuilder(client, getUri(), s);
    }

    @Override public Configuration getConfiguration() { return client.getConfiguration(); }

    @Override
    public WebTarget property(String name, Object value) {
        client.property(name, value);
        return this;
    }

    @Override public WebTarget register(Class<?> componentClass) { client.register(componentClass); return this; }
    @Override public WebTarget register(Class<?> componentClass, int priority) { client.register(componentClass, priority); return this; }
    @Override public WebTarget register(Class<?> componentClass, Class<?>... contracts) { client.register(componentClass, contracts); return this; }
    @Override public WebTarget register(Class<?> componentClass, Map<Class<?>, Integer> contracts) { client.register(componentClass, contracts); return this; }
    @Override public WebTarget register(Object component) { client.register(component); return this; }
    @Override public WebTarget register(Object component, int priority) { client.register(component, priority); return this; }
    @Override public WebTarget register(Object component, Class<?>... contracts) { client.register(component, contracts); return this; }
    @Override public WebTarget register(Object component, Map<Class<?>, Integer> contracts) { client.register(component, contracts); return this; }
}
