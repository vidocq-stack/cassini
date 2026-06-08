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
package io.vidocq.cassini.spi.http;

import java.time.Duration;

/**
 * Contract for suspending/resuming an in-flight request — supports
 * {@code @Suspended AsyncResponse} (JAX-RS §8) and {@code CompletionStage}s
 * returned by resource methods.
 *
 * <p><b>M2h status</b>: this contract is frozen at extraction time. The current
 * implementation (Cassini 0.1.x) handles all async work in a blocking way via
 * {@code awaitBlocking()} in the Invoker. M2h will refactor the Invoker to
 * propagate stages to this API without blocking.
 */
public interface CassiniAsyncContext {

    void suspend();

    void resume(Object entity);

    void resumeWithError(Throwable t);

    void setTimeout(Duration d, Runnable handler);

    void addCompletionCallback(Runnable cb);

    void addConnectionCallback(Runnable cb);

    boolean isSuspended();

    boolean isCancelled();

    boolean isDone();
}
