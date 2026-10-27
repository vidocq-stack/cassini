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

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The response and URI builders served by {@link CassiniRuntimeDelegate}. */
class CassiniBuildersTest {

    @BeforeAll
    static void installDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Test
    void the_default_status_is_204_without_an_entity_and_200_with_one() {
        assertEquals(204, Response.noContent().status(204).build().getStatus());
        assertEquals(204, new CassiniResponseBuilder().build().getStatus());
        assertEquals(200, new CassiniResponseBuilder().entity("x").build().getStatus());
    }

    @Test
    void an_explicit_status_wins_over_the_default() {
        assertEquals(201, new CassiniResponseBuilder().status(201).build().getStatus());
        assertEquals(202, new CassiniResponseBuilder().status(202, "later").entity("x").build().getStatus());
    }

    @Test
    void the_header_shortcuts_set_and_remove_their_header() {
        Response r = new CassiniResponseBuilder()
                .allow("GET", "POST").language("fr").type("text/plain").build();
        assertEquals("GET, POST", r.getHeaderString("Allow"));
        assertEquals("fr", r.getHeaderString("Content-Language"));
        assertEquals("text/plain", r.getHeaderString("Content-Type"));

        Response cleared = new CassiniResponseBuilder()
                .allow("GET").allow((String[]) null)
                .language("fr").language((String) null)
                .type("text/plain").type((String) null).build();
        assertNull(cleared.getHeaderString("Allow"));
        assertNull(cleared.getHeaderString("Content-Language"));
        assertNull(cleared.getHeaderString("Content-Type"));
    }

    @Test
    void the_typed_header_shortcuts_set_and_remove_their_header() {
        Response r = new CassiniResponseBuilder()
                .allow(Set.of("GET")).language(Locale.FRANCE).type(MediaType.TEXT_HTML_TYPE).build();
        assertEquals("GET", r.getHeaderString("Allow"));
        assertEquals("fr-FR", r.getHeaderString("Content-Language"));
        assertEquals("text/html", r.getHeaderString("Content-Type"));

        Response cleared = new CassiniResponseBuilder()
                .allow(Set.of("GET")).allow((Set<String>) null)
                .language(Locale.FRANCE).language((Locale) null)
                .type(MediaType.TEXT_HTML_TYPE).type((MediaType) null).build();
        assertNull(cleared.getHeaderString("Allow"));
        assertNull(cleared.getHeaderString("Content-Language"));
        assertNull(cleared.getHeaderString("Content-Type"));
    }

    @Test
    void a_variant_sets_type_language_and_encoding() {
        Response r = new CassiniResponseBuilder()
                .variant(new Variant(MediaType.APPLICATION_JSON_TYPE, Locale.ENGLISH, "gzip")).build();
        assertEquals("application/json", r.getHeaderString("Content-Type"));
        assertEquals("en", r.getHeaderString("Content-Language"));
        assertEquals("gzip", r.getHeaderString("Content-Encoding"));
    }

    @Test
    void path_segments_are_joined_by_exactly_one_slash() {
        assertEquals("a/b", UriBuilder.fromPath("").path("a").path("b").build().toString());
        assertEquals("http://h/r/a/b", UriBuilder.fromUri("http://h/r").path("a").path("b").build().toString());
        assertEquals("http://h/r/a/b", UriBuilder.fromUri("http://h/r").path("a/").path("/b").build().toString());
        assertEquals("http://h/r/a/b", UriBuilder.fromUri("http://h/r").path("a/").path("b").build().toString());
        assertEquals("http://h/r/a/b", UriBuilder.fromUri("http://h/r").path("a").path("/b").build().toString());
    }

    @Test
    void a_pre_encoded_value_keeps_its_slashes_and_a_plain_one_has_them_encoded() {
        assertEquals("http://h/x/a/b", UriBuilder.fromUri("http://h/x/{v}").buildFromEncoded("a/b").toString());
        assertEquals("http://h/x/a%2Fb", UriBuilder.fromUri("http://h/x/{v}").build("a/b").toString());
        assertEquals("http://h/x/a/b", UriBuilder.fromUri("http://h/x/{v}").build(new Object[]{"a/b"}, false).toString());
    }
}
