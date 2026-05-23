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

import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class CassiniInvocation implements Invocation {

    private final CassiniInvocationBuilder builder;
    private final String method;
    private final Entity<?> entity;

    CassiniInvocation(CassiniInvocationBuilder builder, String method, Entity<?> entity) {
        this.builder = builder;
        this.method = method;
        this.entity = entity;
    }

    @Override
    public Invocation property(String name, Object value) {
        builder.property(name, value);
        return this;
    }

    @Override public Response invoke() { return builder.invoke(method, entity); }

    @Override
    public <T> T invoke(Class<T> responseType) { return invoke().readEntity(responseType); }

    @Override
    public <T> T invoke(GenericType<T> responseType) { return invoke().readEntity(responseType); }

    @Override
    public Future<Response> submit() {
        return CompletableFuture.supplyAsync(this::invoke,
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public <T> Future<T> submit(Class<T> responseType) {
        return CompletableFuture.supplyAsync(() -> invoke(responseType),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public <T> Future<T> submit(GenericType<T> responseType) {
        return CompletableFuture.supplyAsync(() -> invoke(responseType),
                Executors.newVirtualThreadPerTaskExecutor());
    }

    @Override
    public <T> Future<T> submit(InvocationCallback<T> callback) {
        throw new UnsupportedOperationException("InvocationCallback not supported in Cassini Client MVP");
    }
}
