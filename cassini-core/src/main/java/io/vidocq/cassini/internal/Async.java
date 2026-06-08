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

import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * Helpers for the sync ↔ async boundary in Cassini.
 *
 * <p><b>M2h — non-blocking async + virtual threads</b></p>
 * <p>The current Invoker executes resource methods synchronously and
 * returns a {@link io.vidocq.cassini.internal.transport.CassiniHttpResponse}
 * directly. When a resource method returns a {@link CompletionStage},
 * the Invoker resolves it by blocking via {@link #awaitBlocking(CompletionStage)}.
 *
 * <p><b>M2h removal</b>: the M2h refactor will propagate {@code CompletionStage}
 * up to the transport via {@link io.vidocq.cassini.spi.http.CassiniHttpAdapter#dispatch
 * CassiniHttpAdapter.dispatch} (which already returns {@code CompletionStage<Void>}).
 * This helper will then be removed — all its call sites must be addressed
 * during M2h.
 *
 * <p>To ease the M2h refactor, isolate all blocking calls in this class
 * (NEVER do {@code .toCompletableFuture().get()} inline elsewhere).
 */
public final class Async {

    private Async() {}

    /**
     * Blocks the current thread until a {@link CompletionStage} completes.
     *
     * <p><b>TODO(M2h)</b>: replace each call with non-blocking propagation
     * of the stage to the transport. Cf. {@code CassiniHttpAdapter.dispatch}.
     *
     * @param cs stage to await
     * @return the resolved value of the stage
     * @throws RuntimeException si le stage échoue (cause préservée)
     */
    public static <T> T awaitBlocking(CompletionStage<T> cs) {
        try {
            return cs.toCompletableFuture().get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while awaiting CompletionStage", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            if (cause instanceof Error err) throw err;
            throw new RuntimeException(cause);
        }
    }
}
