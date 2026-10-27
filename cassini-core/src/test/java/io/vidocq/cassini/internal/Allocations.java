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

import java.lang.reflect.Method;

/**
 * Bytes allocated by the current thread, for the tests that pin the request path to "nothing
 * is recomputed or rebuilt per call". HotSpot accounts them exactly (TLAB-aware), so a bound on
 * the bytes per call is deterministic where a timing would not be.
 *
 * <p>{@code java.lang.management} and {@code com.sun.management} are reached through reflection:
 * the tests run inside the patched {@code io.vidocq.cassini.core} module, which reads neither
 * {@code java.management} nor {@code jdk.management}, and reflection only needs the packages to
 * be exported.</p>
 */
final class Allocations {

    private static final Object THREADS;
    private static final Method CURRENT_THREAD_ALLOCATED_BYTES;

    static {
        try {
            THREADS = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Class<?> sun = Class.forName("com.sun.management.ThreadMXBean");
            CURRENT_THREAD_ALLOCATED_BYTES = sun.getMethod("getCurrentThreadAllocatedBytes");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("com.sun.management.ThreadMXBean is not available", e);
        }
    }

    private Allocations() {}

    static long currentThreadBytes() {
        try {
            return (Long) CURRENT_THREAD_ALLOCATED_BYTES.invoke(THREADS);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Mean bytes allocated per call of {@code call}, after {@code warmUp} unmeasured calls, over
     * {@code measured} measured ones.
     */
    static long bytesPerCall(Runnable call, int warmUp, int measured) {
        for (int i = 0; i < warmUp; i++) call.run();
        long before = currentThreadBytes();
        for (int i = 0; i < measured; i++) call.run();
        return (currentThreadBytes() - before) / measured;
    }
}
