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
package io.vidocq.cassini.examples.chappe;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Suspended-load bench (manual — results recorded in {@code cassini/BENCH.md}).
 *
 * <p>Measures the cost of the blocking-on-virtual-thread model for
 * {@code @Suspended AsyncResponse}: N concurrent requests suspend
 * server-side (each holding a Chappe connection VT + a cassini-req VT),
 * heap usage is sampled, then all responses are released at once and the
 * drain time measured. Run with:
 *
 * <pre>{@code
 * mvn test -pl cassini-examples/cassini-examples-chappe \
 *     -Dtest=SuspendedLoadBench -Dcassini.bench=true -Dcassini.bench.n=10000
 * }</pre>
 */
class SuspendedLoadBench {

    @Test
    void suspendedLoad() throws Exception {
        assumeTrue(Boolean.getBoolean("cassini.bench"), "manual bench — enable with -Dcassini.bench=true");
        int n = Integer.getInteger("cassini.bench.n", 5000);

        try (var server = new ExampleServer()) {
            var http = HttpClient.newHttpClient();

            gc();
            long heapBefore = usedHeap();

            // 1. Park N suspended requests, one raw socket each (kept open).
            var sockets = new ArrayList<Socket>(n);
            long t0 = System.nanoTime();
            for (int i = 0; i < n; i++) {
                var s = new Socket("127.0.0.1", server.port());
                s.setSoTimeout(120_000);
                sockets.add(s);
                s.getOutputStream().write(
                        ("GET /async/park HTTP/1.1\r\nHost: x\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                s.getOutputStream().flush();
            }

            // 2. Wait until all are parked server-side.
            int parked = 0;
            for (int tries = 0; tries < 600 && parked < n; tries++) {
                Thread.sleep(100);
                parked = Integer.parseInt(http.send(
                        HttpRequest.newBuilder(URI.create(server.baseUrl() + "/async/parked")).GET().build(),
                        HttpResponse.BodyHandlers.ofString()).body());
            }
            long parkMs = (System.nanoTime() - t0) / 1_000_000;
            assertEquals(n, parked, "all requests should be parked");

            gc();
            long heapParked = usedHeap();

            // 3. Release everything, then drain all responses.
            long t1 = System.nanoTime();
            int released = Integer.parseInt(http.send(
                    HttpRequest.newBuilder(URI.create(server.baseUrl() + "/async/release")).GET().build(),
                    HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(n, released);

            var ok = new AtomicInteger();
            var drained = new CountDownLatch(sockets.size());
            for (var s : sockets) {
                Thread.startVirtualThread(() -> {
                    try (s; var r = new BufferedReader(
                            new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII))) {
                        // Status line only — the connection is keep-alive, reading
                        // to EOF would block until the server-side idle timeout.
                        String line = r.readLine();
                        if (line != null && line.contains("200")) ok.incrementAndGet();
                    } catch (Exception ignored) {
                    } finally {
                        drained.countDown();
                    }
                });
            }
            drained.await(60, TimeUnit.SECONDS);
            long drainMs = (System.nanoTime() - t1) / 1_000_000;

            System.out.printf("""
                    === SuspendedLoadBench ===
                    N suspended            : %d
                    park time              : %d ms
                    heap before            : %.1f MB
                    heap with N suspended  : %.1f MB
                    heap delta             : %.1f MB (%.1f KB/request, incl. client sockets)
                    release+drain time     : %d ms
                    200 responses          : %d/%d
                    """,
                    n, parkMs,
                    heapBefore / 1048576.0, heapParked / 1048576.0,
                    (heapParked - heapBefore) / 1048576.0,
                    (heapParked - heapBefore) / 1024.0 / n,
                    drainMs, ok.get(), n);
            assertEquals(n, ok.get(), "every released response should be a 200");
        }
    }

    private static void gc() throws InterruptedException {
        for (int i = 0; i < 3; i++) {
            System.gc();
            Thread.sleep(100);
        }
    }

    private static long usedHeap() {
        var rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }
}
