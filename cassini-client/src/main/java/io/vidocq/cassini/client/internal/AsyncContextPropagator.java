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

import java.lang.reflect.Method;

/**
 * Propagates the {@code io.opentelemetry.context.Context} between the caller thread and the
 * async thread of a {@link CassiniAsyncInvoker} invocation — without imposing an OTel
 * dependency on cassini-client.
 *
 * <p>If the class {@code io.opentelemetry.context.Context} is on the classpath,
 * {@link #capture()} returns the current Context and {@link #activate(Object)} re-attaches it
 * to the async thread. Otherwise (cassini-client used without OTel), the methods are no-ops
 * and async invocation works normally.</p>
 *
 * <p>Why: without this propagation, the CLIENT span created by
 * {@code HumboldtClientRequestFilter} in the async thread would not have the current SERVER span
 * as parent — the span chain would be broken. Pattern aligned with
 * {@code io.opentelemetry.context.Context.taskWrapping(Executor)} but using reflection to
 * decouple cassini.</p>
 */
final class AsyncContextPropagator {

    private static final Method CONTEXT_CURRENT;
    private static final Method CONTEXT_MAKE_CURRENT;

    static {
        Method current = null, makeCurrent = null;
        try {
            Class<?> ctxClass = Class.forName("io.opentelemetry.context.Context");
            current = ctxClass.getMethod("current");
            makeCurrent = ctxClass.getMethod("makeCurrent");
        } catch (Throwable ignored) {
            // OTel context not available — propagation no-op
        }
        CONTEXT_CURRENT = current;
        CONTEXT_MAKE_CURRENT = makeCurrent;
    }

    private AsyncContextPropagator() {}

    /** Captures the current OTel Context if available; otherwise {@code null}. */
    static Object capture() {
        if (CONTEXT_CURRENT == null) return null;
        try { return CONTEXT_CURRENT.invoke(null); }
        catch (Throwable t) { return null; }
    }

    /**
     * Re-activates a captured Context on the current thread. Returns an AutoCloseable
     * to be closed in {@code try-with-resources} to restore the previous Context.
     * If {@code captured} is {@code null} or if OTel is not loaded, returns
     * a no-op closeable.
     */
    static AutoCloseable activate(Object captured) {
        if (captured == null || CONTEXT_MAKE_CURRENT == null) return NOOP;
        try {
            Object scope = CONTEXT_MAKE_CURRENT.invoke(captured);
            if (scope instanceof AutoCloseable ac) return ac;
            return NOOP;
        } catch (Throwable t) {
            return NOOP;
        }
    }

    private static final AutoCloseable NOOP = () -> {};
}
