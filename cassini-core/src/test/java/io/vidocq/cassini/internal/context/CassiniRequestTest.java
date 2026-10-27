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
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Behaviour of {@link CassiniRequest}: conditional requests ({@code Request#evaluatePreconditions},
 * RFC 9110 §13) and content negotiation ({@code Request#selectVariant}, Jakarta REST 4.0 §3.8).
 */
class CassiniRequestTest {

    private static final String IF_MATCH = "If-Match";
    private static final String IF_NONE_MATCH = "If-None-Match";
    private static final String IF_MODIFIED_SINCE = "If-Modified-Since";
    private static final String IF_UNMODIFIED_SINCE = "If-Unmodified-Since";
    private static final String GET = "GET";
    private static final String PUT = "PUT";
    private static final String TAG_A = "\"a\"";
    private static final String TAG_B = "\"b\"";
    private static final String ACCEPT = "Accept";
    private static final String ACCEPT_LANGUAGE = "Accept-Language";
    private static final String ACCEPT_ENCODING = "Accept-Encoding";
    private static final String GZIP = "gzip";
    /** 1994-11-06T08:49:37Z, the RFC 9110 §5.6.7 example date. */
    private static final Date RFC_DATE = new Date(784111777000L);
    private static final String RFC_DATE_TEXT = "Sun, 06 Nov 1994 08:49:37 GMT";
    private static final Date EARLIER = new Date(RFC_DATE.getTime() - 60_000);
    private static final Date LATER = new Date(RFC_DATE.getTime() + 60_000);
    private static final EntityTag A = new EntityTag("a");

    @BeforeAll
    static void installDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static Request request(String method, String header, String value) {
        InMemoryExchange ex = InMemoryExchange.request(method, "/", null, null);
        if (header != null) ex.header(header, value);
        return new CassiniRequest(ex);
    }

    private static int status(Response.ResponseBuilder b) {
        return b.build().getStatus();
    }

    @Test
    void the_method_is_the_exchange_one() {
        assertEquals(PUT, request(PUT, null, null).getMethod());
        assertEquals("POST", new CassiniRequest("POST").getMethod());
    }

    @Test
    void a_request_without_exchange_meets_every_precondition() {
        Request r = new CassiniRequest(GET);
        assertNull(r.evaluatePreconditions(A));
        assertNull(r.evaluatePreconditions(RFC_DATE));
        assertNull(r.evaluatePreconditions());
        assertNull(r.selectVariant(List.of(new Variant(MediaType.TEXT_PLAIN_TYPE, (Locale) null, null))));
    }

    @Test
    void null_arguments_are_rejected() {
        Request r = new CassiniRequest(GET);
        assertThrows(IllegalArgumentException.class, () -> r.evaluatePreconditions((EntityTag) null));
        assertThrows(IllegalArgumentException.class, () -> r.evaluatePreconditions((Date) null));
        assertThrows(IllegalArgumentException.class, () -> r.selectVariant(null));
        List<Variant> noVariant = List.of();
        assertThrows(IllegalArgumentException.class, () -> r.selectVariant(noVariant));
    }

    // ── Entity tags ─────────────────────────────────────────────────────────

    @Test
    void if_match_passes_on_a_matching_tag_or_a_star() {
        assertNull(request(PUT, IF_MATCH, TAG_A).evaluatePreconditions(A));
        assertNull(request(PUT, IF_MATCH, "\"x\", \"a\"").evaluatePreconditions(A));
        assertNull(request(PUT, IF_MATCH, "*").evaluatePreconditions(A));
    }

    @Test
    void if_match_fails_with_412_and_the_current_tag() {
        Response r = request(PUT, IF_MATCH, TAG_B).evaluatePreconditions(A).build();
        assertEquals(412, r.getStatus());
        assertEquals(A, r.getEntityTag());
    }

    @Test
    void if_none_match_answers_304_to_a_safe_method_and_412_otherwise() {
        // RFC 9110 §13.1.2: 304 for GET/HEAD, 412 for any other method; weak comparison.
        assertEquals(304, status(request(GET, IF_NONE_MATCH, TAG_A).evaluatePreconditions(A)));
        assertEquals(304, status(request("HEAD", IF_NONE_MATCH, "W/\"a\"").evaluatePreconditions(A)));
        assertEquals(412, status(request(PUT, IF_NONE_MATCH, "*").evaluatePreconditions(A)));
        assertNull(request(GET, IF_NONE_MATCH, TAG_B).evaluatePreconditions(A));
    }

    // ── Dates ───────────────────────────────────────────────────────────────

    @Test
    void if_unmodified_since_fails_when_the_resource_changed_after() {
        assertEquals(412, status(request(PUT, IF_UNMODIFIED_SINCE, RFC_DATE_TEXT).evaluatePreconditions(LATER)));
        assertNull(request(PUT, IF_UNMODIFIED_SINCE, RFC_DATE_TEXT).evaluatePreconditions(EARLIER));
    }

    @Test
    void if_modified_since_answers_304_to_a_safe_method_when_unchanged() {
        assertEquals(304, status(request(GET, IF_MODIFIED_SINCE, RFC_DATE_TEXT).evaluatePreconditions(RFC_DATE)));
        assertEquals(304, status(request("HEAD", IF_MODIFIED_SINCE, RFC_DATE_TEXT).evaluatePreconditions(EARLIER)));
        assertNull(request(GET, IF_MODIFIED_SINCE, RFC_DATE_TEXT).evaluatePreconditions(LATER));
        // RFC 9110 §13.1.3: If-Modified-Since only applies to GET and HEAD.
        assertNull(request(PUT, IF_MODIFIED_SINCE, RFC_DATE_TEXT).evaluatePreconditions(EARLIER));
    }

    @Test
    void the_obsolete_date_formats_are_understood_and_garbage_is_ignored() {
        assertEquals(304, status(request(GET, IF_MODIFIED_SINCE, "Sunday, 06-Nov-94 08:49:37 GMT")
                .evaluatePreconditions(RFC_DATE)));
        assertEquals(304, status(request(GET, IF_MODIFIED_SINCE, "Sun Nov 6 08:49:37 2099")
                .evaluatePreconditions(RFC_DATE)));
        assertNull(request(GET, IF_MODIFIED_SINCE, "not a date").evaluatePreconditions(RFC_DATE));
    }

    @Test
    void the_tag_is_checked_before_the_date() {
        assertEquals(412, status(request(PUT, IF_MATCH, TAG_B).evaluatePreconditions(RFC_DATE, A)));
        assertEquals(304, status(request(GET, IF_MODIFIED_SINCE, RFC_DATE_TEXT).evaluatePreconditions(RFC_DATE, A)));
        assertNull(request(GET, null, null).evaluatePreconditions(RFC_DATE, A));
    }

    @Test
    void a_missing_resource_fails_any_if_match() {
        // Request#evaluatePreconditions(): "for a resource that does not currently exist".
        assertEquals(412, status(request(PUT, IF_MATCH, "*").evaluatePreconditions()));
        assertNull(request(PUT, null, null).evaluatePreconditions());
    }

    // ── Variants ────────────────────────────────────────────────────────────

    private static final Variant JSON = new Variant(MediaType.APPLICATION_JSON_TYPE, (Locale) null, null);
    private static final Variant HTML = new Variant(MediaType.TEXT_HTML_TYPE, (Locale) null, null);
    private static final Variant GERMAN = new Variant(null, Locale.GERMAN, null);
    private static final Variant ENGLISH = new Variant(null, Locale.ENGLISH, null);
    private static final Variant BROTLI = new Variant(null, (Locale) null, "br");
    private static final Variant GZIPPED = new Variant(null, (Locale) null, GZIP);

    @Test
    void the_first_acceptable_media_type_wins() {
        assertEquals(HTML, request(GET, ACCEPT, "text/html").selectVariant(List.of(JSON, HTML)));
        assertEquals(JSON, request(GET, null, null).selectVariant(List.of(JSON, HTML)));
        assertNull(request(GET, ACCEPT, "image/png").selectVariant(List.of(JSON, HTML)));
    }

    @Test
    void the_language_is_matched_on_its_primary_tag() {
        assertEquals(ENGLISH, request(GET, ACCEPT_LANGUAGE, "en-GB, fr;q=0.5").selectVariant(List.of(GERMAN, ENGLISH)));
        assertEquals(GERMAN, request(GET, ACCEPT_LANGUAGE, "fr, *;q=0.1").selectVariant(List.of(GERMAN, ENGLISH)));
        assertEquals(GERMAN, request(GET, null, null).selectVariant(List.of(GERMAN, ENGLISH)));
        assertNull(request(GET, ACCEPT_LANGUAGE, "fr, ,").selectVariant(List.of(GERMAN, ENGLISH)));
    }

    @Test
    void the_encoding_is_matched_by_name() {
        assertEquals(GZIPPED, request(GET, ACCEPT_ENCODING, "GZIP").selectVariant(List.of(BROTLI, GZIPPED)));
        assertEquals(BROTLI, request(GET, ACCEPT_ENCODING, "*").selectVariant(List.of(BROTLI, GZIPPED)));
        assertEquals(BROTLI, request(GET, null, null).selectVariant(List.of(BROTLI, GZIPPED)));
        assertNull(request(GET, ACCEPT_ENCODING, "deflate").selectVariant(List.of(BROTLI, GZIPPED)));
    }

    @Test
    void every_negotiated_dimension_is_recorded_for_the_vary_header() {
        InMemoryExchange ex = InMemoryExchange.request(GET, "/", null, null);
        CassiniRequest r = new CassiniRequest(ex);
        r.selectVariant(List.of(JSON));
        Object first = ex.getAttribute(CassiniRequest.ATTR_PENDING_VARY);
        assertEquals(Set.of(ACCEPT), first);
        r.selectVariant(List.of(ENGLISH, GZIPPED));
        assertSame(first, ex.getAttribute(CassiniRequest.ATTR_PENDING_VARY));
        assertEquals(Set.of(ACCEPT, ACCEPT_LANGUAGE, ACCEPT_ENCODING), first);
    }
}
