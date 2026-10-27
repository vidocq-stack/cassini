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

import io.vidocq.cassini.internal.filter.CassiniRequestContext;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the dispatch of a plain request copies or builds on its way: the response entity and
 * headers are handed to the transport as they are, and the request URI is not built when nothing
 * needs it.
 */
class RequestPathCopiesTest {

    /** Long enough for the entity buffer to grow past it: a buffer the size of the body is a copy. */
    private static final String BODY = "x".repeat(3_000);

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/text")
    public static class Text {
        @GET
        public String get() {
            return BODY;
        }
    }

    @Path("/created")
    public static class Created {
        @GET
        public jakarta.ws.rs.core.Response get() {
            return jakarta.ws.rs.core.Response.created(java.net.URI.create("items/1")).build();
        }
    }

    /** Written in three parts, so that the entity buffer grows past the body. */
    @Path("/parts")
    public static class Parts {
        @GET
        @jakarta.ws.rs.Produces("text/plain")
        public jakarta.ws.rs.core.StreamingOutput get() {
            return out -> {
                for (int i = 0; i < 3; i++) out.write(BODY.substring(0, 1_000).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            };
        }
    }

    private static CassiniHttpAdapter adapter() {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(Text.class, Parts.class, Created.class);
            }
        }).build().adapter();
    }

    private static InMemoryExchange dispatch(CassiniHttpAdapter adapter) {
        return dispatch(adapter, "/text");
    }

    private static InMemoryExchange dispatch(CassiniHttpAdapter adapter, String path) {
        InMemoryExchange exchange = InMemoryExchange.get(path);
        adapter.dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    @Test
    void theEntityBufferIsHandedOverWithoutACopy() {
        InMemoryExchange exchange = dispatch(adapter(), "/parts");
        assertEquals(200, exchange.status());
        assertEquals(BODY, exchange.responseText());
        assertEquals(1, exchange.wholeBodyWrites);
        assertNotNull(exchange.handedOverArray, "the body was not handed over with its length");
        assertTrue(exchange.handedOverArray.length > BODY.length(),
                "the body was copied to an array of its exact size before it was handed over");
    }

    @Test
    void theResponseHeadersAreHandedOverWithoutACopy() {
        InMemoryExchange exchange = dispatch(adapter());
        assertNotNull(exchange.handedOverHeaders, "the response headers were copied one by one");
        assertTrue(exchange.responseContentType().startsWith("text/plain"));
    }

    @Test
    void aPlainRequestDoesNotBuildTheRequestUri() {
        InMemoryExchange exchange = dispatch(adapter());
        assertEquals(0, exchange.requestUriCalls,
                "the request URI was built although no filter, parameter or UriInfo asked for it");
    }

    @Test
    void theBaseUriComesFromAnAbsoluteTarget() {
        InMemoryExchange exchange = InMemoryExchange.get("example.org:8080", "/created");
        adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals("http://example.org:8080/items/1", exchange.responseHeader("Location"));
    }

    @Test
    void theBaseUriComesFromTheHostHeaderForAnOriginFormTarget() {
        InMemoryExchange exchange = InMemoryExchange.get("/created").rawTarget("/created")
                .header("Host", "api.example.org");
        adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals("http://api.example.org/items/1", exchange.responseHeader("Location"));
    }

    @Test
    void anAbsoluteTargetWithoutAuthorityFallsBackToTheHostHeader() {
        InMemoryExchange exchange = InMemoryExchange.get("/created").rawTarget("HTTPS:///created")
                .header("Host", "h.example.org");
        adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals("HTTPS://h.example.org/items/1", exchange.responseHeader("Location"));
    }

    @Test
    void aDispatchAllocatesNoCopyOfTheEntity() {
        CassiniHttpAdapter adapter = adapter();
        long perCall = Allocations.bytesPerCall(() -> dispatch(adapter), 2_000, 5_000);
        // About 12 KB for a 3,000-byte body, the String and its bytes included; one more copy of
        // the entity buffer before it is handed over makes it about 15 KB.
        assertTrue(perCall < 13_500, "a dispatch allocates " + perCall + " bytes");
    }

    @Test
    void aRequestContextAllocatesItsPropertiesOnlyWhenOneIsSet() {
        InMemoryExchange exchange = InMemoryExchange.get("/text");
        var uriInfo = new io.vidocq.cassini.internal.context.CassiniUriInfo(exchange, "", java.util.Map.of());
        long perCall = Allocations.bytesPerCall(() -> new CassiniRequestContext(exchange, uriInfo), 2_000, 10_000);
        // 144 bytes; 192 when the property map is built up front.
        assertTrue(perCall < 168, "a request context allocates " + perCall + " bytes");
        CassiniRequestContext ctx = new CassiniRequestContext(exchange, uriInfo);
        assertTrue(ctx.getPropertyNames().isEmpty());
        assertNull(ctx.getProperty("a"));
        ctx.removeProperty("a");
        ctx.setProperty("a", 1);
        assertEquals(1, ctx.getProperty("a"));
        assertEquals(Set.of("a"), Set.copyOf(ctx.getPropertyNames()));
        ctx.removeProperty("a");
        assertTrue(ctx.getPropertyNames().isEmpty());
    }
}
