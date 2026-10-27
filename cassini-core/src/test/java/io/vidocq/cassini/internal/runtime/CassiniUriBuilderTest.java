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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriBuilderException;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Behaviour of {@link CassiniUriBuilder} through the public {@link UriBuilder} API,
 * checked against the {@code jakarta.ws.rs.core.UriBuilder} Javadoc (Jakarta REST 4.0).
 */
class CassiniUriBuilderTest {

    private static final String HOST_URI = "http://h";
    private static final String FULL_URI = "http://u:p@h:8080/a/b?x=1&y#f";
    private static final String TEMPLATE_PATH = "/{a}";

    @BeforeAll
    static void installDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/res")
    public static class Res {
        @GET @Path("sub") public String sub() { return "s"; }
        @GET public String plain() { return "p"; }
        @Path("twice") public Object twice() { return null; }
        @Path("twice/{x}") public Object twice(String x) { return x; }
    }

    public static class NotAResource {
        public void nothing() {
            // A method without @Path, for the rejection cases.
        }
    }

    // ── Factories and component copy ────────────────────────────────────────

    @Test
    void fromUri_keeps_every_component() {
        assertEquals(FULL_URI, UriBuilder.fromUri(FULL_URI).build().toString());
        assertEquals(FULL_URI, UriBuilder.fromUri(URI.create(FULL_URI)).build().toString());
    }

    @Test
    void fromUri_rejects_null_and_invalid_uris() {
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromUri((String) null));
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromUri((URI) null));
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromUri("http://h/a b"));
    }

    @Test
    void fromUri_keeps_an_opaque_uri() {
        assertEquals("mailto:joe@example.org", UriBuilder.fromUri("mailto:joe@example.org").build().toString());
    }

    @Test
    void uri_merges_only_the_components_the_new_uri_defines() {
        // UriBuilder#uri(URI): "Copies the non-null components of the supplied URI".
        UriBuilder b = UriBuilder.fromUri(FULL_URI);
        b.uri(URI.create("#g"));
        assertEquals("http://u:p@h:8080/a/b?x=1&y#g", b.build().toString());
        b.uri(URI.create("https://other/c"));
        assertEquals("https://other/c?x=1&y#g", b.build().toString());
        b.uri(URI.create("?z=2"));
        assertEquals("https://other/c?z=2#g", b.build().toString());
    }

    @Test
    void uri_switching_from_opaque_to_hierarchical_drops_the_scheme_specific_part() {
        UriBuilder b = UriBuilder.fromUri("mailto:joe@example.org").uri(URI.create("http://h/x"));
        assertEquals("http://h/x", b.build().toString());
    }

    @Test
    void uri_rejects_null() {
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.uri((URI) null));
        assertThrows(IllegalArgumentException.class, () -> b.uri((String) null));
    }

    @Test
    void uri_string_rejects_an_invalid_uri_that_is_not_a_template() {
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.uri("http://h/a b"));
    }

    @Test
    void uri_string_accepts_a_template_that_java_net_uri_cannot_parse() {
        UriBuilder b = UriBuilder.newInstance().uri("http://u@h:8080/{p a}/x?q={v}#f");
        assertEquals("http://u@h:8080/1/x?q=2#f", b.build("1", "2").toString());
    }

    @Test
    void uri_string_template_without_path_or_port() {
        assertEquals("http://h?q=1", UriBuilder.newInstance().uri("http://h?q={ v }").build("1").toString());
        assertEquals("http://h:8080/x", UriBuilder.newInstance().uri("http://h:8080/{x y}").build("x").toString());
        assertEquals("/x", UriBuilder.newInstance().uri("/{x y}").build("x").toString());
    }

    @Test
    void fromResource_and_path_class_use_the_class_level_path() {
        assertEquals("/res", UriBuilder.fromResource(Res.class).build().toString());
        assertEquals("http://h/res", UriBuilder.fromUri(HOST_URI).path(Res.class).build().toString());
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromResource(NotAResource.class));
        assertThrows(IllegalArgumentException.class, () -> UriBuilder.fromResource(null));
    }

    @Test
    void path_class_rejects_null_and_unannotated_classes() {
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.path((Class<?>) null));
        assertThrows(IllegalArgumentException.class, () -> b.path(NotAResource.class));
    }

    @Test
    void path_class_and_method_name_appends_the_method_path() {
        assertEquals("/res/sub", UriBuilder.fromResource(Res.class).path(Res.class, "sub").build().toString());
    }

    @Test
    void path_class_and_method_name_rejects_ambiguous_missing_or_null_input() {
        UriBuilder b = UriBuilder.newInstance();
        // Javadoc: IllegalArgumentException "if there is more than or less than one variant of the method
        // annotated with Path".
        assertThrows(IllegalArgumentException.class, () -> b.path(Res.class, "twice"));
        assertThrows(IllegalArgumentException.class, () -> b.path(Res.class, "plain"));
        assertThrows(IllegalArgumentException.class, () -> b.path(Res.class, (String) null));
        assertThrows(IllegalArgumentException.class, () -> b.path(null, "sub"));
    }

    @Test
    void path_method_appends_the_method_path() throws NoSuchMethodException {
        var sub = Res.class.getMethod("sub");
        var plain = Res.class.getMethod("plain");
        assertEquals("/res/sub", UriBuilder.fromResource(Res.class).path(sub).build().toString());
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.path(plain));
        assertThrows(IllegalArgumentException.class, () -> b.path((java.lang.reflect.Method) null));
    }

    // ── Scheme, authority, path ─────────────────────────────────────────────

    @Test
    void the_authority_parts_are_set_one_by_one() {
        URI u = UriBuilder.newInstance().scheme("https").userInfo("me").host("example.org").port(8443)
                .path("/x").build();
        assertEquals("https://me@example.org:8443/x", u.toString());
    }

    @Test
    void host_rejects_an_empty_or_malformed_host_and_null_clears_it() {
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.host(""));
        assertThrows(IllegalArgumentException.class, () -> b.host("a b"));
        assertEquals("/x", UriBuilder.fromUri("http://h/x").scheme(null).host(null).build().toString());
    }

    @Test
    void port_rejects_values_below_minus_one() {
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.port(-2));
        assertEquals("http://h/x", UriBuilder.fromUri("http://h:81/x").port(-1).build().toString());
    }

    @Test
    void path_rejects_null_and_ignores_empty() {
        UriBuilder b = UriBuilder.fromPath("x");
        assertThrows(IllegalArgumentException.class, () -> b.path((String) null));
        assertEquals("x", b.path("").build().toString());
    }

    @Test
    void replacePath_replaces_or_clears_the_path() {
        assertEquals("http://h/n", UriBuilder.fromUri("http://h/o?q=1").replacePath("/n").replaceQuery(null)
                .build().toString());
        assertEquals(HOST_URI, UriBuilder.fromUri("http://h/o").replacePath(null).build().toString());
    }

    @Test
    void segment_encodes_slashes_and_rejects_null() {
        assertEquals("a/b%2Fc", UriBuilder.fromPath("a").segment("b/c").build().toString());
        assertEquals("a/b", UriBuilder.fromPath("a/").segment("b").build().toString());
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.segment((String[]) null));
        assertThrows(IllegalArgumentException.class, () -> b.segment("ok", null));
    }

    @Test
    void path_encodes_characters_outside_pchar_and_keeps_percent_triplets() {
        assertEquals("/a%20b/%C3%A9/%2Fx", UriBuilder.fromPath("/a b/é/%2Fx").build().toString());
        // A lone '%' is not a triplet: it is encoded.
        assertEquals("/100%25", UriBuilder.fromPath("/100%").build().toString());
    }

    @Test
    void schemeSpecificPart_sets_an_opaque_or_a_hierarchical_part() {
        assertEquals("mailto:a@b", UriBuilder.newInstance().scheme("mailto").schemeSpecificPart("a@b")
                .build().toString());
        assertEquals("http://u@h:81/p?q=1",
                UriBuilder.newInstance().scheme("http").schemeSpecificPart("//u@h:81/p?q=1").build().toString());
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.schemeSpecificPart(null));
        assertThrows(IllegalArgumentException.class, () -> b.schemeSpecificPart("a b"));
    }

    // ── Matrix and query parameters, fragment ───────────────────────────────

    @Test
    void matrixParam_appends_encoded_parameters_to_the_last_segment() {
        assertEquals("/a;k=v1;k=v2;s=x%3By",
                UriBuilder.fromPath("/a").matrixParam("k", "v1", "v2").matrixParam("s", "x;y").build().toString());
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.matrixParam(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> b.matrixParam("k", (Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> b.matrixParam("k", "v", null));
    }

    @Test
    void replaceMatrixParam_replaces_or_removes_one_parameter() {
        assertEquals("/x/a;o=1;k=n",
                UriBuilder.fromPath("/x/a;k=v;o=1").replaceMatrixParam("k", "n").build().toString());
        assertEquals("/x/a;o=1",
                UriBuilder.fromPath("/x/a;k=v;o=1;k").replaceMatrixParam("k").build().toString());
        assertEquals("a;k=n", UriBuilder.fromPath("a").replaceMatrixParam("k", "n").build().toString());
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.replaceMatrixParam(null, "v"));
    }

    @Test
    void replaceMatrix_replaces_the_matrix_of_the_last_segment() {
        assertEquals("/x;a=1/y;n=2", UriBuilder.fromPath("/x;a=1/y;b=1").replaceMatrix("n=2").build().toString());
        assertEquals("/x/y", UriBuilder.fromPath("/x/y;b=1").replaceMatrix(null).build().toString());
        assertEquals("y;n=2", UriBuilder.fromPath("y").replaceMatrix("n=2").build().toString());
    }

    @Test
    void queryParam_appends_form_encoded_values() {
        assertEquals("/p?a=1&a=x+y&b=%26",
                UriBuilder.fromPath("/p").queryParam("a", 1, "x y").queryParam("b", "&").build().toString());
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.queryParam(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> b.queryParam("k", (Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> b.queryParam("k", "v", null));
    }

    @Test
    void replaceQueryParam_replaces_or_removes_one_parameter() {
        assertEquals("/p?b=2&a=3",
                UriBuilder.fromUri("/p?a=1&b=2").replaceQueryParam("a", 3).build().toString());
        assertEquals("/p?b=2", UriBuilder.fromUri("/p?a=1&b=2").replaceQueryParam("a").build().toString());
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.replaceQueryParam(null, "v"));
    }

    @Test
    void replaceQuery_keeps_empty_values_and_valueless_parameters() {
        assertEquals("/p?a=&b", UriBuilder.fromPath("/p").replaceQuery("a=&b").build().toString());
        assertEquals("/p", UriBuilder.fromUri("/p?a=1").replaceQuery("").build().toString());
    }

    @Test
    void fragment_is_set_encoded_and_cleared() {
        assertEquals("/p#a%20b", UriBuilder.fromPath("/p").fragment("a b").build().toString());
        assertEquals("/p", UriBuilder.fromUri("/p#f").fragment(null).build().toString());
    }

    // ── Templates ───────────────────────────────────────────────────────────

    @Test
    void build_rejects_missing_or_null_values() {
        UriBuilder b = UriBuilder.fromPath(TEMPLATE_PATH);
        assertThrows(IllegalArgumentException.class, b::build);
        assertThrows(IllegalArgumentException.class, () -> b.build((Object) null));
        assertThrows(IllegalArgumentException.class, () -> b.build((Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> b.build(null, true));
        assertThrows(IllegalArgumentException.class, () -> b.buildFromEncoded((Object[]) null));
    }

    @Test
    void a_template_with_a_regex_is_substituted_by_its_name() {
        assertEquals("/7", UriBuilder.fromPath("/{id: [0-9]+}").build(7).toString());
    }

    @Test
    void an_unclosed_brace_is_kept_as_a_literal() {
        assertEquals("/a%7Bb", UriBuilder.fromPath("/a{b").build().toString());
    }

    @Test
    void buildFromMap_uses_values_by_name() {
        Map<String, Object> values = Map.of("a", "x/y", "b", "%41");
        assertEquals("/x%2Fy/%2541", UriBuilder.fromPath("/{a}/{b}").buildFromMap(values).toString());
        assertEquals("/x/y/%2541", UriBuilder.fromPath("/{a}/{b}").buildFromMap(values, false).toString());
        assertEquals("/x/y/%41", UriBuilder.fromPath("/{a}/{b}").buildFromEncodedMap(values).toString());
    }

    @Test
    void buildFromMap_rejects_a_null_map() {
        UriBuilder b = UriBuilder.fromPath(TEMPLATE_PATH);
        assertThrows(IllegalArgumentException.class, () -> b.buildFromMap(null));
        assertThrows(IllegalArgumentException.class, () -> b.buildFromMap(null, true));
        assertThrows(IllegalArgumentException.class, () -> b.buildFromEncodedMap(null));
    }

    @Test
    void buildFromEncoded_keeps_percent_triplets_and_encodes_the_rest() {
        assertEquals("/%41%20b", UriBuilder.fromPath(TEMPLATE_PATH).buildFromEncoded("%41 b").toString());
    }

    @Test
    void resolveTemplate_fixes_a_value_before_build() {
        UriBuilder b = UriBuilder.fromPath("/{a}/{b}").resolveTemplate("a", "x/y");
        assertEquals("/x%2Fy/{b}", b.toTemplate());
        assertEquals("/x%2Fy/2", b.build("2").toString());
        assertEquals("/x/y/2",
                UriBuilder.fromPath("/{a}/{b}").resolveTemplate("a", "x/y", false).build("2").toString());
        assertEquals("/%41/2",
                UriBuilder.fromPath("/{a}/{b}").resolveTemplateFromEncoded("a", "%41").build("2").toString());
    }

    @Test
    void resolveTemplates_resolves_every_entry_of_the_map() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("a", "1/2");
        values.put("b", "%41");
        assertEquals("/1%2F2/%2541", UriBuilder.fromPath("/{a}/{b}").resolveTemplates(values).build().toString());
        assertEquals("/1/2/%2541",
                UriBuilder.fromPath("/{a}/{b}").resolveTemplates(values, false).build().toString());
        assertEquals("/1/2/%41",
                UriBuilder.fromPath("/{a}/{b}").resolveTemplatesFromEncoded(values).build().toString());
    }

    @Test
    void resolveTemplate_rejects_null_arguments() {
        UriBuilder b = UriBuilder.newInstance();
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplate(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplate("n", null));
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplate(null, "v", true));
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplateFromEncoded(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplateFromEncoded("n", null));
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplates(null));
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplates(null, true));
        assertThrows(IllegalArgumentException.class, () -> b.resolveTemplatesFromEncoded(null));
    }

    @Test
    void toTemplate_renders_every_component_and_resolved_values() {
        UriBuilder b = UriBuilder.fromUri("http://u@h:81/{a}/{b: .*}?q={a}&f#{a}").resolveTemplate("a", "1");
        assertEquals("http://u@h:81/1/{b: .*}?q=1&f#1", b.toTemplate());
        assertEquals("/{x", UriBuilder.fromPath("/{x").resolveTemplate("a", "1").toTemplate());
        assertEquals("/{a}", UriBuilder.fromPath(TEMPLATE_PATH).toTemplate());
    }

    @Test
    void an_unbuildable_uri_raises_UriBuilderException() {
        // A scheme must start with a letter (RFC 3986 §3.1).
        UriBuilder b = UriBuilder.newInstance().host("h").scheme("1x");
        assertThrows(UriBuilderException.class, b::build);
    }

    @Test
    void path_after_an_authority_starts_with_a_slash() {
        // CASSINI-009. UriBuilder#path: "Existing '/' characters are preserved"; a path following an
        // authority must begin with '/' (RFC 3986 §3.3), otherwise the host and the path run together.
        assertEquals("http://h/a", UriBuilder.fromUri(HOST_URI).path("a").build().toString());
        assertEquals("http://h/a/b", UriBuilder.fromUri(HOST_URI).path("a").path("b").build().toString());
        assertEquals("http://h/a", UriBuilder.fromUri(HOST_URI).segment("a").build().toString());
        assertEquals("http://h:81/x", UriBuilder.newInstance().scheme("http").host("h").port(81).path("x")
                .build().toString());
        assertEquals("http://h/{a}", UriBuilder.fromUri(HOST_URI).path("{a}").toTemplate());
    }

    @Test
    void clone_is_independent_of_the_original() {
        UriBuilder original = UriBuilder.fromUri("http://h/a?q=1#f").resolveTemplate("t", "v");
        UriBuilder copy = original.clone();
        assertNotSame(original, copy);
        copy.path("b").queryParam("r", 2);
        assertEquals("http://h/a?q=1#f", original.build().toString());
        assertEquals("http://h/a/b?q=1&r=2#f", copy.build().toString());
    }
}
