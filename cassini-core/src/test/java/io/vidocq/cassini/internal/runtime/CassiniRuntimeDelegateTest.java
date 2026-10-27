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

import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.UriBuilderException;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.ext.RuntimeDelegate.HeaderDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Contract of the header delegates served by {@link CassiniRuntimeDelegate}.
 *
 * <p>This is the single behavioural reference for ALL transports: since the
 * CASSINI-003 unification, {@code ChappeRuntimeDelegate} and
 * {@code JdkHttpRuntimeDelegate} inherit these delegates instead of duplicating
 * them, so the REST TCK (which runs on the Chappe transport) exercises exactly
 * what is asserted here.</p>
 */
class CassiniRuntimeDelegateTest {

    private final CassiniRuntimeDelegate delegate = new CassiniRuntimeDelegate();

    @BeforeAll
    static void installDelegate() {
        // cassini-core deliberately does not register itself as a ServiceLoader
        // provider (transports do) — the jakarta.ws.rs API <clinit>s need one.
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private <T> HeaderDelegate<T> headerDelegate(Class<T> type) {
        return delegate.createHeaderDelegate(type);
    }

    // ── CacheControl ──────────────────────────────────────────────────────────

    @Test
    void cacheControl_publicDirectiveIsKeptAsExtension() {
        // CASSINI-003: the core copy used to silently drop "public" while the
        // chappe copy (TCK-validated) records it in the cache extension map.
        // jakarta.ws.rs.core.CacheControl does not model "public", so the
        // extension map is the only place where a round-trip can preserve it.
        HeaderDelegate<CacheControl> hd = headerDelegate(CacheControl.class);
        CacheControl cc = hd.fromString("public, max-age=60");
        assertTrue(cc.getCacheExtension().containsKey("public"),
                "'public' must survive parsing via the cache extension map");
        assertEquals(60, cc.getMaxAge());
        String out = hd.toString(cc);
        assertTrue(out.contains("public"), "round-trip must preserve 'public': " + out);
        assertTrue(out.contains("max-age=60"));
    }

    @Test
    void cacheControl_roundTripOfModeledDirectives() {
        HeaderDelegate<CacheControl> hd = headerDelegate(CacheControl.class);
        // RFC 7234: private/no-cache may carry a QUOTED comma-separated field
        // list — the tokenizer must not split inside the quotes (CASSINI-003
        // also covers this: both pre-unification copies broke "a,b" into "a).
        CacheControl cc = hd.fromString(
                "private=\"a,b\", no-cache=\"c\", no-store, no-transform, "
                        + "must-revalidate, proxy-revalidate, max-age=1, s-maxage=2");
        assertTrue(cc.isPrivate());
        assertEquals(java.util.List.of("a", "b"), cc.getPrivateFields());
        assertTrue(cc.isNoCache());
        assertEquals(java.util.List.of("c"), cc.getNoCacheFields());
        assertTrue(cc.isNoStore());
        assertTrue(cc.isNoTransform());
        assertTrue(cc.isMustRevalidate());
        assertTrue(cc.isProxyRevalidate());
        assertEquals(1, cc.getMaxAge());
        assertEquals(2, cc.getSMaxAge());

        CacheControl back = hd.fromString(hd.toString(cc));
        assertEquals(hd.toString(cc), hd.toString(back), "toString must be stable");
    }

    // ── Date (RFC 1123 / IMF-fixdate) ────────────────────────────────────────

    @Test
    void date_formatsAsImfFixdateInGmt() {
        HeaderDelegate<Date> hd = headerDelegate(Date.class);
        // 2026-06-07T08:09:01Z — single-digit day/hour exercise zero-padding.
        Date d = Date.from(java.time.Instant.parse("2026-06-07T08:09:01Z"));
        assertEquals("Sun, 07 Jun 2026 08:09:01 GMT", hd.toString(d));
    }

    @Test
    void date_parsesItsOwnOutputAndKnownDates() {
        HeaderDelegate<Date> hd = headerDelegate(Date.class);
        Date d = Date.from(java.time.Instant.parse("2026-06-12T17:30:05Z"));
        assertEquals(d, hd.fromString(hd.toString(d)));
        Date known = hd.fromString("Fri, 12 Jun 2026 17:30:05 GMT");
        assertEquals(d, known);
    }

    @Test
    void date_unparseableInputYieldsNull() {
        HeaderDelegate<Date> hd = headerDelegate(Date.class);
        assertNull(hd.fromString("not a date"));
    }

    // ── NewCookie ─────────────────────────────────────────────────────────────

    @Test
    void newCookie_roundTrip() {
        HeaderDelegate<NewCookie> hd = headerDelegate(NewCookie.class);
        NewCookie c = hd.fromString(
                "session=abc; Path=/app; Domain=example.org; Max-Age=30; Secure; HttpOnly");
        assertEquals("session", c.getName());
        assertEquals("abc", c.getValue());
        assertEquals("/app", c.getPath());
        assertEquals(EXAMPLE_ORG, c.getDomain());
        assertEquals(30, c.getMaxAge());
        assertTrue(c.isSecure());
        assertTrue(c.isHttpOnly());

        NewCookie back = hd.fromString(hd.toString(c));
        assertEquals(c.getName(), back.getName());
        assertEquals(c.getValue(), back.getValue());
        assertEquals(c.getPath(), back.getPath());
        assertEquals(c.getMaxAge(), back.getMaxAge());
        assertEquals(c.isSecure(), back.isSecure());
        assertEquals(c.isHttpOnly(), back.isHttpOnly());
    }

    // ── Cookie ────────────────────────────────────────────────────────────────

    @Test
    void cookie_preservesNameAndValueCase() {
        HeaderDelegate<Cookie> hd = headerDelegate(Cookie.class);
        Cookie c = hd.fromString("$Version=1; NaMe=VaLue; $Path=\"/p\"; $Domain=d.org");
        assertEquals("NaMe", c.getName());
        assertEquals("VaLue", c.getValue());
        assertEquals("/p", c.getPath());
        assertEquals("d.org", c.getDomain());
        assertEquals(1, c.getVersion());
    }

    // ── EntityTag ─────────────────────────────────────────────────────────────

    @Test
    void entityTag_weakAndStrongRoundTrip() {
        HeaderDelegate<EntityTag> hd = headerDelegate(EntityTag.class);
        EntityTag weak = hd.fromString("W/\"v1\"");
        assertTrue(weak.isWeak());
        assertEquals("v1", weak.getValue());
        assertEquals("W/\"v1\"", hd.toString(weak));

        EntityTag strong = hd.fromString("\"v2\"");
        assertFalse(strong.isWeak());
        assertEquals("\"v2\"", hd.toString(strong));
    }

    // ── Link ──────────────────────────────────────────────────────────────────

    @Test
    void link_parsesUriAndParams() {
        HeaderDelegate<Link> hd = headerDelegate(Link.class);
        Link l = hd.fromString("<http://example.org/x>; rel=next; title=\"page 2\"");
        assertEquals(java.net.URI.create("http://example.org/x"), l.getUri());
        assertEquals("next", l.getRel());
        assertEquals("page 2", l.getTitle());
        String out = hd.toString(l);
        assertTrue(out.startsWith("<http://example.org/x>"), out);
    }

    @Test
    void linkBuilder_substitutesTheTemplateValues() {
        Link l = delegate.createLinkBuilder()
                .uriBuilder(jakarta.ws.rs.core.UriBuilder.fromUri("http://e.org/items/{id}")).rel("self").build("42");
        assertEquals("http://e.org/items/42", l.getUri().toString());
    }

    @Test
    void linkBuilder_substitutesTemplatesLeftInItsUri() {
        Link l = delegate.createLinkBuilder()
                .uri(java.net.URI.create("http://e.org/%7Bkind%7D/%7Bid:%5Cd+%7D/%7Bkind%7D")).rel("self").build("items", "42");
        assertEquals("http://e.org/items/42/items", l.getUri().toString());
        Link.Builder unresolved = delegate.createLinkBuilder().uri(java.net.URI.create("http://e.org/%7Bid%7D"));
        assertThrows(IllegalArgumentException.class, unresolved::build);
    }

    @Test
    void linkBuilder_paramOrderIsPreservedInToString() {
        // LinkedHashMap-backed params: insertion order must survive build().
        Link l = delegate.createLinkBuilder()
                .uri("http://e.org/a").rel("self").title("t").param("z", "1").build();
        assertEquals("<http://e.org/a>;rel=\"self\";title=\"t\";z=\"1\"", l.toString());
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    private static final String E_ORG_A = "http://e.org/a";
    private static final String VALUE_IS_NULL = "a null value is rejected";
    private static final String EXAMPLE_ORG = "example.org";
    private static final String TEXT_PLAIN = "text/plain";
    private static final String BRACKETED_A = "<http://e.org/a>";

    public static class App extends Application { }

    @Test
    void the_builders_are_cassini_ones() throws java.io.IOException {
        assertInstanceOf(CassiniUriBuilder.class, delegate.createUriBuilder());
        assertInstanceOf(CassiniResponseBuilder.class, delegate.createResponseBuilder());
        assertEquals("p", delegate.createEntityPartBuilder("p").content("x").build().getName());
    }

    @Test
    void createEndpoint_validates_then_is_unsupported() {
        Application app = new App();
        assertThrows(IllegalArgumentException.class, () -> delegate.createEndpoint(null, Object.class));
        assertThrows(IllegalArgumentException.class, () -> delegate.createEndpoint(app, null));
        assertThrows(UnsupportedOperationException.class, () -> delegate.createEndpoint(app, Object.class));
    }

    @Test
    void bootstrap_needs_a_transport_adapter() {
        SeBootstrap.Configuration config = delegate.createConfigurationBuilder().build();
        var byInstance = delegate.bootstrap(new App(), config).toCompletableFuture();
        var byClass = delegate.bootstrap(App.class, config).toCompletableFuture();
        var unbuildable = delegate.bootstrap(AbstractApp.class, config).toCompletableFuture();
        assertInstanceOf(UnsupportedOperationException.class,
                assertThrows(ExecutionException.class, byInstance::get).getCause());
        assertInstanceOf(UnsupportedOperationException.class,
                assertThrows(ExecutionException.class, byClass::get).getCause());
        assertInstanceOf(ReflectiveOperationException.class,
                assertThrows(ExecutionException.class, unbuildable::get).getCause());
    }

    public abstract static class AbstractApp extends Application { }

    @Test
    void the_configuration_has_defaults_explicit_properties_and_external_ones() {
        SeBootstrap.Configuration defaults = delegate.createConfigurationBuilder().build();
        assertEquals("HTTP", defaults.protocol());
        assertEquals("localhost", defaults.host());
        assertEquals("/", defaults.rootPath());

        SeBootstrap.Configuration c = delegate.createConfigurationBuilder()
                .property("custom", "v")
                .from((name, type) -> switch (name) {
                    case SeBootstrap.Configuration.HOST -> Optional.of(type.cast(EXAMPLE_ORG));
                    case SeBootstrap.Configuration.PORT -> Optional.of(type.cast(8080));
                    case SeBootstrap.Configuration.ROOT_PATH -> throw new IllegalStateException("source down");
                    default -> Optional.empty();
                })
                .build();
        assertEquals("v", c.property("custom"));
        assertEquals(EXAMPLE_ORG, c.host());
        assertEquals(8080, c.port());
        assertEquals("/", c.rootPath());
    }

    // ── Header delegates: argument checks and remaining branches ─────────────

    @Test
    void every_header_delegate_rejects_null() {
        assertThrows(IllegalArgumentException.class, () -> delegate.createHeaderDelegate(null));
        for (Class<?> type : List.of(MediaType.class, NewCookie.class, Cookie.class, EntityTag.class,
                CacheControl.class, Link.class, Date.class, Object.class)) {
            HeaderDelegate<?> hd = delegate.createHeaderDelegate(type);
            assertThrows(IllegalArgumentException.class, () -> hd.fromString(null), VALUE_IS_NULL);
        }
        for (Class<?> type : List.of(MediaType.class, NewCookie.class, Cookie.class, EntityTag.class,
                CacheControl.class, Link.class, Object.class)) {
            @SuppressWarnings("unchecked")
            HeaderDelegate<Object> hd = (HeaderDelegate<Object>) delegate.createHeaderDelegate(type);
            assertThrows(IllegalArgumentException.class, () -> hd.toString(null), VALUE_IS_NULL);
        }
    }

    @Test
    void mediaType_and_toString_delegates_round_trip() {
        HeaderDelegate<MediaType> mt = headerDelegate(MediaType.class);
        assertEquals(MediaType.TEXT_PLAIN_TYPE, mt.fromString(TEXT_PLAIN));
        assertEquals("text/plain;charset=UTF-8", mt.toString(MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8")));
        HeaderDelegate<Integer> other = headerDelegate(Integer.class);
        assertEquals("12", other.toString(12));
        assertEquals("12", ((HeaderDelegate<?>) other).fromString("12"));
    }

    @Test
    void newCookie_parses_every_attribute_and_skips_unknown_ones() {
        HeaderDelegate<NewCookie> hd = headerDelegate(NewCookie.class);
        NewCookie c = hd.fromString("n=\"v\"; Comment=hello; Version=2; Max-Age=x; Unknown=1; Flag");
        assertEquals("v", c.getValue());
        assertEquals("hello", c.getComment());
        assertEquals(2, c.getVersion());
        assertEquals(-1, c.getMaxAge());
        assertFalse(c.isSecure());
        assertNull(c.getPath());
    }

    @Test
    void newCookie_serialises_only_the_set_attributes_and_quotes_when_needed() {
        HeaderDelegate<NewCookie> hd = headerDelegate(NewCookie.class);
        NewCookie full = new NewCookie.Builder("n").value("a b").path("/p").domain("d").maxAge(5)
                .comment("c;d").version(1).secure(true).httpOnly(true).build();
        assertEquals("n=\"a b\";Version=1;Path=/p;Domain=d;Max-Age=5;Comment=\"c;d\";Secure;HttpOnly",
                hd.toString(full));
        NewCookie bare = new NewCookie.Builder("n").version(0).build();
        assertEquals("n=", hd.toString(bare));
    }

    @Test
    void cookie_serialises_its_rfc_2109_attributes() {
        HeaderDelegate<Cookie> hd = headerDelegate(Cookie.class);
        Cookie full = new Cookie.Builder("n").value("v,w").path("/p").domain("d").version(1).build();
        assertEquals("$Version=1;n=\"v,w\";$Path=/p;$Domain=d", hd.toString(full));
        assertEquals("n=v", hd.toString(new Cookie.Builder("n").value("v").version(0).build()));
    }

    @Test
    void cookie_without_a_value_is_named_after_the_whole_header() {
        HeaderDelegate<Cookie> hd = headerDelegate(Cookie.class);
        assertEquals("solo", hd.fromString("solo").getName());
        Cookie onlyAttributes = hd.fromString("$Version=x; ;$Domain=d");
        assertEquals("$Version=x; ;$Domain=d", onlyAttributes.getName());
        assertEquals(0, onlyAttributes.getVersion());
    }

    @Test
    void cacheControl_serialises_flags_field_lists_and_extensions() {
        HeaderDelegate<CacheControl> hd = headerDelegate(CacheControl.class);
        CacheControl cc = new CacheControl();
        cc.setPrivate(true);
        cc.setNoCache(true);
        cc.setNoTransform(false);
        cc.setSMaxAge(3);
        cc.getCacheExtension().put("ext", "1");
        cc.getCacheExtension().put("flag", null);
        cc.getCacheExtension().put("empty", "");
        assertEquals("private, no-cache, s-maxage=3, ext=1, flag, empty", hd.toString(cc));

        CacheControl parsed = hd.fromString("no-cache, private=\"\", max-age=oops, , s-maxage=x");
        assertTrue(parsed.isNoCache());
        assertTrue(parsed.getNoCacheFields().isEmpty());
        assertTrue(parsed.getPrivateFields().isEmpty());
        assertEquals(-1, parsed.getMaxAge());
        assertEquals(-1, parsed.getSMaxAge());
        assertFalse(parsed.isNoTransform());
        assertEquals(List.of("a"), hd.fromString("no-cache=\"a, ,\"").getNoCacheFields());
    }

    @Test
    void link_header_without_brackets_or_params() {
        HeaderDelegate<Link> hd = headerDelegate(Link.class);
        assertEquals(URI.create(E_ORG_A), hd.fromString(E_ORG_A).getUri());
        Link bare = hd.fromString(BRACKETED_A);
        assertTrue(bare.getParams().isEmpty());
        assertEquals(BRACKETED_A, hd.toString(bare));
        assertEquals(Map.of("rel", "x"), hd.fromString("<http://e.org/a>; novalue; rel=x").getParams());
    }

    // ── Variant list builder ─────────────────────────────────────────────────

    @Test
    void variantListBuilder_builds_the_cartesian_product_of_each_group() {
        // Variant.VariantListBuilder#add: "the cartesian product of the current set of media types, languages
        // and encodings" is added to the list.
        List<Variant> variants = delegate.createVariantListBuilder()
                .mediaTypes(MediaType.TEXT_PLAIN_TYPE, MediaType.TEXT_HTML_TYPE).languages(Locale.ENGLISH).add()
                .encodings("gzip").add()
                .add()
                .build();
        assertEquals(List.of(
                new Variant(MediaType.TEXT_PLAIN_TYPE, Locale.ENGLISH, null),
                new Variant(MediaType.TEXT_HTML_TYPE, Locale.ENGLISH, null),
                new Variant(null, (Locale) null, "gzip")), variants);
        assertTrue(delegate.createVariantListBuilder().build().isEmpty());
    }

    @Test
    void variantListBuilder_is_reset_by_build() {
        // CASSINI-016. Variant.VariantListBuilder#build: "After this method is called the builder is reset to an
        // empty state."
        Variant.VariantListBuilder b = delegate.createVariantListBuilder();
        assertEquals(1, b.mediaTypes(MediaType.TEXT_PLAIN_TYPE).build().size());
        assertEquals(List.of(new Variant(null, Locale.ENGLISH, null)), b.languages(Locale.ENGLISH).build());
        assertTrue(b.build().isEmpty());
    }

    // ── Link builder ─────────────────────────────────────────────────────────

    @Test
    void linkBuilder_copies_an_existing_link() {
        Link original = Link.fromUri(E_ORG_A).rel("self").type(TEXT_PLAIN).build();
        Link copy = delegate.createLinkBuilder().link(original).build();
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
        assertEquals(TEXT_PLAIN, copy.getType());
    }

    @Test
    void linkBuilder_parses_a_link_header() {
        Link l = delegate.createLinkBuilder().link("<http://e.org/a>; rel=\"a b\"; title=t; skipped").build();
        assertEquals(URI.create(E_ORG_A), l.getUri());
        assertEquals(List.of("a", "b"), l.getRels());
        assertEquals("t", l.getTitle());
        assertEquals(URI.create(E_ORG_A), delegate.createLinkBuilder().link(BRACKETED_A).build().getUri());
        assertEquals(URI.create(E_ORG_A), delegate.createLinkBuilder().link(E_ORG_A).build().getUri());
        assertEquals(List.of(), delegate.createLinkBuilder().link(E_ORG_A).build().getRels());
    }

    @Test
    void linkBuilder_rejects_malformed_links() {
        Link.Builder b = delegate.createLinkBuilder();
        assertThrows(IllegalArgumentException.class, () -> b.link((String) null));
        assertThrows(IllegalArgumentException.class, () -> b.link("<>; rel=x"));
        assertThrows(IllegalArgumentException.class, () -> b.link("<a<b>"));
        assertThrows(IllegalArgumentException.class, () -> b.link("<a>>"));
        assertThrows(IllegalArgumentException.class, () -> b.uri((URI) null));
        assertThrows(IllegalArgumentException.class, () -> b.uri((String) null));
        assertThrows(IllegalArgumentException.class, () -> b.uri("a b"));
        assertThrows(IllegalArgumentException.class, () -> b.uri("{a} b"));
        assertThrows(IllegalArgumentException.class, () -> b.rel(null));
        assertThrows(IllegalArgumentException.class, () -> b.param(null, "v"));
        assertThrows(IllegalArgumentException.class, () -> b.build((Object[]) null));
        assertThrows(IllegalArgumentException.class, () -> b.buildRelativized(null));
    }

    @Test
    void linkBuilder_rejects_an_unbuildable_uri() {
        // Link.Builder#build: UriBuilderException "if a URI cannot be constructed based on the current state".
        // "a_b:x" is not a valid server authority: java.net.URI keeps it as a registry-based one, without host.
        Link.Builder badAuthority = delegate.createLinkBuilder().uri(URI.create("http://a_b:x/"));
        assertThrows(UriBuilderException.class, badAuthority::build);
    }

    @Test
    void linkBuilder_resolves_templates_and_a_base() {
        Link templated = delegate.createLinkBuilder().uri("http://e.org/{a}/{b}/{a}").build("1", "2");
        assertEquals(URI.create("http://e.org/1/2/1"), templated.getUri());
        Link relative = delegate.createLinkBuilder().baseUri("http://e.org/base/").uri("x").build();
        assertEquals(URI.create("http://e.org/base/x"), relative.getUri());
        Link relativeToUri = delegate.createLinkBuilder().baseUri(URI.create("http://e.org/b/")).uri("y").build();
        assertEquals(URI.create("http://e.org/b/y"), relativeToUri.getUri());
        assertEquals(URI.create(""), delegate.createLinkBuilder().build().getUri());
        Link.Builder unclosed = delegate.createLinkBuilder().uri("http://e.org/a/{x");
        assertThrows(IllegalArgumentException.class, unclosed::build);
    }

    @Test
    void linkBuilder_buildRelativized_relativises_against_the_base() {
        Link l = delegate.createLinkBuilder().uri("http://e.org/a/b").rel("r").buildRelativized(URI.create("http://e.org/a/"));
        assertEquals(URI.create("b"), l.getUri());
        assertEquals("r", l.getRel());
    }

    @Test
    void a_built_link_offers_a_uri_builder() {
        Link l = delegate.createLinkBuilder().uri(E_ORG_A).build();
        assertEquals(URI.create("http://e.org/a/b"), l.getUriBuilder().path("b").build());
        assertNotEquals(l, delegate.createLinkBuilder().uri(E_ORG_A).rel("x").build());
        assertNotEquals(l, delegate.createLinkBuilder().uri("http://e.org/z").build());
        assertNotEquals(null, l);
    }
}
