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
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link ClientRequestContext} and {@link ClientResponseContext} as the filters see them. */
class ClientContextsTest {

    private static final String X_LIST = "X-List";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String BODY = "body";

    /** Runs {@code builder.method} with the given filters on a fresh client against {@code server}. */
    private static Response run(RecordingServer server, String method, Entity<?> entity, Object... filters) {
        try (Client client = ClientBuilder.newClient()) {
            for (Object filter : filters) client.register(filter);
            Invocation.Builder builder = client.target(server.baseUrl()).path("/c").request()
                    .header(X_LIST, "a").header(X_LIST, "b");
            return entity == null ? builder.method(method) : builder.method(method, entity);
        }
    }

    @Test
    void request_context_exposes_the_request() throws Exception {
        AtomicReference<String> problems = new AtomicReference<>("not run");
        try (RecordingServer server = RecordingServer.echo()) {
            Response response = run(server, "POST", Entity.text(BODY), (ClientRequestFilter) ctx -> {
                StringBuilder p = new StringBuilder();
                check(p, "method", "POST", ctx.getMethod());
                check(p, "uri", URI.create(server.baseUrl() + "/c"), ctx.getUri());
                check(p, "header string", "a,b", ctx.getHeaderString(X_LIST));
                check(p, "absent header", null, ctx.getHeaderString("X-Absent"));
                check(p, "string headers", List.of("a", "b"), ctx.getStringHeaders().get(X_LIST));
                check(p, "contains", true, ctx.containsHeaderString(X_LIST, ",", "b"::equals));
                check(p, "not contains", false, ctx.containsHeaderString(X_LIST, ",", "c"::equals));
                check(p, "contains absent", false, ctx.containsHeaderString("X-Absent", ",", "c"::equals));
                check(p, "acceptable", List.of(MediaType.WILDCARD_TYPE), ctx.getAcceptableMediaTypes());
                check(p, "languages", List.of(), ctx.getAcceptableLanguages());
                check(p, "media type", MediaType.TEXT_PLAIN_TYPE, ctx.getMediaType());
                check(p, "has entity", true, ctx.hasEntity());
                check(p, "entity", BODY, ctx.getEntity());
                check(p, "entity class", String.class, ctx.getEntityClass());
                check(p, "entity type", String.class, ctx.getEntityType());
                check(p, "annotations", 0, ctx.getEntityAnnotations().length);
                check(p, "cookies", Map.of(), ctx.getCookies());
                check(p, "date", null, ctx.getDate());
                check(p, "language", null, ctx.getLanguage());
                check(p, "client", true, ctx.getClient() != null);
                check(p, "configuration", ctx.getClient().getConfiguration(), ctx.getConfiguration());
                problems.set(p.toString());
            });
            assertEquals(200, response.getStatus());
        }
        assertEquals("", problems.get());
    }

    @Test
    void request_context_parses_accept_headers() throws Exception {
        AtomicReference<List<?>> types = new AtomicReference<>();
        AtomicReference<List<?>> languages = new AtomicReference<>();
        try (RecordingServer server = RecordingServer.echo(); Client client = ClientBuilder.newClient()) {
            client.register((ClientRequestFilter) ctx -> {
                types.set(ctx.getAcceptableMediaTypes());
                languages.set(ctx.getAcceptableLanguages());
            });
            client.target(server.baseUrl()).request("text/plain", "application/json")
                    .acceptLanguage(Locale.FRANCE, Locale.GERMANY).get();
            assertEquals(List.of(MediaType.TEXT_PLAIN_TYPE, MediaType.APPLICATION_JSON_TYPE), types.get());
            assertEquals(List.of(Locale.FRANCE, Locale.GERMANY), languages.get());

            client.target(server.baseUrl()).request().headers(null).get();
            assertEquals(List.of(MediaType.WILDCARD_TYPE), types.get());
        }
    }

    @Test
    void request_filter_can_change_method_entity_and_properties() throws Exception {
        AtomicReference<Object> property = new AtomicReference<>();
        try (RecordingServer server = RecordingServer.echo()) {
            ClientRequestFilter rewrite = ctx -> {
                ctx.setMethod("PUT");
                ctx.setEntity("rewritten", new Annotation[0], MediaType.APPLICATION_XML_TYPE);
                ctx.setProperty("k", "v");
                ctx.setProperty("gone", "x");
                ctx.removeProperty("gone");
                property.set(Set.copyOf(ctx.getPropertyNames()) + "=" + ctx.getProperty("k"));
            };
            Response response = run(server, "POST", Entity.text(BODY), rewrite);
            assertEquals("PUT:rewritten", response.readEntity(String.class));
            assertEquals("application/xml", server.last().header(CONTENT_TYPE));
        }
        assertEquals("[k]=v", property.get());
    }

    @Test
    void request_filter_can_add_or_drop_the_entity() throws Exception {
        try (RecordingServer server = RecordingServer.echo()) {
            ClientRequestFilter add = ctx -> ctx.setEntity("added");
            assertEquals("POST:added", run(server, "POST", null, add).readEntity(String.class));

            ClientRequestFilter drop = ctx -> ctx.setEntity(null);
            assertEquals("POST:", run(server, "POST", Entity.text(BODY), drop).readEntity(String.class));

            ClientRequestFilter untyped = ctx -> ctx.setEntity("plain", null, null);
            assertEquals("POST:plain", run(server, "POST", Entity.text(BODY), untyped).readEntity(String.class));
        }
    }

    @Test
    void request_without_entity_has_none_in_the_context() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        try (RecordingServer server = RecordingServer.echo()) {
            run(server, "GET", null, (ClientRequestFilter) ctx ->
                    seen.set(ctx.hasEntity() + " " + ctx.getEntity() + " " + ctx.getEntityClass() + " " + ctx.getMediaType()));
        }
        assertEquals("false null null null", seen.get());
    }

    @Test
    void response_context_exposes_the_response() throws Exception {
        AtomicReference<String> problems = new AtomicReference<>("not run");
        try (RecordingServer server = RecordingServer.fixed(201,
                Map.of(CONTENT_TYPE, "text/plain", "Location", "http://h/new", X_LIST, "a"), BODY)) {
            run(server, "GET", null, (ClientResponseFilter) (req, ctx) -> {
                StringBuilder p = new StringBuilder();
                check(p, "status", 201, ctx.getStatus());
                check(p, "status info", Response.Status.CREATED, ctx.getStatusInfo());
                check(p, "header", "a", ctx.getHeaderString(X_LIST));
                check(p, "absent header", null, ctx.getHeaderString("X-Absent"));
                check(p, "contains", true, ctx.containsHeaderString(X_LIST, ",", "a"::equals));
                check(p, "not contains", false, ctx.containsHeaderString(X_LIST, ",", "z"::equals));
                check(p, "contains absent", false, ctx.containsHeaderString("X-Absent", ",", "z"::equals));
                check(p, "length", BODY.length(), ctx.getLength());
                check(p, "media type", MediaType.TEXT_PLAIN_TYPE, ctx.getMediaType());
                check(p, "location", URI.create("http://h/new"), ctx.getLocation());
                check(p, "has entity", true, ctx.hasEntity());
                check(p, "entity", BODY, read(ctx));
                check(p, "entity again", BODY, read(ctx));
                check(p, "allowed", Set.of(), ctx.getAllowedMethods());
                check(p, "cookies", Map.of(), ctx.getCookies());
                check(p, "links", Set.of(), ctx.getLinks());
                check(p, "has link", false, ctx.hasLink("next"));
                check(p, "link", null, ctx.getLink("next"));
                check(p, "link builder", null, ctx.getLinkBuilder("next"));
                check(p, "etag", null, ctx.getEntityTag());
                check(p, "date", null, ctx.getDate());
                check(p, "last modified", null, ctx.getLastModified());
                check(p, "language", null, ctx.getLanguage());
                problems.set(p.toString());
            });
        }
        assertEquals("", problems.get());
    }

    @Test
    void response_context_without_type_or_location() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        try (RecordingServer server = RecordingServer.fixed(204, Map.of(), "")) {
            run(server, "GET", null, (ClientResponseFilter) (req, ctx) ->
                    seen.set(ctx.getMediaType() + " " + ctx.getLocation() + " " + ctx.hasEntity()));
        }
        assertEquals("null null false", seen.get());
    }

    @Test
    void response_filter_can_change_status_headers_and_entity() throws Exception {
        try (RecordingServer server = RecordingServer.fixed(200, Map.of(CONTENT_TYPE, "text/plain"), BODY)) {
            ClientResponseFilter rewrite = (req, ctx) -> {
                ctx.setStatus(202);
                ctx.getHeaders().putSingle("X-Added", "yes");
                ctx.setEntityStream(new ByteArrayInputStream("replaced".getBytes(StandardCharsets.UTF_8)));
            };
            Response response = run(server, "GET", null, rewrite);
            assertEquals(202, response.getStatus());
            assertEquals("yes", response.getHeaderString("X-Added"));
            assertEquals("replaced", response.readEntity(String.class));

            ClientResponseFilter info = (req, ctx) -> {
                assertTrue(ctx.hasEntity());
                ctx.setStatusInfo(Response.Status.ACCEPTED);
            };
            assertEquals(202, run(server, "GET", null, info).getStatus());

            ClientResponseFilter keepInfo = (req, ctx) -> ctx.setStatusInfo(null);
            assertEquals(200, run(server, "GET", null, keepInfo).getStatus());
        }
    }

    @Test
    void replaced_entity_stream_is_read_by_the_response_context() throws Exception {
        AtomicReference<Integer> length = new AtomicReference<>();
        try (RecordingServer server = RecordingServer.fixed(200, Map.of(), BODY)) {
            run(server, "GET", null, (ClientResponseFilter) (req, ctx) -> {
                ByteArrayInputStream replacement = new ByteArrayInputStream(new byte[] {1, 2, 3});
                ctx.setEntityStream(replacement);
                assertSame(replacement, ctx.getEntityStream());
                length.set(ctx.getLength());
            });
        }
        assertEquals(3, length.get());
    }

    @Test
    void aborted_responses_carry_their_entity_and_headers() throws Exception {
        try (RecordingServer server = RecordingServer.echo()) {
            ClientRequestFilter bytes = ctx -> ctx.abortWith(Response.ok(new byte[] {'o', 'k'})
                    .header("X-Abort", "1").build());
            Response aborted = run(server, "GET", null, bytes);
            assertArrayEquals(new byte[] {'o', 'k'}, aborted.readEntity(byte[].class));
            assertEquals("1", aborted.getHeaderString("X-Abort"));

            ClientRequestFilter empty = ctx -> ctx.abortWith(Response.noContent().build());
            Response noContent = run(server, "GET", null, empty);
            assertEquals(204, noContent.getStatus());
            assertFalse(noContent.hasEntity());

            ClientRequestFilter number = ctx -> ctx.abortWith(Response.ok(42).build());
            assertEquals("42", run(server, "GET", null, number).readEntity(String.class));
        }
    }

    @Test
    void request_headers_seen_by_the_context_are_those_sent() throws Exception {
        try (RecordingServer server = RecordingServer.echo()) {
            ClientRequestFilter replace = ctx -> {
                MultivaluedHashMap<String, Object> headers = new MultivaluedHashMap<>();
                headers.putSingle(HttpHeaders.ACCEPT, "text/plain");
                ctx.getHeaders().clear();
                ctx.getHeaders().putAll(headers);
            };
            run(server, "GET", null, replace);
            assertEquals("text/plain", server.last().header(HttpHeaders.ACCEPT));
            assertNull(server.last().header(X_LIST));
            assertNotNull(server.last().header("Host"));
        }
    }

    private static String read(ClientResponseContext ctx) {
        try {
            return new String(ctx.getEntityStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return e.toString();
        }
    }

    private static void check(StringBuilder problems, String what, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            problems.append(what).append(": expected ").append(expected).append(" but was ").append(actual).append("; ");
        }
    }
}
