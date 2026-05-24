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
 * Propage le {@code io.opentelemetry.context.Context} entre le thread caller et le
 * thread async d'une invocation {@link CassiniAsyncInvoker} — sans imposer une dep
 * OTel à cassini-client.
 *
 * <p>Si la classe {@code io.opentelemetry.context.Context} est dans le classpath,
 * {@link #capture()} retourne le Context courant et {@link #activate(Object)} le
 * ré-attache au thread async. Sinon (cassini-client utilisé sans OTel), les méthodes
 * sont no-op et l'invocation async fonctionne normalement.</p>
 *
 * <p>Pourquoi : sans cette propagation, le span CLIENT créé par
 * {@code HumboldtClientRequestFilter} dans le thread async n'aurait pas le span
 * SERVER courant comme parent — la chaîne de spans serait cassée. Pattern aligné
 * sur {@code io.opentelemetry.context.Context.taskWrapping(Executor)} mais en
 * réflexion pour découpler cassini.</p>
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
            // OTel context non disponible — propagation no-op
        }
        CONTEXT_CURRENT = current;
        CONTEXT_MAKE_CURRENT = makeCurrent;
    }

    private AsyncContextPropagator() {}

    /** Capture l'OTel Context courant si disponible ; sinon {@code null}. */
    static Object capture() {
        if (CONTEXT_CURRENT == null) return null;
        try { return CONTEXT_CURRENT.invoke(null); }
        catch (Throwable t) { return null; }
    }

    /**
     * Réactive un Context capturé sur le thread courant. Retourne un AutoCloseable
     * à fermer en {@code try-with-resources} pour restaurer le Context précédent.
     * Si {@code captured} est {@code null} ou si OTel n'est pas chargé, retourne
     * un no-op closeable.
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
