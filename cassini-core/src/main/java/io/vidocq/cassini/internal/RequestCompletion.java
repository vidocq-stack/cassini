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

    private static final System.Logger LOG = System.getLogger(RequestCompletion.class.getName());

    /**
     * Runs the action the request holds, if any, and forgets it, so it never runs twice. A failure of the action is
     * logged and goes no further: the response is already written.
     *
     * <p>An {@link Error} is never swallowed. When one is already in flight ({@code pending}), the action's error
     * is logged and attached to it as suppressed, so that the original reaches the caller; otherwise it is
     * logged and rethrown.</p>
     */
    static void run(CassiniHttpExchange exchange, Error pending) {
        Object registered = RequestProperties.take(exchange, PROPERTY);
        if (registered != null && !(registered instanceof Runnable)) {
            // A scope that never ends would be a silent leak: say that the contract was not honoured.
            LOG.log(System.Logger.Level.WARNING, "The {0} request property holds a {1}, not a Runnable: ignored",
                    PROPERTY, registered.getClass().getName());
        }
        if (registered instanceof Runnable action) {
            try {
                action.run();
            } catch (RuntimeException e) {
                LOG.log(System.Logger.Level.WARNING, "An end-of-request action failed", e);
            } catch (Error e) { // NOSONAR java:S1181 - never swallowed: attached to the one in flight, or rethrown
                LOG.log(System.Logger.Level.ERROR, "An end-of-request action failed with an error", e);
                if (pending == null) throw e;
                pending.addSuppressed(e);
            }
        }
    }
}
