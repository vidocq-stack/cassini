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
package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.internal.InMemoryExchange;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Behaviour of {@link CassiniHttpHeaders} ({@code jakarta.ws.rs.core.HttpHeaders} Javadoc, Jakarta REST 4.0). */
class CassiniHttpHeadersTest {

    private static final String ACCEPT = "Accept";
    private static final String ACCEPT_LANGUAGE = "Accept-Language";
    private static final String COOKIE = "Cookie";
    private static final String CONTENT_LENGTH = "Content-Length";

    @BeforeAll
    static void installDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static InMemoryExchange exchange() {
        return InMemoryExchange.get("/").replaceHeader(CONTENT_LENGTH, null);
    }

    private static HttpHeaders headers(String name, String value) {
        return new CassiniHttpHeaders(exchange().header(name, value));
    }

    @Test
    void request_headers_are_read_case_insensitively() {
        HttpHeaders h = new CassiniHttpHeaders(exchange().header("X-A", "1").header("X-A", "2"));
        assertEquals(List.of("1", "2"), h.getRequestHeader("x-a"));
        assertEquals("1,2", h.getHeaderString("X-A"));
        assertNull(h.getHeaderString("X-None"));
        assertEquals(List.of("1", "2"), h.getRequestHeaders().get("X-A"));
    }

    @Test
    void containsHeaderString_tests_every_trimmed_token() {
        HttpHeaders h = headers("X-L", "a, b;c");
        assertTrue(h.containsHeaderString("x-l", "b;c"::equals));
        assertTrue(h.containsHeaderString("X-L", ";", "c"::equals));
        assertFalse(h.containsHeaderString("X-L", "z"::equals));
        assertFalse(h.containsHeaderString("X-None", _ -> true));
    }

    @Test
    void acceptable_media_types_are_sorted_by_quality() {
        HttpHeaders h = headers(ACCEPT, "text/plain;q=0.5, text/html;q=oops, application/json");
        assertEquals(List.of(MediaType.TEXT_HTML_TYPE, MediaType.APPLICATION_JSON_TYPE, MediaType.TEXT_PLAIN_TYPE),
                h.getAcceptableMediaTypes().stream().map(m -> new MediaType(m.getType(), m.getSubtype())).toList());
        // HttpHeaders#getAcceptableMediaTypes: a single wildcard when no Accept header is present.
        assertEquals(List.of(MediaType.WILDCARD_TYPE), new CassiniHttpHeaders(exchange()).getAcceptableMediaTypes());
    }

    @Test
    void acceptable_languages_are_sorted_by_quality() {
        HttpHeaders h = headers(ACCEPT_LANGUAGE, "en;q=0.5, , fr, de;q=x;level=1");
        assertEquals(List.of(Locale.FRENCH, Locale.GERMAN, Locale.ENGLISH), h.getAcceptableLanguages());
    }

    @Test
    void without_accept_language_any_language_is_acceptable() {
        // CASSINI-015. HttpHeaders#getAcceptableLanguages: "If no acceptable languages are specified, a read-only
        // list containing a single wildcard Locale instance (with language field set to "*") is returned."
        List<Locale> none = new CassiniHttpHeaders(exchange()).getAcceptableLanguages();
        assertEquals(1, none.size());
        assertEquals("*", none.get(0).getLanguage());
        assertEquals(none, headers(ACCEPT_LANGUAGE, " ").getAcceptableLanguages());
    }

    @Test
    void the_entity_media_type_is_null_without_a_content_type() {
        assertNull(new CassiniHttpHeaders(exchange()).getMediaType());
        assertNull(headers("Content-Type", " ").getMediaType());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, headers("Content-Type", "text/plain").getMediaType());
    }

    @Test
    void the_language_accepts_the_java_locale_spelling() {
        assertNull(new CassiniHttpHeaders(exchange()).getLanguage());
        assertEquals(Locale.US, headers("Content-Language", "en_US").getLanguage());
        assertEquals(Locale.US, headers("Content-Language", "en-US").getLanguage());
    }

    @Test
    void cookies_carry_their_rfc_2109_attributes() {
        HttpHeaders h = headers(COOKIE, "$Version=1; a=\"1\"; $Path=/p; $Domain=d; flag; b=2");
        Map<String, Cookie> cookies = h.getCookies();
        assertEquals(new Cookie.Builder("a").value("1").version(1).path("/p").domain("d").build(), cookies.get("a"));
        assertEquals(new Cookie.Builder("b").value("2").version(1).build(), cookies.get("b"));
        assertEquals(2, cookies.size());
    }

    @Test
    void cookies_from_several_headers_are_merged() {
        HttpHeaders h = new CassiniHttpHeaders(exchange().header(COOKIE, "$Version=x; a=1").header(COOKIE, "b=2"));
        assertEquals(new Cookie.Builder("a").value("1").version(0).build(), h.getCookies().get("a"));
        assertEquals("2", h.getCookies().get("b").getValue());
        assertTrue(new CassiniHttpHeaders(exchange()).getCookies().isEmpty());
        assertTrue(headers(COOKIE, "nothing").getCookies().isEmpty());
    }

    @Test
    void the_date_is_parsed_or_null() {
        assertEquals(new Date(784111777000L), headers("Date", "Sun, 06 Nov 1994 08:49:37 GMT").getDate());
        assertNull(headers("Date", "yesterday").getDate());
        assertNull(new CassiniHttpHeaders(exchange()).getDate());
    }

    @Test
    void the_length_is_minus_one_when_absent_or_invalid() {
        assertEquals(5, headers(CONTENT_LENGTH, "5").getLength());
        assertEquals(-1, headers(CONTENT_LENGTH, "five").getLength());
        assertEquals(-1, new CassiniHttpHeaders(exchange()).getLength());
    }
}
