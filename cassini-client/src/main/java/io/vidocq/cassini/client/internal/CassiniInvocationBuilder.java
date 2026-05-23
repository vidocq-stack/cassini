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

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.RxInvoker;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;

final class CassiniInvocationBuilder implements Invocation.Builder {

    private final CassiniClient client;
    private final URI uri;
    private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    CassiniInvocationBuilder(CassiniClient client, URI uri, String[] acceptedTypes) {
        this.client = client;
        this.uri = uri;
        if (acceptedTypes != null && acceptedTypes.length > 0) {
            for (String type : acceptedTypes) headers.add(HttpHeaders.ACCEPT, type);
        }
    }

    // ---- SyncInvoker ----------------------------------------------------------------------

    @Override public Response get() { return invoke("GET", null); }
    @Override public <T> T get(Class<T> responseType) { return get().readEntity(responseType); }
    @Override public <T> T get(GenericType<T> responseType) { return get().readEntity(responseType); }

    @Override public Response put(Entity<?> entity) { return invoke("PUT", entity); }
    @Override public <T> T put(Entity<?> entity, Class<T> responseType) { return put(entity).readEntity(responseType); }
    @Override public <T> T put(Entity<?> entity, GenericType<T> responseType) { return put(entity).readEntity(responseType); }

    @Override public Response post(Entity<?> entity) { return invoke("POST", entity); }
    @Override public <T> T post(Entity<?> entity, Class<T> responseType) { return post(entity).readEntity(responseType); }
    @Override public <T> T post(Entity<?> entity, GenericType<T> responseType) { return post(entity).readEntity(responseType); }

    @Override public Response delete() { return invoke("DELETE", null); }
    @Override public <T> T delete(Class<T> responseType) { return delete().readEntity(responseType); }
    @Override public <T> T delete(GenericType<T> responseType) { return delete().readEntity(responseType); }

    @Override public Response head() { return invoke("HEAD", null); }

    @Override public Response options() { return invoke("OPTIONS", null); }
    @Override public <T> T options(Class<T> responseType) { return options().readEntity(responseType); }
    @Override public <T> T options(GenericType<T> responseType) { return options().readEntity(responseType); }

    @Override public Response trace() { return invoke("TRACE", null); }
    @Override public <T> T trace(Class<T> responseType) { return trace().readEntity(responseType); }
    @Override public <T> T trace(GenericType<T> responseType) { return trace().readEntity(responseType); }

    @Override public Response method(String name) { return invoke(name, null); }
    @Override public <T> T method(String name, Class<T> responseType) { return method(name).readEntity(responseType); }
    @Override public <T> T method(String name, GenericType<T> responseType) { return method(name).readEntity(responseType); }
    @Override public Response method(String name, Entity<?> entity) { return invoke(name, entity); }
    @Override public <T> T method(String name, Entity<?> entity, Class<T> responseType) { return method(name, entity).readEntity(responseType); }
    @Override public <T> T method(String name, Entity<?> entity, GenericType<T> responseType) { return method(name, entity).readEntity(responseType); }

    // ---- Invocation.Builder ---------------------------------------------------------------

    @Override public Invocation build(String method) { return new CassiniInvocation(this, method, null); }
    @Override public Invocation build(String method, Entity<?> entity) { return new CassiniInvocation(this, method, entity); }
    @Override public Invocation buildGet() { return build("GET"); }
    @Override public Invocation buildDelete() { return build("DELETE"); }
    @Override public Invocation buildPost(Entity<?> entity) { return build("POST", entity); }
    @Override public Invocation buildPut(Entity<?> entity) { return build("PUT", entity); }

    @Override
    public AsyncInvoker async() {
        throw new UnsupportedOperationException("Async invoker not supported in Cassini Client MVP — use submit() via Invocation");
    }

    @Override
    public Invocation.Builder accept(String... mediaTypes) {
        headers.remove(HttpHeaders.ACCEPT);
        for (String t : mediaTypes) headers.add(HttpHeaders.ACCEPT, t);
        return this;
    }

    @Override
    public Invocation.Builder accept(MediaType... mediaTypes) {
        headers.remove(HttpHeaders.ACCEPT);
        for (MediaType t : mediaTypes) headers.add(HttpHeaders.ACCEPT, t.toString());
        return this;
    }

    @Override
    public Invocation.Builder acceptLanguage(Locale... locales) {
        headers.remove(HttpHeaders.ACCEPT_LANGUAGE);
        for (Locale l : locales) headers.add(HttpHeaders.ACCEPT_LANGUAGE, l.toLanguageTag());
        return this;
    }

    @Override
    public Invocation.Builder acceptLanguage(String... locales) {
        headers.remove(HttpHeaders.ACCEPT_LANGUAGE);
        for (String l : locales) headers.add(HttpHeaders.ACCEPT_LANGUAGE, l);
        return this;
    }

    @Override
    public Invocation.Builder acceptEncoding(String... encodings) {
        headers.remove(HttpHeaders.ACCEPT_ENCODING);
        for (String e : encodings) headers.add(HttpHeaders.ACCEPT_ENCODING, e);
        return this;
    }

    @Override
    public Invocation.Builder cookie(Cookie cookie) {
        headers.add(HttpHeaders.COOKIE, cookie.toString());
        return this;
    }

    @Override
    public Invocation.Builder cookie(String name, String value) {
        headers.add(HttpHeaders.COOKIE, name + "=" + value);
        return this;
    }

    @Override
    public Invocation.Builder cacheControl(CacheControl cacheControl) {
        headers.add(HttpHeaders.CACHE_CONTROL, cacheControl.toString());
        return this;
    }

    @Override
    public Invocation.Builder header(String name, Object value) {
        if (value == null) headers.remove(name);
        else headers.add(name, value);
        return this;
    }

    @Override
    public Invocation.Builder headers(MultivaluedMap<String, Object> headers) {
        this.headers.clear();
        if (headers != null) this.headers.putAll(headers);
        return this;
    }

    @Override
    public Invocation.Builder property(String name, Object value) {
        client.cassiniConfiguration().putProperty(name, value);
        return this;
    }

    @Override
    public CompletionStageRxInvoker rx() {
        throw new UnsupportedOperationException("Rx invoker not supported in Cassini Client MVP");
    }

    @Override
    public <T extends RxInvoker> T rx(Class<T> clazz) {
        throw new UnsupportedOperationException("Rx invoker not supported in Cassini Client MVP");
    }

    // ---- Internal -------------------------------------------------------------------------

    Response invoke(String method, Entity<?> entity) {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri);
            long readMs = client.cassiniConfiguration().getReadTimeoutMs();
            if (readMs > 0) requestBuilder.timeout(Duration.ofMillis(readMs));

            applyHeaders(requestBuilder);
            applyBody(requestBuilder, method, entity);

            HttpResponse<byte[]> httpResponse = client.httpClient().send(
                    requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return CassiniClientResponse.from(httpResponse);
        } catch (IOException e) {
            throw new ProcessingException("HTTP I/O failed for " + method + " " + uri, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProcessingException("Interrupted during " + method + " " + uri, e);
        }
    }

    private void applyHeaders(HttpRequest.Builder builder) {
        for (var entry : headers.entrySet()) {
            String name = entry.getKey();
            if (isRestricted(name)) continue;
            for (Object v : entry.getValue()) {
                if (v != null) builder.header(name, v.toString());
            }
        }
    }

    private void applyBody(HttpRequest.Builder builder, String method, Entity<?> entity) {
        if (entity == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
            return;
        }
        // Sérialisation MVP basique — String / byte[]. MessageBodyRegistry intégré en commit #3.
        Object value = entity.getEntity();
        byte[] payload;
        if (value == null) payload = new byte[0];
        else if (value instanceof byte[] b) payload = b;
        else payload = value.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);

        if (entity.getMediaType() != null) {
            builder.header(HttpHeaders.CONTENT_TYPE, entity.getMediaType().toString());
        }
        builder.method(method, HttpRequest.BodyPublishers.ofByteArray(payload));
    }

    private static boolean isRestricted(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("connection") || n.equals("content-length") || n.equals("expect")
                || n.equals("host") || n.equals("upgrade");
    }
}
