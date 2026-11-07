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
package io.vidocq.cassini.client;

import io.vidocq.cassini.client.support.RecordingServer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CASSINI-024 item 7, response side: the client {@link Response} and the {@link ClientResponseContext}
 * derive {@code Set-Cookie}, {@code Date}, {@code Last-Modified}, {@code ETag}, {@code Allow} and
 * {@code Link} from the headers, the same way, instead of returning nothing. The test server
 * stamps every reply with its own {@code Date}, so that one is only checked for presence; the
 * date parsing itself is exercised through {@code Last-Modified}.
 */
class ResponseHeadersTest {

    private static final String LAST_MODIFIED = "Wed, 16 Nov 1994 09:00:00 GMT";
    private static final Date LAST_MODIFIED_DATE = new Date(784976400000L);
    private static final String NEXT = "http://example.com/next";
    private static final String PREV = "http://example.com/prev";

    private static RecordingServer server;

    @BeforeAll
    static void start() throws Exception {
        server = new RecordingServer((exchange, request) -> {
            if (request.uri().endsWith("/bare")) {
                RecordingServer.send(exchange, 200, Map.of("Content-Type", "text/plain"), "x");
                return;
            }
            var headers = exchange.getResponseHeaders();
            headers.add("Set-Cookie", "a=1; Path=/; HttpOnly");
            headers.add("Set-Cookie", "b=2; Max-Age=60");
            headers.add("Last-Modified", LAST_MODIFIED);
            headers.add("ETag", "W/\"v1\"");
            headers.add("Allow", "GET, post");
            headers.add("Link", "<" + NEXT + ">; rel=\"next\"");
            headers.add("Link", "<" + PREV + ">; rel=\"prev\"");
            RecordingServer.send(exchange, 200, Map.of("Content-Type", "text/plain"), "x");
        });
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** What a response exposes through the typed accessors. */
    private record View(Map<String, NewCookie> cookies, Date date, Date lastModified, EntityTag tag,
                        Set<String> allowed, Set<Link> links, boolean hasNext, boolean hasMissing,
                        Link prev, Link missing, Link.Builder nextBuilder, Link.Builder missingBuilder) { }

    private static View view(Response r) {
        return new View(r.getCookies(), r.getDate(), r.getLastModified(), r.getEntityTag(),
                r.getAllowedMethods(), r.getLinks(), r.hasLink("next"), r.hasLink("missing"),
                r.getLink("prev"), r.getLink("missing"), r.getLinkBuilder("next"), r.getLinkBuilder("missing"));
    }

    private static View view(ClientResponseContext c) {
        return new View(c.getCookies(), c.getDate(), c.getLastModified(), c.getEntityTag(),
                c.getAllowedMethods(), c.getLinks(), c.hasLink("next"), c.hasLink("missing"),
                c.getLink("prev"), c.getLink("missing"), c.getLinkBuilder("next"), c.getLinkBuilder("missing"));
    }

    private static View viaResponse(String path) {
        try (Client client = ClientBuilder.newClient()) {
            try (Response response = client.target(server.baseUrl() + path).request().get()) {
                return view(response);
            }
        }
    }

    private static View viaContext(String path) {
        AtomicReference<View> seen = new AtomicReference<>();
        try (Client client = ClientBuilder.newClient()
                .register((ClientResponseFilter) (request, response) -> seen.set(view(response)))) {
            client.target(server.baseUrl() + path).request().get().close();
        }
        return seen.get();
    }

    private static void assertTyped(View v) {
        assertEquals(2, v.cookies().size());
        assertEquals("1", v.cookies().get("a").getValue());
        assertEquals("/", v.cookies().get("a").getPath());
        assertTrue(v.cookies().get("a").isHttpOnly());
        assertEquals("2", v.cookies().get("b").getValue());
        assertEquals(60, v.cookies().get("b").getMaxAge());
        assertNotNull(v.date());
        assertEquals(LAST_MODIFIED_DATE, v.lastModified());
        assertNotNull(v.tag());
        assertEquals("v1", v.tag().getValue());
        assertTrue(v.tag().isWeak());
        assertEquals(Set.of("GET", "POST"), v.allowed());
        assertEquals(2, v.links().size());
        assertTrue(v.hasNext());
        assertFalse(v.hasMissing());
        assertEquals(URI.create(PREV), v.prev().getUri());
        assertNull(v.missing());
        assertNotNull(v.nextBuilder());
        assertEquals(URI.create(NEXT), v.nextBuilder().build().getUri());
        assertNull(v.missingBuilder());
    }

    private static void assertAbsent(View v) {
        assertEquals(Map.of(), v.cookies());
        assertNull(v.lastModified());
        assertNull(v.tag());
        assertEquals(Set.of(), v.allowed());
        assertEquals(Set.of(), v.links());
        assertFalse(v.hasNext());
    }

    @Test
    void the_response_derives_its_typed_headers() {
        assertTyped(viaResponse("/"));
    }

    @Test
    void the_response_context_derives_its_typed_headers() {
        assertTyped(viaContext("/"));
    }

    @Test
    void the_response_without_those_headers_has_none() {
        assertAbsent(viaResponse("/bare"));
    }

    @Test
    void the_response_context_without_those_headers_has_none() {
        assertAbsent(viaContext("/bare"));
    }

    @Test
    void a_filter_changing_the_headers_changes_what_the_response_derives() {
        try (Client client = ClientBuilder.newClient().register((ClientResponseFilter) (request, response) ->
                response.getHeaders().putSingle("ETag", "\"changed\""))) {
            try (Response response = client.target(server.baseUrl() + "/").request().get()) {
                assertEquals("changed", response.getEntityTag().getValue());
            }
        }
    }
}
