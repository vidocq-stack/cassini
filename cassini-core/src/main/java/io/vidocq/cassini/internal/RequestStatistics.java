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

import io.vidocq.cassini.spi.http.CassiniStatistics;

import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;

/**
 * The counters of one stack, updated by {@link DefaultCassiniHttpAdapter} on every request (cassini#42).
 *
 * <p>{@link LongAdder}s, not {@code AtomicLong}s: every request runs on its own virtual thread, so the counters are
 * contended by design, and an adder spreads the writes over cells instead of retrying one compare-and-set. Updating
 * them allocates nothing. Reading sums the cells: no lock, no allocation, no I/O.
 */
final class RequestStatistics implements CassiniStatistics {

    private final LongAdder started = new LongAdder();
    private final LongAdder completed = new LongAdder();
    private final LongAdder nanos = new LongAdder();
    private final LongAccumulator max = new LongAccumulator(Math::max, 0L);
    /** Index 1 to 5 for 1xx to 5xx; index 0 is unused, so that a status class is its own index. */
    private final LongAdder[] byClass = {null, new LongAdder(), new LongAdder(), new LongAdder(), new LongAdder(),
            new LongAdder()};

    /** Marks a request as handed to the stack, and returns when, for {@link #end}. */
    long begin() {
        started.increment();
        return System.nanoTime();
    }

    /**
     * Marks a request begun at {@code startNanos} as answered with {@code status}. A status outside 100-599 is counted
     * as a request, in no class.
     */
    void end(long startNanos, int status) {
        long elapsed = System.nanoTime() - startNanos;
        nanos.add(elapsed);
        max.accumulate(elapsed);
        int statusClass = status / 100;
        if (statusClass >= 1 && statusClass <= 5) {
            byClass[statusClass].increment();
        }
        // Last, so that inFlight() never reads a request as answered before its figures are in.
        completed.increment();
    }

    @Override
    public long requests() {
        return completed.sum();
    }

    @Override
    public long inFlight() {
        // Read completed first: a request that ends between the two reads then counts as still in flight, never as
        // a negative.
        long done = completed.sum();
        return Math.max(0L, started.sum() - done);
    }

    @Override
    public long responses(int statusClass) {
        if (statusClass < 1 || statusClass > 5) {
            throw new IllegalArgumentException("status class must be between 1 and 5, was " + statusClass);
        }
        return byClass[statusClass].sum();
    }

    @Override
    public long totalNanos() {
        return nanos.sum();
    }

    @Override
    public long maxNanos() {
        return max.get();
    }
}
