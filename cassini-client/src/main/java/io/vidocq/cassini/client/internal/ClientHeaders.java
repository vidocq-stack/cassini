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
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.core.AbstractMultivaluedMap;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MultivaluedMap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Header helpers shared by the request and response sides of the client: the case-insensitive
 * header map, and the typed views (cookies) the contexts and the response derive from raw values.
 */
final class ClientHeaders {

    private ClientHeaders() { }

    /**
     * A header map whose names compare case-insensitively, as HTTP field names do
     * (RFC 9110 §5.1): {@code java.net.http} hands them over as the server spelled them, all lower
     * case over HTTP/2 (CASSINI-019), and a request header set as {@code x-a} is the one a filter
     * reads as {@code X-A}.
     */
    static <V> MultivaluedMap<String, V> map() {
        return new AbstractMultivaluedMap<>(new TreeMap<>(String.CASE_INSENSITIVE_ORDER)) { };
    }

    /**
     * The cookies of a request, from its {@code Cookie} header values: a {@link Cookie} object as
     * is, a string as the {@code name=value; name=value} pairs of RFC 6265 §5.4. A pair without a
     * name, and the RFC 2109 {@code $}-attributes, are ignored; the last cookie of a name wins.
     */
    static Map<String, Cookie> requestCookies(MultivaluedMap<String, Object> headers) {
        Map<String, Cookie> out = new LinkedHashMap<>();
        var values = headers.get("Cookie");
        if (values == null) return out;
        for (Object value : values) {
            if (value instanceof Cookie cookie) {
                out.put(cookie.getName(), cookie);
            } else if (value != null) {
                for (String pair : value.toString().split(";")) {
                    int eq = pair.indexOf('=');
                    String name = (eq < 0 ? pair : pair.substring(0, eq)).trim();
                    // $Version, $Path, $Domain: RFC 2109 attributes of the cookie before them, not cookies.
                    if (name.isEmpty() || name.charAt(0) == '$') continue;
                    String cookieValue = eq < 0 ? "" : pair.substring(eq + 1).trim();
                    out.put(name, new Cookie.Builder(name).value(cookieValue).build());
                }
            }
        }
        return out;
    }
}
