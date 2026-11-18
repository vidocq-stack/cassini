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

import io.vidocq.cassini.internal.filter.RequestProperties;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.util.ArrayList;
import java.util.List;

/**
 * The end of a request, for the code that has to leave something with it: a filter sets the
 * {@value #PROPERTY} property of the request to a {@link Runnable}, and Cassini runs it once the dispatch
 * is over.
 */
final class RequestCompletion {

    /** The request property holding the action, a {@link Runnable}. */
    static final String PROPERTY = "io.vidocq.cassini.requestCompleted";

    private RequestCompletion() {
    }

    /** The exchange attribute holding what ends the resource instances of the request, a list of {@link Runnable}. */
    private static final String RELEASES = "io.vidocq.cassini.resourceReleases";

    private static final System.Logger LOG = System.getLogger(RequestCompletion.class.getName());

    /**
     * Keeps {@code release}, what ends a resource instance the request uses, for {@link #run} (CASSINI-062). Nothing
     * to keep when there is no {@code release}, or no request: an instance made outside one lives on, as before.
     */
    static void release(CassiniHttpExchange exchange, Runnable release) {
        if (release == null || exchange == null) return;
        @SuppressWarnings("unchecked")
        var releases = (List<Runnable>) exchange.getAttribute(RELEASES);
        if (releases == null) {
            releases = new ArrayList<>(1);
            exchange.setAttribute(RELEASES, releases);
        }
        releases.add(release);
    }

    /**
     * Ends the resource instances of the request, then runs the action the request holds, if any, and forgets both,
     * so nothing runs twice. The instances end first: a {@code @PreDestroy} may still use the request scope the
     * action ends. A failure is logged and goes no further: the response is already written.
     *
     * <p>An {@link Error} is never swallowed. When one is already in flight ({@code pending}), the error is logged and
     * attached to it as suppressed, so that the original reaches the caller; otherwise the first one is logged and
     * rethrown once everything ran, the others attached to it.</p>
     */
    static void run(CassiniHttpExchange exchange, Error pending) {
        Error raised = null;
        @SuppressWarnings("unchecked")
        var releases = (List<Runnable>) exchange.getAttribute(RELEASES);
        if (releases != null) {
            // Emptied, not removed: an exchange may refuse a null attribute
            var taken = List.copyOf(releases);
            releases.clear();
            for (Runnable release : taken) {
                raised = runGuarded(release, pending, raised, "A resource release failed");
            }
        }
        Object registered = RequestProperties.take(exchange, PROPERTY);
        if (registered != null && !(registered instanceof Runnable)) {
            // A scope that never ends would be a silent leak: say that the contract was not honoured.
            LOG.log(System.Logger.Level.WARNING, "The {0} request property holds a {1}, not a Runnable: ignored",
                    PROPERTY, registered.getClass().getName());
        }
        if (registered instanceof Runnable action) {
            raised = runGuarded(action, pending, raised, "An end-of-request action failed");
        }
        if (raised != null) throw raised;
    }

    /** Runs {@code action}; returns the error to rethrow at the end, {@code raised} or the one the action threw. */
    private static Error runGuarded(Runnable action, Error pending, Error raised, String failure) {
        try {
            action.run();
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, failure, e);
        } catch (Error e) { // NOSONAR java:S1181 - never swallowed: attached to the one in flight, or rethrown
            LOG.log(System.Logger.Level.ERROR, failure + " with an error", e);
            Error first = pending != null ? pending : raised;
            if (first == null) return e;
            first.addSuppressed(e);
        }
        return raised;
    }
}
