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

import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.CompletionStageRxInvoker;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * The mandatory {@link CompletionStageRxInvoker} of {@code Invocation.Builder.rx()}: each call runs
 * the invocation on the client's executor (virtual threads unless the builder was given another
 * one), through the same path as the {@code AsyncInvoker}.
 *
 * <p>A typed call fails its stage with the {@code WebApplicationException} / {@code ProcessingException}
 * of the invocation, as the synchronous call would throw it.</p>
 */
final class CassiniRxInvoker implements CompletionStageRxInvoker {

    /** {@link HttpMethod} declares no TRACE constant. */
    private static final String TRACE = "TRACE";

    private final CassiniInvocationBuilder builder;

    CassiniRxInvoker(CassiniInvocationBuilder builder) {
        this.builder = builder;
    }

    private <T> CompletionStage<T> supply(String method, Entity<?> entity, Function<Response, T> reader) {
        return builder.supplyAsync(builder.executor(), method, entity, reader);
    }

    private CompletionStage<Response> raw(String method, Entity<?> entity) {
        return supply(method, entity, Function.identity());
    }

    private <T> CompletionStage<T> typed(String method, Entity<?> entity, Class<T> type) {
        return supply(method, entity, r -> CassiniInvocationBuilder.readTyped(r, type));
    }

    private <T> CompletionStage<T> typed(String method, Entity<?> entity, GenericType<T> type) {
        return supply(method, entity, r -> CassiniInvocationBuilder.readTyped(r, type));
    }

    @Override public CompletionStage<Response> get() { return raw(HttpMethod.GET, null); }
    @Override public <T> CompletionStage<T> get(Class<T> type) { return typed(HttpMethod.GET, null, type); }
    @Override public <T> CompletionStage<T> get(GenericType<T> type) { return typed(HttpMethod.GET, null, type); }

    @Override public CompletionStage<Response> put(Entity<?> e) { return raw(HttpMethod.PUT, e); }
    @Override public <T> CompletionStage<T> put(Entity<?> e, Class<T> type) { return typed(HttpMethod.PUT, e, type); }
    @Override public <T> CompletionStage<T> put(Entity<?> e, GenericType<T> type) { return typed(HttpMethod.PUT, e, type); }

    @Override public CompletionStage<Response> post(Entity<?> e) { return raw(HttpMethod.POST, e); }
    @Override public <T> CompletionStage<T> post(Entity<?> e, Class<T> type) { return typed(HttpMethod.POST, e, type); }
    @Override public <T> CompletionStage<T> post(Entity<?> e, GenericType<T> type) { return typed(HttpMethod.POST, e, type); }

    @Override public CompletionStage<Response> delete() { return raw(HttpMethod.DELETE, null); }
    @Override public <T> CompletionStage<T> delete(Class<T> type) { return typed(HttpMethod.DELETE, null, type); }
    @Override public <T> CompletionStage<T> delete(GenericType<T> type) { return typed(HttpMethod.DELETE, null, type); }

    @Override public CompletionStage<Response> head() { return raw(HttpMethod.HEAD, null); }

    @Override public CompletionStage<Response> options() { return raw(HttpMethod.OPTIONS, null); }
    @Override public <T> CompletionStage<T> options(Class<T> type) { return typed(HttpMethod.OPTIONS, null, type); }
    @Override public <T> CompletionStage<T> options(GenericType<T> type) { return typed(HttpMethod.OPTIONS, null, type); }

    @Override public CompletionStage<Response> trace() { return raw(TRACE, null); }
    @Override public <T> CompletionStage<T> trace(Class<T> type) { return typed(TRACE, null, type); }
    @Override public <T> CompletionStage<T> trace(GenericType<T> type) { return typed(TRACE, null, type); }

    @Override public CompletionStage<Response> method(String name) { return raw(name, null); }
    @Override public <T> CompletionStage<T> method(String name, Class<T> type) { return typed(name, null, type); }
    @Override public <T> CompletionStage<T> method(String name, GenericType<T> type) { return typed(name, null, type); }
    @Override public CompletionStage<Response> method(String name, Entity<?> e) { return raw(name, e); }
    @Override public <T> CompletionStage<T> method(String name, Entity<?> e, Class<T> type) { return typed(name, e, type); }
    @Override public <T> CompletionStage<T> method(String name, Entity<?> e, GenericType<T> type) { return typed(name, e, type); }
}
