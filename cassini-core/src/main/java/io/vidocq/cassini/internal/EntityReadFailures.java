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

import io.vidocq.cassini.spi.http.CassiniHttpExchange;

/**
 * Request entities rejected with a 400 because they could not be read
 * (cassini#39). The client's fault, so never logged above {@code DEBUG}: one
 * line per request, and the stack trace only at {@code TRACE}.
 */
final class EntityReadFailures {

    /** Stable logger name, documented in the Usage page — not a class name. */
    static final String LOGGER_NAME = "io.vidocq.cassini.entity";

    /** Longest client-influenced text copied into a log line. */
    static final int MAX_TEXT = 200;

    private static final System.Logger LOG = System.getLogger(LOGGER_NAME);

    private EntityReadFailures() {}

    /** One {@code DEBUG} line for a rejected entity; its stack at {@code TRACE} only. */
    static void logRejected(CassiniHttpExchange request, Throwable failure) {
        if (!LOG.isLoggable(System.Logger.Level.DEBUG)) return;
        LOG.log(System.Logger.Level.DEBUG, "400 Bad Request for " + oneLine(request.method()) + " "
                + oneLine(request.routingPath()) + ": unreadable request entity ("
                + failure.getClass().getName() + ": " + oneLine(failure.getMessage()) + ")");
        if (LOG.isLoggable(System.Logger.Level.TRACE)) {
            LOG.log(System.Logger.Level.TRACE, "Stack trace of the unreadable request entity above", failure);
        }
    }

    /** Client-influenced text kept on one bounded line: control characters
     *  (CR and LF included) become spaces, and the text stops at {@link #MAX_TEXT}. */
    static String oneLine(String text) {
        if (text == null) return "";
        int end = Math.min(text.length(), MAX_TEXT);
        StringBuilder b = new StringBuilder(end + 3);
        for (int i = 0; i < end; i++) {
            char c = text.charAt(i);
            b.append(Character.isISOControl(c) ? ' ' : c);
        }
        if (text.length() > MAX_TEXT) b.append("...");
        return b.toString();
    }
}
