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

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.ext.RuntimeDelegate.HeaderDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Date;

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
        assertEquals("example.org", c.getDomain());
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
    void linkBuilder_paramOrderIsPreservedInToString() {
        // LinkedHashMap-backed params: insertion order must survive build().
        Link l = delegate.createLinkBuilder()
                .uri("http://e.org/a").rel("self").title("t").param("z", "1").build();
        assertEquals("<http://e.org/a>;rel=\"self\";title=\"t\";z=\"1\"", l.toString());
    }
}
