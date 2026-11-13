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

import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The request header maps Cassini hands to filters, resources and entity readers (CASSINI-045). */
class HeaderMapsTest {

    @Test
    void headerNamesAreComparedIgnoringCase() {
        MultivaluedMap<String, String> headers = HeaderMaps.copyOf(Map.of("authorization", List.of("Bearer t")));

        assertEquals(List.of("Bearer t"), headers.get("Authorization"));
        assertEquals("Bearer t", headers.getFirst("AUTHORIZATION"));
        assertTrue(headers.containsKey("Authorization"));
        headers.add("X-Trace", "1");
        headers.add("x-trace", "2");
        assertEquals(List.of("1", "2"), headers.get("X-TRACE"));
    }

    @Test
    void theCopyLeavesTheHeadersOfTheExchangeUntouched() {
        Map<String, List<String>> exchange = new HashMap<>(Map.of("A", new ArrayList<>(List.of("1"))));
        MultivaluedMap<String, String> headers = HeaderMaps.copyOf(exchange);

        headers.add("a", "2");
        headers.putSingle("B", "3");

        assertEquals(List.of("1", "2"), headers.get("A"));
        assertEquals(Map.of("A", List.of("1")), exchange);
    }

    @Test
    void noHeadersGiveAnEmptyMap() {
        assertTrue(HeaderMaps.copyOf(null).isEmpty());
        assertTrue(HeaderMaps.copyOf(Map.of()).isEmpty());
    }
}
