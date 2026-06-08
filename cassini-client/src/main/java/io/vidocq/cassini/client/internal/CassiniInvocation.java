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
