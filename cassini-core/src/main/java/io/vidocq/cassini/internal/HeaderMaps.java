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

import jakarta.ws.rs.core.AbstractMultivaluedMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The maps of request headers Cassini hands to filters, resources and entity readers. Header names are
 * case-insensitive (RFC 9110 §5.1), and HTTP/2 sends them in lower case: a lookup of {@code Authorization} must find
 * an {@code authorization} header (CASSINI-045).
 */
public final class HeaderMaps {

    private HeaderMaps() {
    }

    /**
     * An empty mutable map of headers, whose names are compared ignoring case.
     *
     * @param <V> the type of the header values
     * @return a new map
     */
    public static <V> MultivaluedMap<String, V> caseInsensitive() {
        return new AbstractMultivaluedMap<>(new TreeMap<>(String.CASE_INSENSITIVE_ORDER)) { };
    }

    /**
     * A mutable copy of the headers of a request, whose names are compared ignoring case.
     *
     * @param headers the headers of the exchange, or {@code null} for none
     * @return a new map; changing it leaves {@code headers} untouched
     */
    public static MultivaluedMap<String, String> copyOf(Map<String, List<String>> headers) {
        MultivaluedMap<String, String> copy = caseInsensitive();
        if (headers != null) {
            for (var header : headers.entrySet()) copy.addAll(header.getKey(), header.getValue());
        }
        return copy;
    }
}
