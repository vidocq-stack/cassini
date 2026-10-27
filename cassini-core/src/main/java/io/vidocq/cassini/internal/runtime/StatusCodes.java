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
package io.vidocq.cassini.internal.runtime;

import jakarta.ws.rs.core.Response.Status;

/**
 * {@link Status#fromStatusCode(int)} through a table built once: the API method walks a fresh
 * copy of {@code Status.values()} on every call, and the response path asks on every request.
 */
public final class StatusCodes {

    /** Indexed by status code; covers 0-999, Status codes are three digits. */
    private static final Status[] BY_CODE = new Status[1000];

    static {
        for (Status s : Status.values()) {
            int code = s.getStatusCode();
            if (code >= 0 && code < BY_CODE.length && BY_CODE[code] == null) BY_CODE[code] = s;
        }
    }

    private StatusCodes() {}

    /**
     * The {@link Status} of {@code code}, as {@link Status#fromStatusCode(int)} gives it.
     *
     * @param code an HTTP status code
     * @return the status of that code, or {@code null} when {@link Status} has none
     */
    public static Status of(int code) {
        return code >= 0 && code < BY_CODE.length ? BY_CODE[code] : null;
    }
}
