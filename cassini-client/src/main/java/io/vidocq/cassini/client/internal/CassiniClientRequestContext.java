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
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Mutable {@link ClientRequestContext} implementation — feeds the
 * {@link jakarta.ws.rs.client.ClientRequestFilter}s which can mutate URI, method,
 * headers, entity, properties. {@code abortWith(Response)} aborts the chain
 * and short-circuits the transport (the aborted response is returned to the caller).
 *
 * <p>Methods backed by Date/Locale/Cookies/Annotations return values derived from
 * the headers — no cache. The context is created once per call to
 * {@code CassiniInvocationBuilder.invoke(method, entity)}.</p>
 */
final class CassiniClientRequestContext implements ClientRequestContext {

    private final CassiniClient client;
    private URI uri;
    private String method;
    private final MultivaluedMap<String, Object> headers;
    private Object entity;
    private Class<?> entityClass;
    private Type entityType;
    private Annotation[] entityAnnotations = new Annotation[0];
    private MediaType entityMediaType;
    private final Map<String, Object> properties = new HashMap<>();
    private Response abortResponse;

    CassiniClientRequestContext(CassiniClient client, URI uri, String method,
                                 MultivaluedMap<String, Object> headers,
                                 Object entity, MediaType entityMediaType) {
        this.client = client;
        this.uri = uri;
        this.method = method;
        this.headers = headers;
        this.entity = entity;
        this.entityClass = entity == null ? null : entity.getClass();
        this.entityType = this.entityClass;
        this.entityMediaType = entityMediaType;
    }

    Response abortResponse() { return abortResponse; }
    Object getOriginalEntity() { return entity; }
    MediaType getEntityMediaType() { return entityMediaType; }

    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return Collections.unmodifiableSet(properties.keySet()); }
    @Override public void setProperty(String name, Object object) { properties.put(name, object); }
    @Override public void removeProperty(String name) { properties.remove(name); }

    @Override public URI getUri() { return uri; }
    @Override public void setUri(URI uri) { this.uri = uri; }

    @Override public String getMethod() { return method; }
    @Override public void setMethod(String method) { this.method = method; }

    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }

    @Override
    public MultivaluedMap<String, String> getStringHeaders() {
        jakarta.ws.rs.core.MultivaluedHashMap<String, String> out = new jakarta.ws.rs.core.MultivaluedHashMap<>();
        for (var entry : headers.entrySet()) {
            for (Object v : entry.getValue()) {
                if (v != null) out.add(entry.getKey(), v.toString());
            }
        }
        return out;
    }

    @Override
    public String getHeaderString(String name) {
        List<Object> vs = headers.get(name);
        if (vs == null || vs.isEmpty()) return null;
        List<String> parts = new ArrayList<>(vs.size());
        for (Object v : vs) if (v != null) parts.add(v.toString());
        return String.join(",", parts);
    }

    @Override
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        String header = getHeaderString(name);
        if (header == null) return false;
        for (String token : header.split(valueSeparatorRegex)) {
            if (valuePredicate.test(token.trim())) return true;
        }
        return false;
    }

    @Override public Date getDate() { return null; }
    @Override public Locale getLanguage() { return null; }
    @Override public MediaType getMediaType() { return entityMediaType; }

    @Override
    public List<MediaType> getAcceptableMediaTypes() {
        String accept = getHeaderString(HttpHeaders.ACCEPT);
        if (accept == null) return List.of(MediaType.WILDCARD_TYPE);
        List<MediaType> out = new ArrayList<>();
        for (String tok : accept.split(",")) out.add(MediaType.valueOf(tok.trim()));
        return out;
    }

    @Override
    public List<Locale> getAcceptableLanguages() {
        String al = getHeaderString(HttpHeaders.ACCEPT_LANGUAGE);
        if (al == null) return List.of();
        List<Locale> out = new ArrayList<>();
        for (String tok : al.split(",")) out.add(Locale.forLanguageTag(tok.trim()));
        return out;
    }

    @Override
    public Map<String, Cookie> getCookies() { return Map.of(); }

    @Override public boolean hasEntity() { return entity != null; }
    @Override public Object getEntity() { return entity; }
    @Override public Class<?> getEntityClass() { return entityClass; }
    @Override public Type getEntityType() { return entityType; }

    @Override
    public void setEntity(Object entity) {
        this.entity = entity;
        this.entityClass = entity == null ? null : entity.getClass();
        this.entityType = this.entityClass;
    }

    @Override
    public void setEntity(Object entity, Annotation[] annotations, MediaType mediaType) {
        setEntity(entity);
        this.entityAnnotations = annotations == null ? new Annotation[0] : annotations;
        this.entityMediaType = mediaType;
        if (mediaType != null) {
            headers.putSingle(HttpHeaders.CONTENT_TYPE, mediaType.toString());
        }
    }

    @Override public Annotation[] getEntityAnnotations() { return entityAnnotations; }

    @Override public OutputStream getEntityStream() {
        throw new UnsupportedOperationException("getEntityStream not supported in Cassini Client MVP — use getEntity()");
    }

    @Override public void setEntityStream(OutputStream outputStream) {
        throw new UnsupportedOperationException("setEntityStream not supported in Cassini Client MVP");
    }

    @Override public Client getClient() { return client; }
    @Override public Configuration getConfiguration() { return client.getConfiguration(); }

    @Override
    public void abortWith(Response response) {
        this.abortResponse = response;
    }
}
