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

import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Implémentation de {@link AsyncInvoker} basée sur {@link CompletableFuture} +
 * virtual threads. Chaque méthode délègue à la méthode sync correspondante de
 * {@link CassiniInvocationBuilder} en l'exécutant dans un virtual thread.
 *
 * <p>Le pipeline des filtres CLIENT (request/response) s'exécute entièrement
 * dans le thread async — les spans {@code kind=CLIENT} posés par
 * {@code HumboldtClientRequestFilter}/{@code HumboldtClientResponseFilter} sont
 * donc bien créés et terminés autour de chaque appel async.</p>
 *
 * <p>{@link InvocationCallback} : la méthode {@code completed(T)} est invoquée
 * en succès, {@code failed(Throwable)} en cas d'erreur HTTP I/O.</p>
 */
final class CassiniAsyncInvoker implements AsyncInvoker {

    private final CassiniInvocationBuilder builder;
    private final Executor executor;

    CassiniAsyncInvoker(CassiniInvocationBuilder builder) {
        this.builder = builder;
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    // ---- helpers ---------------------------------------------------------------------------

    private Future<Response> async(String method, Entity<?> entity) {
        return CompletableFuture.supplyAsync(() -> builder.invoke(method, entity), executor);
    }

    @SuppressWarnings("unchecked")
    private <T> Future<T> asyncTyped(String method, Entity<?> entity, Class<T> type) {
        return CompletableFuture.supplyAsync(
                () -> (T) builder.invoke(method, entity).readEntity(type), executor);
    }

    @SuppressWarnings("unchecked")
    private <T> Future<T> asyncTyped(String method, Entity<?> entity, GenericType<T> type) {
        return CompletableFuture.supplyAsync(
                () -> (T) builder.invoke(method, entity).readEntity(type.getRawType()), executor);
    }

    private <T> Future<T> asyncCallback(String method, Entity<?> entity, InvocationCallback<T> callback) {
        @SuppressWarnings("unchecked")
        Class<T> responseType = (Class<T>) Response.class;
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try {
                @SuppressWarnings("unchecked")
                T result = (T) builder.invoke(method, entity);
                return result;
            } catch (RuntimeException re) {
                throw re;
            }
        }, executor);
        future.whenComplete((res, ex) -> {
            if (ex != null) callback.failed(ex);
            else callback.completed(res);
        });
        return future;
    }

    // ---- GET -------------------------------------------------------------------------------

    @Override public Future<Response> get() { return async("GET", null); }
    @Override public <T> Future<T> get(Class<T> type) { return asyncTyped("GET", null, type); }
    @Override public <T> Future<T> get(GenericType<T> type) { return asyncTyped("GET", null, type); }
    @Override public <T> Future<T> get(InvocationCallback<T> cb) { return asyncCallback("GET", null, cb); }

    // ---- PUT -------------------------------------------------------------------------------

    @Override public Future<Response> put(Entity<?> e) { return async("PUT", e); }
    @Override public <T> Future<T> put(Entity<?> e, Class<T> type) { return asyncTyped("PUT", e, type); }
    @Override public <T> Future<T> put(Entity<?> e, GenericType<T> type) { return asyncTyped("PUT", e, type); }
    @Override public <T> Future<T> put(Entity<?> e, InvocationCallback<T> cb) { return asyncCallback("PUT", e, cb); }

    // ---- POST ------------------------------------------------------------------------------

    @Override public Future<Response> post(Entity<?> e) { return async("POST", e); }
    @Override public <T> Future<T> post(Entity<?> e, Class<T> type) { return asyncTyped("POST", e, type); }
    @Override public <T> Future<T> post(Entity<?> e, GenericType<T> type) { return asyncTyped("POST", e, type); }
    @Override public <T> Future<T> post(Entity<?> e, InvocationCallback<T> cb) { return asyncCallback("POST", e, cb); }

    // ---- DELETE ----------------------------------------------------------------------------

    @Override public Future<Response> delete() { return async("DELETE", null); }
    @Override public <T> Future<T> delete(Class<T> type) { return asyncTyped("DELETE", null, type); }
    @Override public <T> Future<T> delete(GenericType<T> type) { return asyncTyped("DELETE", null, type); }
    @Override public <T> Future<T> delete(InvocationCallback<T> cb) { return asyncCallback("DELETE", null, cb); }

    // ---- HEAD ------------------------------------------------------------------------------

    @Override public Future<Response> head() { return async("HEAD", null); }
    @Override public Future<Response> head(InvocationCallback<Response> cb) { return asyncCallback("HEAD", null, cb); }

    // ---- OPTIONS ---------------------------------------------------------------------------

    @Override public Future<Response> options() { return async("OPTIONS", null); }
    @Override public <T> Future<T> options(Class<T> type) { return asyncTyped("OPTIONS", null, type); }
    @Override public <T> Future<T> options(GenericType<T> type) { return asyncTyped("OPTIONS", null, type); }
    @Override public <T> Future<T> options(InvocationCallback<T> cb) { return asyncCallback("OPTIONS", null, cb); }

    // ---- TRACE -----------------------------------------------------------------------------

    @Override public Future<Response> trace() { return async("TRACE", null); }
    @Override public <T> Future<T> trace(Class<T> type) { return asyncTyped("TRACE", null, type); }
    @Override public <T> Future<T> trace(GenericType<T> type) { return asyncTyped("TRACE", null, type); }
    @Override public <T> Future<T> trace(InvocationCallback<T> cb) { return asyncCallback("TRACE", null, cb); }

    // ---- method() --------------------------------------------------------------------------

    @Override public Future<Response> method(String name) { return async(name, null); }
    @Override public <T> Future<T> method(String name, Class<T> type) { return asyncTyped(name, null, type); }
    @Override public <T> Future<T> method(String name, GenericType<T> type) { return asyncTyped(name, null, type); }
    @Override public <T> Future<T> method(String name, InvocationCallback<T> cb) { return asyncCallback(name, null, cb); }
    @Override public Future<Response> method(String name, Entity<?> e) { return async(name, e); }
    @Override public <T> Future<T> method(String name, Entity<?> e, Class<T> type) { return asyncTyped(name, e, type); }
    @Override public <T> Future<T> method(String name, Entity<?> e, GenericType<T> type) { return asyncTyped(name, e, type); }
    @Override public <T> Future<T> method(String name, Entity<?> e, InvocationCallback<T> cb) { return asyncCallback(name, e, cb); }
}
