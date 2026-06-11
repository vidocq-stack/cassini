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
package io.vidocq.cassini.examples.chappe.resource;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.CompletionCallback;
import jakarta.ws.rs.container.ConnectionCallback;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Asynchronous JAX-RS examples (§8.2): {@code @Suspended AsyncResponse}
 * resumed from another virtual thread, lifecycle callbacks
 * ({@link CompletionCallback}, {@link ConnectionCallback} — fired through the
 * Chappe client-disconnect probe), and a park/release pair used by the
 * suspended-load bench.
 */
@Path("async")
public class AsyncResource {

    // --- Test/observability hooks (same pattern as TodoResource.reset()) ---
    public record Events(CountDownLatch disconnected, CountDownLatch completed) {}

    private static volatile Events events = new Events(new CountDownLatch(1), new CountDownLatch(1));

    public static Events reset() {
        events = new Events(new CountDownLatch(1), new CountDownLatch(1));
        return events;
    }

    /** Resumed from another virtual thread — the basic async pattern. */
    @GET
    @Path("echo/{value}")
    @Produces(MediaType.TEXT_PLAIN)
    public void echo(@PathParam("value") String value, @Suspended AsyncResponse ar) {
        ar.register((CompletionCallback) t -> events.completed().countDown());
        Thread.startVirtualThread(() -> ar.resume("async:" + value));
    }

    /**
     * Suspends without ever resuming: the outcome is decided by the client —
     * either it disconnects (the {@link ConnectionCallback} fires and the
     * runtime releases the suspended response) or the timeout trips.
     */
    @GET
    @Path("suspend")
    @Produces(MediaType.TEXT_PLAIN)
    public void suspend(@Suspended AsyncResponse ar) {
        ar.setTimeout(10, TimeUnit.SECONDS);
        ar.register((ConnectionCallback) disconnected -> events.disconnected().countDown());
        ar.register((CompletionCallback) t -> events.completed().countDown());
    }

    // --- Suspended-load bench support ---

    private static final Queue<AsyncResponse> PARKED = new ConcurrentLinkedQueue<>();

    /** Parks the response until {@link #release()} — bench scenario. */
    @GET
    @Path("park")
    @Produces(MediaType.TEXT_PLAIN)
    public void park(@Suspended AsyncResponse ar) {
        ar.setTimeout(5, TimeUnit.MINUTES);
        PARKED.add(ar);
    }

    /** @return the number of currently parked responses. */
    @GET
    @Path("parked")
    @Produces(MediaType.TEXT_PLAIN)
    public int parked() {
        return PARKED.size();
    }

    /** Resumes every parked response; returns how many were released. */
    @GET
    @Path("release")
    @Produces(MediaType.TEXT_PLAIN)
    public int release() {
        int n = 0;
        AsyncResponse ar;
        while ((ar = PARKED.poll()) != null) {
            if (ar.resume("released")) n++;
        }
        return n;
    }
}
