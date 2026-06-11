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
package io.vidocq.cassini.internal;

import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.TimeoutHandler;
import jakarta.ws.rs.core.Response;

import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cassini implementation of {@link AsyncResponse} (§8.2).
 *
 * <p>Each asynchronous request ({@code @Suspended AsyncResponse}) receives
 * an instance of this class. The resource method calls {@link #resume(Object)}
 * (from any thread) to send the response; the Invoker blocks the
 * current virtual thread on {@link #completionFuture()} until that call.
 *
 * <p>Blocking on a virtual thread is intentional (M2h): it releases the
 * carrier thread without starvation, in line with the JDK 25 virtual-threads model.
 */
public final class CassiniAsyncResponseImpl implements AsyncResponse {

    /** Exchange attribute key so that ParamExtractor can retrieve the instance. */
    public static final String ATTR_KEY = "cassini.async_response";

    private static final ScheduledExecutorService SCHEDULER;

    static {
        SCHEDULER = Executors.newScheduledThreadPool(1, r -> {
            Thread t = new Thread(r, "cassini-async-timeout");
            t.setDaemon(true);
            return t;
        });
    }

    private final CompletableFuture<Object> future = new CompletableFuture<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile ScheduledFuture<?> timeoutTask;
    private volatile TimeoutHandler timeoutHandler;

    /** Stage completed when {@link #resume} or {@link #cancel} is called. */
    public CompletableFuture<Object> completionFuture() { return future; }

    @Override
    public boolean resume(Object response) {
        if (future.isDone()) return false;
        cancelTimeout();
        return future.complete(response);
    }

    @Override
    public boolean resume(Throwable response) {
        if (future.isDone()) return false;
        cancelTimeout();
        return future.completeExceptionally(response);
    }

    @Override
    public boolean cancel() {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancelTimeout();
        future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE).build());
        return true;
    }

    @Override
    public boolean cancel(int retryAfter) {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancelTimeout();
        future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", retryAfter).build());
        return true;
    }

    @Override
    public boolean cancel(Date retryAfter) {
        if (!cancelled.compareAndSet(false, true)) return false;
        cancelTimeout();
        future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE)
                .header("Retry-After", retryAfter).build());
        return true;
    }

    @Override public boolean isSuspended() { return !future.isDone(); }
    @Override public boolean isCancelled()  { return cancelled.get(); }
    @Override public boolean isDone()        { return future.isDone(); }

    @Override
    public boolean setTimeout(long time, TimeUnit unit) {
        cancelTimeout();
        timeoutTask = SCHEDULER.schedule(() -> {
            if (!future.isDone()) {
                TimeoutHandler h = timeoutHandler;
                if (h != null) {
                    h.handleTimeout(this);
                } else {
                    future.complete(Response.status(Response.Status.SERVICE_UNAVAILABLE).build());
                }
            }
        }, time, unit);
        return true;
    }

    @Override
    public void setTimeoutHandler(TimeoutHandler handler) {
        this.timeoutHandler = handler;
    }

    // §8.2: registered lifecycle callbacks. CompletionCallback fires once the
    // request processing finishes (fired by the Invoker); ConnectionCallback
    // fires on premature client disconnect — registration is supported, but
    // detection requires a transport notification Chappe does not expose yet
    // (see ASYNC.md, deferred with the CompletionStage propagation chantier).
    private final java.util.List<jakarta.ws.rs.container.CompletionCallback> completionCallbacks =
            new CopyOnWriteArrayList<>();
    private final java.util.List<jakarta.ws.rs.container.ConnectionCallback> connectionCallbacks =
            new CopyOnWriteArrayList<>();
    private final AtomicBoolean completionFired = new AtomicBoolean(false);

    @Override
    public Collection<Class<?>> register(Class<?> callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        return register(instantiateCallback(callback));
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Class<?> callback, Class<?>... callbacks) {
        java.util.Objects.requireNonNull(callbacks, "callbacks");
        var out = new java.util.LinkedHashMap<Class<?>, Collection<Class<?>>>();
        out.put(callback, register(callback));
        for (Class<?> c : callbacks) out.put(c, register(c));
        return out;
    }

    @Override
    public Collection<Class<?>> register(Object callback) {
        java.util.Objects.requireNonNull(callback, "callback");
        var recognized = new java.util.ArrayList<Class<?>>();
        if (callback instanceof jakarta.ws.rs.container.CompletionCallback cc) {
            completionCallbacks.add(cc);
            recognized.add(jakarta.ws.rs.container.CompletionCallback.class);
        }
        if (callback instanceof jakarta.ws.rs.container.ConnectionCallback dc) {
            connectionCallbacks.add(dc);
            recognized.add(jakarta.ws.rs.container.ConnectionCallback.class);
        }
        return recognized;
    }

    @Override
    public Map<Class<?>, Collection<Class<?>>> register(Object callback, Object... callbacks) {
        java.util.Objects.requireNonNull(callbacks, "callbacks");
        var out = new java.util.LinkedHashMap<Class<?>, Collection<Class<?>>>();
        out.put(callback.getClass(), register(callback));
        for (Object c : callbacks) out.put(c.getClass(), register(c));
        return out;
    }

    private static Object instantiateCallback(Class<?> callback) {
        try {
            return callback.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Cannot instantiate callback " + callback.getName(), e);
        }
    }

    /**
     * §8.2: invoked by the Invoker exactly once, when the processing of the
     * async request is over. {@code unmappedError} is {@code null} on normal
     * completion (incl. mapped exceptions), or the throwable when processing
     * ended with an exception that no ExceptionMapper handled.
     */
    public void fireCompletion(Throwable unmappedError) {
        if (!completionFired.compareAndSet(false, true)) return;
        for (var cb : completionCallbacks) {
            try {
                cb.onComplete(unmappedError);
            } catch (RuntimeException ignored) {
                // a callback failure must not break response delivery
            }
        }
    }

    /** §8.2: premature client disconnect (transport notification required). */
    public void fireDisconnect() {
        for (var cb : connectionCallbacks) {
            try {
                cb.onDisconnect(this);
            } catch (RuntimeException ignored) { /* same as above */ }
        }
    }

    private void cancelTimeout() {
        ScheduledFuture<?> t = timeoutTask;
        if (t != null) {
            t.cancel(false);
            timeoutTask = null;
        }
    }
}
