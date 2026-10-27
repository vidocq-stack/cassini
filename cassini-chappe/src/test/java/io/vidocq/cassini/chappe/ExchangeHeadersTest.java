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
package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cassini reads the request headers several times per request (Content-Type, Accept, the
 * contexts, the entity reader). The exchange rebuilt a map of all of them, with a list per name,
 * on every read: about six times per request on the REST benchmark. A lookup by name now asks
 * Chappe's headers directly, and the map is built at most once.
 */
class ExchangeHeadersTest {

    private static final Headers HEADERS = Headers.builder()
            .add("Host", "localhost:8080")
            .add("Accept", "application/json")
            .add("Content-Type", "application/json")
            .add("X-Many", "a")
            .add("X-Many", "b")
            .build();

    @Test
    void aHeaderIsFoundByNameWhateverItsCase() {
        var exchange = new ChappeHttpExchange(request());
        assertEquals("application/json", exchange.firstHeader("accept"));
        assertEquals(List.of("a", "b"), exchange.headers("x-many"));
        assertNull(exchange.firstHeader("X-Absent"));
        assertEquals(List.of(), exchange.headers("X-Absent"));
    }

    @Test
    void theMapOfAllHeadersIsBuiltOnce() {
        var exchange = new ChappeHttpExchange(request());
        Map<String, List<String>> all = exchange.requestHeaders();
        assertEquals(List.of("a", "b"), all.get("X-Many"));
        assertEquals(List.of("localhost:8080"), all.get("Host"));
        assertSame(all, exchange.requestHeaders());
    }

    @Test
    void lookingUpAHeaderDoesNotCopyThemAll() {
        var exchange = new ChappeHttpExchange(request());
        // A fixed cost at most (the Optional behind Headers.firstOrNull), not a copy of every header.
        long perCall = bytesPerCall(() -> exchange.firstHeader("Accept"));
        assertTrue(perCall < 64, "looking up Accept allocates " + perCall + " bytes per call");
    }

    private static Request request() {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create("/things"); }
            @Override public String path() { return "/things"; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return HEADERS; }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
        };
    }

    /** Mean bytes the current thread allocates per call, after a warm-up. HotSpot accounts them
     *  exactly; {@code com.sun.management} is reached through reflection because the module
     *  under test reads neither {@code java.management} nor {@code jdk.management}. */
    private static long bytesPerCall(Runnable call) {
        try {
            Object threads = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Method allocated = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
            for (int i = 0; i < 2_000; i++) call.run();
            long before = (Long) allocated.invoke(threads);
            for (int i = 0; i < 10_000; i++) call.run();
            return ((Long) allocated.invoke(threads) - before) / 10_000;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("com.sun.management.ThreadMXBean is not available", e);
        }
    }
}
