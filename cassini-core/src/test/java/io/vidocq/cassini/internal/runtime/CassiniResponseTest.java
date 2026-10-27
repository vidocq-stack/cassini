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

import io.vidocq.cassini.internal.RequestScope;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour of {@link CassiniResponse} and {@link CassiniResponseBuilder} through the
 * {@link Response} API (Jakarta REST 4.0, {@code jakarta.ws.rs.core.Response} Javadoc).
 */
class CassiniResponseTest {

    private static final String ALLOW = "Allow";
    private static final String CONTENT_TYPE = "Content-Type";
    private static final String LINK = "Link";
    private static final String TEXT_PLAIN = "text/plain";
    private static final String NEXT = "next";
    private static final String HTTP_H_X = "http://h/x";
    private static final String CONTENT_TYPE_LOWER = "content-type";
    private static final String RFC_DATE_TEXT = "Sun, 06 Nov 1994 08:49:37 GMT";
    private static final String CONTENT_LOCATION = "Content-Location";
    private static final String X_OLD = "X-Old";
    /** 1994-11-06T08:49:37Z, the RFC 9110 §5.6.7 example date. */
    private static final Date RFC_DATE = new Date(784111777000L);

    @BeforeAll
    static void installDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static Response withHeader(String name, Object value) {
        return new CassiniResponseBuilder().header(name, value).build();
    }

    // ── Status ──────────────────────────────────────────────────────────────

    @Test
    void a_known_status_maps_to_its_Status_constant() {
        assertSame(Response.Status.OK, Response.ok().build().getStatusInfo());
    }

    @Test
    void an_unknown_status_keeps_its_code_reason_and_family() {
        Response.StatusType custom = Response.status(299, "Custom").build().getStatusInfo();
        assertEquals(299, custom.getStatusCode());
        assertEquals("Custom", custom.getReasonPhrase());
        assertEquals(Response.Status.Family.SUCCESSFUL, custom.getFamily());
        assertEquals("", Response.status(599).build().getStatusInfo().getReasonPhrase());
    }

    @Test
    void a_custom_reason_phrase_wins_over_the_standard_one() {
        // CASSINI-013. Response.ResponseBuilder#status(int, String): the reason phrase is part of the status
        // the built response reports.
        Response.StatusType created = Response.status(201, "Made").build().getStatusInfo();
        assertEquals(201, created.getStatusCode());
        assertEquals("Made", created.getReasonPhrase());
        assertEquals(Response.Status.Family.SUCCESSFUL, created.getFamily());
        assertSame(Response.Status.CREATED, Response.status(201, "Made").status(201).build().getStatusInfo());
        assertSame(Response.Status.CREATED, Response.status(201, "Created").build().getStatusInfo());
    }

    // ── Entity ──────────────────────────────────────────────────────────────

    @Test
    void a_generic_entity_is_unwrapped() {
        GenericEntity<List<String>> ge = new GenericEntity<>(List.of("a")) { };
        assertEquals(List.of("a"), Response.ok(ge).build().getEntity());
    }

    @Test
    void readEntity_returns_the_in_memory_entity() {
        Response r = Response.ok("text").build();
        Annotation[] none = new Annotation[0];
        assertEquals("text", r.readEntity(String.class));
        assertEquals("text", r.readEntity(new GenericType<String>() { }));
        assertEquals("text", r.readEntity(String.class, none));
        assertEquals("text", r.readEntity(new GenericType<String>() { }, none));
        assertTrue(r.hasEntity());
        assertFalse(Response.noContent().build().hasEntity());
    }

    @Test
    void the_entity_annotations_are_kept() throws NoSuchMethodException {
        Annotation[] anns = CassiniResponseTest.class.getDeclaredMethod("the_entity_annotations_are_kept")
                .getAnnotations();
        CassiniResponse r = (CassiniResponse) new CassiniResponseBuilder().entity("x", anns).build();
        assertArrayEquals(anns, r.entityAnnotations());
        assertEquals(0, ((CassiniResponse) Response.ok().build()).entityAnnotations().length);
    }

    @Test
    void bufferEntity_has_nothing_to_buffer_for_an_in_memory_entity() {
        assertFalse(Response.ok("x").build().bufferEntity());
    }

    @Test
    void a_closed_response_rejects_entity_access() {
        // Response#close: "any attempts to manipulate (read, get, buffer) a message entity on a closed
        // response will result in an IllegalStateException being thrown".
        Response r = Response.ok("x").build();
        r.close();
        assertThrows(IllegalStateException.class, r::getEntity);
        assertThrows(IllegalStateException.class, r::hasEntity);
        assertThrows(IllegalStateException.class, r::bufferEntity);
    }

    // ── Typed header getters ────────────────────────────────────────────────

    @Test
    void getMediaType_reads_a_typed_or_textual_value_whatever_the_header_case() {
        assertNull(Response.ok().build().getMediaType());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, Response.ok().type(MediaType.TEXT_PLAIN_TYPE).build().getMediaType());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, Response.ok().type(TEXT_PLAIN).build().getMediaType());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, withHeader(CONTENT_TYPE_LOWER, TEXT_PLAIN).getMediaType());
    }

    @Test
    void getLanguage_parses_the_language_tag() {
        assertNull(Response.ok().build().getLanguage());
        assertEquals(Locale.CANADA_FRENCH, Response.ok().language("fr-CA").build().getLanguage());
    }

    @Test
    void getLength_is_minus_one_when_absent_or_invalid() {
        assertEquals(-1, Response.ok().build().getLength());
        assertEquals(12, withHeader("Content-Length", 12).getLength());
        assertEquals(-1, withHeader("Content-Length", "twelve").getLength());
    }

    @Test
    void getAllowedMethods_is_upper_cased_and_skips_empty_tokens() {
        assertEquals(Set.of(), Response.ok().build().getAllowedMethods());
        Response r = new CassiniResponseBuilder().header(ALLOW, "get, ,post").header(ALLOW, "PUT").build();
        assertEquals(Set.of("GET", "POST", "PUT"), r.getAllowedMethods());
    }

    @Test
    void getCookies_keeps_the_NewCookie_values() {
        assertTrue(Response.ok().build().getCookies().isEmpty());
        NewCookie c = new NewCookie.Builder("a").value("1").build();
        Response r = new CassiniResponseBuilder().cookie(c).header("Set-Cookie", "b=2").build();
        assertEquals(Map.of("a", c), r.getCookies());
    }

    @Test
    void getEntityTag_reads_a_typed_or_textual_value() {
        assertNull(Response.ok().build().getEntityTag());
        assertEquals(new EntityTag("v1"), Response.ok().tag("v1").build().getEntityTag());
        assertEquals(new EntityTag("v2", true), withHeader("ETag", "W/\"v2\"").getEntityTag());
    }

    @Test
    void the_dates_accept_the_three_http_date_formats() {
        // RFC 9110 §5.6.7: IMF-fixdate, obsolete RFC 850 and asctime formats.
        assertEquals(RFC_DATE, withHeader("Date", RFC_DATE_TEXT).getDate());
        assertEquals(RFC_DATE, withHeader("Date", "Sunday, 06-Nov-94 08:49:37 GMT").getDate());
        assertEquals(RFC_DATE, Response.ok().lastModified(RFC_DATE).build().getLastModified());
        assertNull(withHeader("Date", "yesterday").getDate());
        assertNull(Response.ok().build().getLastModified());
    }

    @Test
    void getLocation_is_absolute_or_resolved_against_the_request_base() throws Exception {
        assertNull(Response.ok().build().getLocation());
        assertEquals(URI.create(HTTP_H_X), Response.ok().location(URI.create(HTTP_H_X)).build().getLocation());
        assertEquals(URI.create("x"), Response.ok().location(URI.create("x")).build().getLocation());
        // Response.ResponseBuilder#location: a relative URI is resolved against the base URI of the application.
        URI resolved = RequestScope.call(() -> {
            CassiniResponseBuilder.setBaseUri(URI.create("http://h/app/"));
            return Response.created(URI.create("items/1")).build().getLocation();
        });
        assertEquals(URI.create("http://h/app/items/1"), resolved);
        assertNull(Response.ok().location(URI.create(HTTP_H_X)).location(null).build().getLocation());
    }

    @Test
    void clearBaseUri_outside_a_request_is_harmless() throws Exception {
        CassiniResponseBuilder.clearBaseUri();
        String unresolved = RequestScope.call(() -> {
            CassiniResponseBuilder.clearBaseUri();
            return Response.ok().contentLocation(URI.create("y")).build().getHeaderString(CONTENT_LOCATION);
        });
        assertEquals("y", unresolved);
    }

    @Test
    void contentLocation_is_set_and_removed() {
        assertEquals(HTTP_H_X, Response.ok().contentLocation(URI.create(HTTP_H_X)).build()
                .getHeaderString(CONTENT_LOCATION));
        assertNull(Response.ok().contentLocation(URI.create(HTTP_H_X)).contentLocation(null).build()
                .getHeaderString(CONTENT_LOCATION));
    }

    // ── Links ───────────────────────────────────────────────────────────────

    @Test
    void links_are_read_from_typed_and_textual_values() {
        assertEquals(Set.of(), Response.ok().build().getLinks());
        Link typed = Link.fromUri("http://h/1").rel(NEXT).build();
        Response r = new CassiniResponseBuilder().links(typed).header(LINK, "<http://h/0>; rel=\"prev\"")
                .header(LINK, "<a b>").build();
        assertEquals(2, r.getLinks().size());
        assertTrue(r.hasLink(NEXT));
        assertTrue(r.hasLink("prev"));
        assertFalse(r.hasLink("up"));
        assertFalse(r.hasLink(null));
        assertEquals(typed, r.getLink(NEXT));
        assertNull(r.getLink(null));
        assertEquals(URI.create("http://h/1"), r.getLinkBuilder(NEXT).build().getUri());
        assertNull(r.getLinkBuilder("up"));
    }

    @Test
    void link_shortcuts_add_a_link_and_reject_a_null_uri() {
        Response r = Response.ok().link(URI.create("http://h/a"), "a").link("http://h/b", "b").build();
        assertEquals(URI.create("http://h/b"), r.getLink("b").getUri());
        assertTrue(r.hasLink("a"));
        Response.ResponseBuilder b = Response.ok();
        assertThrows(IllegalArgumentException.class, () -> b.link((URI) null, "a"));
        assertThrows(IllegalArgumentException.class, () -> b.link((String) null, "a"));
        assertTrue(Response.ok().links(Link.fromUri("http://h").rel("x").build()).links((Link[]) null)
                .build().getLinks().isEmpty());
    }

    // ── Header views ────────────────────────────────────────────────────────

    @Test
    void getHeaders_is_a_case_insensitive_live_view() {
        Response r = Response.ok().type(TEXT_PLAIN).build();
        MultivaluedMap<String, Object> h = r.getHeaders();
        assertSame(r.getMetadata().getFirst(CONTENT_TYPE), h.getFirst(CONTENT_TYPE_LOWER));
        assertEquals(List.of(TEXT_PLAIN), h.get("CONTENT-TYPE"));
        assertTrue(h.containsKey(CONTENT_TYPE_LOWER));
        assertTrue(h.containsKey(CONTENT_TYPE));
        assertFalse(h.containsKey("x-none"));
        assertFalse(h.containsKey(null));
        assertNull(h.get(null));
        assertFalse(h.isEmpty());
        assertEquals(1, h.size());
        assertTrue(h.containsValue(List.of(TEXT_PLAIN)));
        assertEquals(Set.of(CONTENT_TYPE), h.keySet());
        assertEquals(1, h.values().size());
        assertEquals(1, h.entrySet().size());

        h.add("X-A", "1");
        h.addAll("X-A", "2", "3");
        h.addAll("X-A", List.of("4"));
        h.addFirst("X-A", "0");
        assertEquals("0,1,2,3,4", r.getHeaderString("x-a"));
        h.putSingle("X-B", "b");
        h.put("X-C", List.of("c"));
        h.putAll(Map.of("X-D", List.of("d")));
        assertEquals("b", r.getHeaderString("X-B"));
        assertEquals("c", r.getHeaderString("X-C"));
        assertEquals("d", r.getHeaderString("X-D"));
        assertEquals(List.of("b"), h.remove("x-b"));

        MultivaluedMap<String, Object> same = new MultivaluedHashMap<>();
        h.forEach(same::put);
        assertTrue(h.equalsIgnoreValueOrder(same));
        h.clear();
        assertTrue(r.getHeaders().isEmpty());
    }

    @Test
    void getStringHeaders_serialises_values_through_the_header_delegates() {
        Response r = Response.ok().type(MediaType.APPLICATION_JSON_TYPE).lastModified(RFC_DATE)
                .header("X-N", 7).build();
        MultivaluedMap<String, String> h = r.getStringHeaders();
        assertEquals("application/json", h.getFirst(CONTENT_TYPE_LOWER));
        assertEquals(RFC_DATE_TEXT, h.getFirst("Last-Modified"));
        assertEquals(List.of("7"), h.get("x-n"));
        assertTrue(h.containsKey("x-n"));
        assertTrue(h.containsKey("X-N"));
        assertFalse(h.containsKey("x-none"));
        assertFalse(h.containsKey(null));
        assertNull(h.getFirst(null));
        assertEquals(3, h.size());
        assertFalse(h.isEmpty());
        assertTrue(h.containsValue(List.of("7")));
        assertEquals(3, h.keySet().size());
        assertEquals(3, h.values().size());
        assertEquals(3, h.entrySet().size());

        h.add("X-A", "1");
        h.addAll("X-A", "2");
        h.addAll("X-A", List.of("3"));
        h.addFirst("X-A", "0");
        assertEquals(List.of("0", "1", "2", "3"), h.get("x-a"));
        h.putSingle("X-B", "b");
        h.put("X-C", List.of("c"));
        h.putAll(Map.of("X-D", List.of("d")));
        assertEquals(List.of("b"), h.remove("x-b"));
        MultivaluedMap<String, String> same = new MultivaluedHashMap<>();
        h.forEach(same::put);
        assertTrue(h.equalsIgnoreValueOrder(same));
        h.clear();
        assertTrue(h.isEmpty());
    }

    @Test
    void getHeaderString_joins_the_values_and_is_null_when_absent() {
        Response r = new CassiniResponseBuilder().header("X-M", "a").header("X-M", new EntityTag("t")).build();
        assertEquals("a,\"t\"", r.getHeaderString("x-m"));
        assertNull(r.getHeaderString("X-None"));
    }

    @Test
    void toString_names_the_status_and_the_entity() {
        assertEquals("CassiniResponse[status=200, entity=e]", Response.ok("e").build().toString());
    }

    // ── Builder ─────────────────────────────────────────────────────────────

    @Test
    void a_cloned_builder_is_independent() {
        Response.ResponseBuilder b = Response.status(201, "Made").entity("x").header("X-A", "1");
        Response.ResponseBuilder copy = b.clone();
        copy.header("X-B", "2").status(202);
        Response original = b.build();
        assertEquals(201, original.getStatus());
        assertNull(original.getHeaderString("X-B"));
        assertEquals(202, copy.build().getStatus());
        assertEquals("1", copy.build().getHeaderString("X-A"));
    }

    @Test
    void the_remaining_header_shortcuts_set_and_remove_their_header() {
        CacheControl cc = new CacheControl();
        cc.setMaxAge(5);
        Response r = Response.ok().cacheControl(cc).encoding("gzip").expires(RFC_DATE)
                .tag(new EntityTag("e")).build();
        assertEquals("no-transform, max-age=5", r.getHeaderString("Cache-Control"));
        assertEquals("gzip", r.getHeaderString("Content-Encoding"));
        assertEquals(RFC_DATE_TEXT, r.getHeaderString("Expires"));
        assertEquals("\"e\"", r.getHeaderString("ETag"));

        Response cleared = Response.ok().cacheControl(cc).cacheControl(null).encoding("gzip").encoding(null)
                .expires(RFC_DATE).expires(null).lastModified(RFC_DATE).lastModified(null)
                .tag("e").tag((String) null).tag(new EntityTag("f")).tag((EntityTag) null)
                .header("X-A", "1").header("X-A", null)
                .cookie(new NewCookie.Builder("c").build()).cookie((NewCookie[]) null)
                .build();
        assertTrue(cleared.getHeaders().isEmpty());
    }

    @Test
    void replaceAll_replaces_or_clears_every_header() {
        MultivaluedMap<String, Object> fresh = new MultivaluedHashMap<>();
        fresh.add("X-New", "n");
        Response r = Response.ok().header(X_OLD, "o").replaceAll(fresh).build();
        assertNull(r.getHeaderString(X_OLD));
        assertEquals("n", r.getHeaderString("X-New"));
        assertTrue(Response.ok().header(X_OLD, "o").replaceAll(null).build().getHeaders().isEmpty());
    }

    @Test
    void variants_set_the_vary_header_from_the_varying_dimensions() {
        // Response.ResponseBuilder#variants: "Add a Vary header that lists the available variants".
        Variant all = new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, "gzip");
        assertEquals("Accept, Accept-Language, Accept-Encoding",
                Response.ok().variants(all).build().getHeaderString("Vary"));
        assertEquals("Accept-Language",
                Response.ok().variants(List.of(new Variant(null, Locale.FRENCH, null))).build().getHeaderString("Vary"));
        assertNull(Response.ok().variants(all).variants((List<Variant>) null).build().getHeaderString("Vary"));
        assertNull(Response.ok().variants().build().getHeaderString("Vary"));
    }

    @Test
    void a_variant_without_any_dimension_set_changes_nothing() {
        assertTrue(Response.ok().variant(null).build().getHeaders().isEmpty());
        Response r = Response.ok().variant(new Variant(null, (Locale) null, "br")).build();
        assertEquals("br", r.getHeaderString("Content-Encoding"));
        assertNull(r.getHeaderString(CONTENT_TYPE));
    }
}
