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

import io.vidocq.cassini.internal.context.CassiniUriInfo;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The base URI of a request depends on its scheme, its authority and the context path only. It
 * is the same for every request of a listener, and it was parsed again by every {@code UriInfo}
 * of every request: several times per request, which put {@code java.net.URI} parsing at a tenth
 * of the CPU samples of the Todo benchmark.
 */
class CassiniUriInfoTest {

    @Test
    void theBaseUriOfAnExchangeIsBuiltOnce() {
        InMemoryExchange exchange = InMemoryExchange.get("/things/42");
        CassiniUriInfo first = new CassiniUriInfo(exchange, "", Map.of());
        CassiniUriInfo second = new CassiniUriInfo(exchange, "", Map.of());
        assertEquals(URI.create("http://127.0.0.1/"), first.getBaseUri());
        assertSame(first.getBaseUri(), first.getBaseUri());
        assertSame(first.getBaseUri(), second.getBaseUri());
    }

    @Test
    void exchangesWithTheSameAuthorityShareTheBaseUri() {
        CassiniUriInfo a = new CassiniUriInfo(InMemoryExchange.get("/things/1"), "/api", Map.of());
        CassiniUriInfo b = new CassiniUriInfo(InMemoryExchange.get("/other"), "/api", Map.of());
        assertEquals(URI.create("http://127.0.0.1/api/"), a.getBaseUri());
        assertSame(a.getBaseUri(), b.getBaseUri());
    }

    @Test
    void anotherAuthorityOrContextPathHasItsOwnBaseUri() {
        CassiniUriInfo host = new CassiniUriInfo(InMemoryExchange.get("127.0.0.1", "/things"), "", Map.of());
        CassiniUriInfo other = new CassiniUriInfo(InMemoryExchange.get("example.org:8443", "/things"), "", Map.of());
        CassiniUriInfo context = new CassiniUriInfo(InMemoryExchange.get("127.0.0.1", "/api/things"), "/api", Map.of());
        assertEquals(URI.create("http://example.org:8443/"), other.getBaseUri());
        assertEquals(URI.create("http://127.0.0.1/api/"), context.getBaseUri());
        assertNotSame(host.getBaseUri(), other.getBaseUri());
        assertNotSame(host.getBaseUri(), context.getBaseUri());
    }

    @Test
    void theBaseUriCacheIsBounded() {
        for (int i = 0; i < 2_000; i++) {
            new CassiniUriInfo(InMemoryExchange.get("host-" + i + ".example", "/x"), "", Map.of()).getBaseUri();
        }
        assertEquals(true, CassiniUriInfo.cachedBaseUris() <= 1_024,
                "cached base URIs: " + CassiniUriInfo.cachedBaseUris());
    }
}
