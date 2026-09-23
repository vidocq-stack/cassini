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

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * cassini#42: what the request counters cost. Two stacks, one with {@link RequestStatistics} and one without, serve
 * the same in-memory {@code GET} — no socket, so that the counters are not lost in network noise. Rounds alternate
 * between the two stacks, so that warm-up and machine drift hit both alike.
 *
 * <p>Opt-in: {@code ./mvnw -ntp -pl cassini-core test -Dtest=RequestStatisticsBench -Dcassini.bench=true}. The
 * figures go to {@code BENCH.md}.
 */
@EnabledIfSystemProperty(named = "cassini.bench", matches = "true")
class RequestStatisticsBench {

    private static final int THREADS = Integer.getInteger("cassini.bench.threads", 64);
    private static final int PER_THREAD = Integer.getInteger("cassini.bench.perThread", 5_000);
    private static final int ROUNDS = Integer.getInteger("cassini.bench.rounds", 12);
    private static final int WARMUP_ROUNDS = 4;
    private static final int ALLOC_REQUESTS = 50_000;

    @Path("/ping")
    public static class Ping {
        @GET
        public String ping() { return "pong"; }
    }

    private static CassiniStack stack(boolean statistics) {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(Ping.class);
            }
        }).statistics(statistics).build();
    }

    /** Serves THREADS × PER_THREAD requests, one virtual thread per client; returns ns per request. */
    private static double round(CassiniStack stack) throws Exception {
        long start = System.nanoTime();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> clients = new ArrayList<>(THREADS);
            for (int t = 0; t < THREADS; t++) {
                clients.add(executor.submit(() -> {
                    for (int i = 0; i < PER_THREAD; i++) {
                        InMemoryExchange exchange = InMemoryExchange.get("/ping");
                        stack.adapter().dispatch(exchange).toCompletableFuture().join();
                        if (exchange.status() != 200) throw new AssertionError("status " + exchange.status());
                    }
                    return null;
                }));
            }
            for (Future<?> client : clients) client.get();
        }
        return (System.nanoTime() - start) / (double) (THREADS * PER_THREAD);
    }

    /**
     * Bytes the current thread allocates per request, after a warm-up. Read through reflection: cassini-core's
     * patched test module does not read {@code jdk.management}, and a bench is no reason to make it.
     */
    private static double allocatedPerRequest(CassiniStack stack) throws ReflectiveOperationException {
        Object threads = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean").invoke(null);
        Method allocated = Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("getThreadAllocatedBytes", long.class);
        long self = Thread.currentThread().threadId();
        for (int i = 0; i < ALLOC_REQUESTS; i++) {
            stack.adapter().dispatch(InMemoryExchange.get("/ping")).toCompletableFuture().join();
        }
        long before = (long) allocated.invoke(threads, self);
        for (int i = 0; i < ALLOC_REQUESTS; i++) {
            stack.adapter().dispatch(InMemoryExchange.get("/ping")).toCompletableFuture().join();
        }
        return ((long) allocated.invoke(threads, self) - before) / (double) ALLOC_REQUESTS;
    }

    private static double median(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    @Test
    void countersCostAgainstAnUninstrumentedStack() throws Exception {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
        CassiniStack on = stack(true);
        CassiniStack off = stack(false);

        for (int r = 0; r < WARMUP_ROUNDS; r++) {
            round(on);
            round(off);
        }
        double[] withCounters = new double[ROUNDS];
        double[] without = new double[ROUNDS];
        for (int r = 0; r < ROUNDS; r++) {
            // Alternate which stack goes first, so that neither always runs on a machine the other just warmed.
            if (r % 2 == 0) {
                withCounters[r] = round(on);
                without[r] = round(off);
            } else {
                without[r] = round(off);
                withCounters[r] = round(on);
            }
        }
        double allocOn = allocatedPerRequest(on);
        double allocOff = allocatedPerRequest(off);

        double medOn = median(withCounters);
        double medOff = median(without);
        System.out.printf("%nRequestStatisticsBench: %d virtual threads x %d requests, %d rounds (+%d warm-up)%n",
                THREADS, PER_THREAD, ROUNDS, WARMUP_ROUNDS);
        System.out.printf("  per round, ns/request, with counters: %s%n", Arrays.toString(round1(withCounters)));
        System.out.printf("  per round, ns/request, without      : %s%n", Arrays.toString(round1(without)));
        System.out.printf("  median ns/request: with %.1f, without %.1f, delta %+.1f ns (%+.2f %%)%n",
                medOn, medOff, medOn - medOff, 100 * (medOn - medOff) / medOff);
        System.out.printf("  allocated bytes/request (one thread, %d requests): with %.1f, without %.1f, delta %+.1f%n",
                ALLOC_REQUESTS, allocOn, allocOff, allocOn - allocOff);
        System.out.printf("  requests counted by the instrumented stack: %d%n",
                on.statistics().orElseThrow().requests());
    }

    private static double[] round1(double[] values) {
        return Arrays.stream(values).map(v -> Math.round(v * 10) / 10.0).toArray();
    }
}
