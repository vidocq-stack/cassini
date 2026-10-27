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
package io.vidocq.cassini.internal.filter;

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour of {@link CassiniResponseContext} ({@code ContainerResponseContext} Javadoc, Jakarta REST 4.0). */
class CassiniResponseContextTest {

    private static final String LINK = "Link";
    private static final String NEXT = "next";
    private static final String EXPIRES = "Expires";
    private static final String DATE_TEXT = "Sun, 06 Nov 1994 08:49:37 GMT";
    private static final Date RFC_DATE = new Date(784111777000L);

    @BeforeAll
    static void installDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static ContainerResponseContext context(String name, Object... values) {
        MultivaluedMap<String, Object> h = new MultivaluedHashMap<>();
        for (Object v : values) h.add(name, v);
        return new CassiniResponseContext(200, null, null, h);
    }

    private static ContainerResponseContext empty() {
        return new CassiniResponseContext(200, null, null, null);
    }

    @Test
    void the_status_and_its_info_move_together() {
        ContainerResponseContext c = empty();
        assertSame(Response.Status.OK, c.getStatusInfo());
        c.setStatus(404);
        assertSame(Response.Status.NOT_FOUND, c.getStatusInfo());
        c.setStatusInfo(Response.Status.CREATED);
        assertEquals(201, c.getStatus());
    }

    @Test
    void a_non_standard_status_still_has_a_status_info() {
        // CASSINI-014. ContainerResponseContext#getStatusInfo: "The returned value is never null".
        ContainerResponseContext c = new CassiniResponseContext(299, null, null, null);
        assertEquals(299, c.getStatusInfo().getStatusCode());
        assertEquals(Response.Status.Family.SUCCESSFUL, c.getStatusInfo().getFamily());
        c.setStatus(599);
        assertEquals(599, c.getStatusInfo().getStatusCode());
        assertEquals(Response.Status.Family.SERVER_ERROR, c.getStatusInfo().getFamily());
        assertEquals("", c.getStatusInfo().getReasonPhrase());
    }

    @Test
    void header_strings_go_through_the_header_delegates() {
        // CASSINI-014. ContainerResponseContext#getHeaderString / #getStringHeaders: each value is converted
        // "using a RuntimeDelegate.HeaderDelegate if one is available".
        ContainerResponseContext c = context(EXPIRES, RFC_DATE);
        assertEquals(DATE_TEXT, c.getHeaderString(EXPIRES));
        assertEquals(DATE_TEXT, c.getStringHeaders().getFirst(EXPIRES));
    }

    @Test
    void the_entity_its_type_and_annotations_are_replaceable() throws NoSuchMethodException {
        Annotation[] anns = getClass().getDeclaredMethod("the_entity_its_type_and_annotations_are_replaceable")
                .getAnnotations();
        CassiniResponseContext c = new CassiniResponseContext(200, null, String.class, anns, null);
        assertFalse(c.hasEntity());
        assertNull(c.getEntityClass());
        assertEquals(String.class, c.getEntityType());
        assertArrayEquals(anns, c.getEntityAnnotations());

        c.setEntity(42);
        assertTrue(c.hasEntity());
        assertEquals(Integer.class, c.getEntityClass());
        assertEquals(Integer.class, c.getEntityType());

        c.setEntity("x", null, MediaType.TEXT_PLAIN_TYPE);
        assertEquals(String.class, c.getEntityType());
        assertEquals(0, c.getEntityAnnotations().length);
        assertEquals(MediaType.TEXT_PLAIN_TYPE, c.getMediaType());

        c.setEntity(null, anns, null);
        assertNull(c.getEntity());
        assertEquals(String.class, c.getEntityType());
        c.setEntity(null);
        assertFalse(c.hasEntity());
    }

    @Test
    void the_entity_stream_can_be_wrapped_while_the_original_sink_stays() {
        CassiniResponseContext c = new CassiniResponseContext(200, null, null, null);
        assertSame(c.originalStream(), c.getEntityStream());
        ByteArrayOutputStream other = new ByteArrayOutputStream();
        c.setEntityStream(other);
        assertSame(other, c.getEntityStream());
    }

    @Test
    void header_strings_join_the_values() {
        ContainerResponseContext c = context("X-M", "a", 1);
        assertEquals("a,1", c.getHeaderString("X-M"));
        assertNull(c.getHeaderString("X-None"));
        assertEquals(List.of("a", "1"), c.getStringHeaders().get("X-M"));
        assertSame(c.getHeaders(), c.getHeaders());
    }

    @Test
    void containsHeaderString_is_case_insensitive() {
        ContainerResponseContext c = context("X-L", "a, b;c");
        assertTrue(c.containsHeaderString("X-L", "b;c"::equals));
        assertTrue(c.containsHeaderString("x-l", ";", "c"::equals));
        assertFalse(c.containsHeaderString("x-l", "z"::equals));
        assertFalse(c.containsHeaderString("X-None", _ -> true));
    }

    @Test
    void allowed_methods_are_upper_cased() {
        assertEquals(Set.of(), empty().getAllowedMethods());
        assertEquals(Set.of("GET", "POST"), context("Allow", "get, Post").getAllowedMethods());
    }

    @Test
    void the_dates_are_typed_or_parsed() {
        assertEquals(RFC_DATE, context("Date", DATE_TEXT).getDate());
        assertEquals(RFC_DATE, context("Date", "Sunday, 06-Nov-94 08:49:37 GMT").getDate());
        assertEquals(RFC_DATE, context("Last-Modified", RFC_DATE).getLastModified());
        assertNull(context("Date", "yesterday").getDate());
        assertNull(empty().getLastModified());
    }

    @Test
    void language_length_and_media_type() {
        assertNull(empty().getLanguage());
        assertEquals(Locale.FRANCE, context("Content-Language", "fr-FR").getLanguage());
        assertEquals(-1, empty().getLength());
        assertEquals(3, context("Content-Length", "3").getLength());
        assertEquals(-1, context("Content-Length", "three").getLength());
        assertNull(empty().getMediaType());
        assertEquals(MediaType.APPLICATION_XML_TYPE, context("Content-Type", "application/xml").getMediaType());
    }

    @Test
    void cookies_are_typed_or_parsed() {
        NewCookie typed = new NewCookie.Builder("a").value("1").build();
        var cookies = context("Set-Cookie", typed, "b=2;Path=/p").getCookies();
        assertEquals(typed, cookies.get("a"));
        assertEquals("/p", cookies.get("b").getPath());
        assertTrue(empty().getCookies().isEmpty());
    }

    @Test
    void the_entity_tag_is_typed_or_parsed() {
        assertNull(empty().getEntityTag());
        assertEquals(new EntityTag("t"), context("ETag", new EntityTag("t")).getEntityTag());
        assertEquals(new EntityTag("t", true), context("ETag", "W/\"t\"").getEntityTag());
    }

    @Test
    void the_location_is_a_uri() {
        assertNull(empty().getLocation());
        assertEquals(URI.create("http://h/x"), context("Location", "http://h/x").getLocation());
    }

    @Test
    void links_are_typed_or_parsed_and_looked_up_by_relation() {
        assertEquals(Set.of(), empty().getLinks());
        Link typed = Link.fromUri("http://h/1").rel(NEXT).build();
        ContainerResponseContext c = context(LINK, typed, "<http://h/0>; rel=\"prev\"", "<a b>");
        assertEquals(2, c.getLinks().size());
        assertTrue(c.hasLink("prev"));
        assertFalse(c.hasLink("up"));
        assertFalse(c.hasLink(null));
        assertEquals(typed, c.getLink(NEXT));
        assertNull(c.getLink(null));
        assertEquals(URI.create("http://h/1"), c.getLinkBuilder(NEXT).build().getUri());
        assertNull(c.getLinkBuilder("up"));
    }
}
