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

import io.vidocq.cassini.internal.InMemoryExchange;
import io.vidocq.cassini.internal.context.CassiniUriInfo;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §6.5 / §6.6: the {@code ContainerRequestContext} given to request filters. */
class CassiniRequestContextTest {

    private static final String TEXT_UTF8 = "text/plain; charset=UTF-8";
    private static final String X_LIST = "X-List";
    private static final String X_ADDED = "X-Added";
    private static final String LOCAL = "http://127.0.0.1";
    private static final String THINGS_7 = "things/7";
    private static final String THINGS = "things";
    private static final String V2_BASE = "http://example.org/v2/";
    private static final String V2_ITEM = "http://example.org/v2/items/9";

    private static final String PATH = "/api/things/7";
    private static final String TEXT = "text/plain";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static CassiniRequestContext context(InMemoryExchange exchange) {
        return CassiniRequestContext.create(exchange,
                new CassiniUriInfo(exchange, "/api", Map.of("id", List.of("7")), "/things/{id}"));
    }

    private static InMemoryExchange get() {
        return InMemoryExchange.request("GET", PATH + "?q=1", null, null);
    }

    @Test
    void propertiesAreKeptPerRequest() {
        var context = context(get());
        assertNull(context.getProperty("a"));
        assertTrue(context.getPropertyNames().isEmpty());
        context.removeProperty("a");
        context.setProperty("a", 1);
        context.setProperty("b", 2);
        assertEquals(1, context.getProperty("a"));
        assertEquals(Set.of("a", "b"), Set.copyOf(context.getPropertyNames()));
        context.removeProperty("a");
        assertEquals(Set.of("b"), Set.copyOf(context.getPropertyNames()));
    }

    @Test
    void headersAreAMutableCopyReadCaseInsensitivelyByContainsHeaderString() {
        var exchange = InMemoryExchange.request("POST", PATH, TEXT_UTF8, "body")
                .header("Accept", "application/json, text/plain;q=0.5")
                .header("Accept-Language", "fr-FR")
                .header("Cookie", "session=abc")
                .header(X_LIST, "a; b")
                .header(X_LIST, "c");
        var context = context(exchange);

        assertEquals("a; b,c", context.getHeaderString(X_LIST));
        assertNull(context.getHeaderString("X-Absent"));
        assertTrue(context.containsHeaderString("x-list", ";", "b"::equals));
        assertTrue(context.containsHeaderString(X_LIST, "c"::equals));
        assertFalse(context.containsHeaderString(X_LIST, "d"::equals));
        assertFalse(context.containsHeaderString("X-Absent", "a"::equals));
        context.getHeaders().putSingle(X_ADDED, "yes");
        assertEquals("yes", context.getHeaderString(X_ADDED));
        assertFalse(exchange.requestHeaders().containsKey(X_ADDED), "the exchange is left untouched");

        assertEquals(MediaType.valueOf(TEXT_UTF8), context.getMediaType());
        assertEquals(MediaType.valueOf(TEXT_UTF8), context.parsedContentType());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, context.getAcceptableMediaTypes().get(0));
        assertEquals(Locale.forLanguageTag("fr-FR"), context.getAcceptableLanguages().get(0));
        assertEquals("abc", context.getCookies().get("session").getValue());
        assertEquals(4, context.getLength());
        assertNull(context.getDate());
        assertNull(context.getLanguage());
        assertTrue(context.hasEntity());
        assertFalse(context(get()).hasEntity());
    }

    @Test
    void preMatchingFiltersMayChangeTheMethodUriEntityAndSecurity() throws IOException {
        var exchange = get();
        var context = context(exchange);
        assertEquals("GET", context.getMethod());
        assertEquals("GET", context.getRequest().getMethod());
        assertEquals(URI.create(LOCAL + PATH + "?q=1"), context.currentRequestUri());

        context.setMethod("PUT");
        context.setRequestUri(URI.create("http://127.0.0.1/api/other"));
        var body = new ByteArrayInputStream(new byte[]{1});
        context.setEntityStream(body);
        SecurityContext security = securityContext("ada");
        context.setSecurityContext(security);

        assertEquals("PUT", context.getMethod());
        assertEquals("PUT", context.currentMethod());
        assertEquals(URI.create("http://127.0.0.1/api/other"), context.currentRequestUri());
        assertSame(body, context.getEntityStream());
        assertSame(body, context.currentEntityStream());
        assertSame(security, context.getSecurityContext());
        assertSame(security, exchange.getAttribute(CassiniRequestContext.ATTR_SECURITY_CONTEXT));
        assertSame(security, context(exchange).getSecurityContext(), "a later context sees the filter's choice");
        assertSame(exchange, context.exchange());
    }

    @Test
    void postMatchingFiltersMayNotChangeTheRequest() {
        var context = context(get());
        context.markPostMatching();
        var uri = URI.create("http://127.0.0.1/x");
        var stream = new ByteArrayInputStream(new byte[0]);
        var security = securityContext("bob");
        assertThrows(IllegalStateException.class, () -> context.setMethod("POST"));
        assertThrows(IllegalStateException.class, () -> context.setRequestUri(uri));
        assertThrows(IllegalStateException.class, () -> context.setRequestUri(uri, uri));
        assertThrows(IllegalStateException.class, () -> context.setEntityStream(stream));
        assertThrows(IllegalStateException.class, () -> context.setSecurityContext(security));
    }

    @Test
    void abortWithIsAllowedInFiltersOnly() {
        var context = context(get());
        assertFalse(context.isAborted());
        Response teapot = Response.status(418).build();
        context.abortWith(teapot);
        assertTrue(context.isAborted());
        assertSame(teapot, context.abortedResponse());

        var duringResponse = context(get());
        duringResponse.runDuringResponsePhase(() ->
                assertThrows(IllegalStateException.class, () -> duringResponse.abortWith(teapot)));
        duringResponse.abortWith(teapot);
        assertTrue(duringResponse.isAborted(), "the response phase is over");

        var inResource = context(get());
        inResource.markPostResource();
        assertThrows(IllegalStateException.class, () -> inResource.abortWith(teapot));
    }

    @Test
    void theUriInfoDelegatesUntilTheRequestUriIsChanged() {
        var context = context(get());
        UriInfo info = context.getUriInfo();
        assertEquals(URI.create("http://127.0.0.1/api/"), info.getBaseUri());
        assertEquals(URI.create(LOCAL + PATH + "?q=1"), info.getRequestUri());
        assertEquals(THINGS_7, info.getPath());
        assertEquals(THINGS_7, info.getPath(false));
        assertEquals(List.of(THINGS, "7"), info.getPathSegments().stream().map(jakarta.ws.rs.core.PathSegment::getPath).toList());
        assertEquals(2, info.getPathSegments(false).size());
        assertEquals("7", info.getPathParameters().getFirst("id"));
        assertEquals("7", info.getPathParameters(false).getFirst("id"));
        assertEquals("1", info.getQueryParameters().getFirst("q"));
        assertEquals("1", info.getQueryParameters(false).getFirst("q"));
        assertEquals(List.of(THINGS_7, THINGS), info.getMatchedURIs());
        assertEquals(List.of(THINGS_7, THINGS), info.getMatchedURIs(false));
        assertEquals(List.of(), info.getMatchedResources());
        assertEquals(URI.create("http://127.0.0.1/api/x"), info.resolve(URI.create("x")));
        assertEquals(URI.create("http://other.org/x"), info.relativize(URI.create("http://other.org/x")));
        assertEquals(URI.create("http://127.0.0.1/api/"), info.getBaseUriBuilder().build());
        assertEquals(URI.create(LOCAL + PATH + "?q=1"), info.getRequestUriBuilder().build());
    }

    @Test
    void theUriInfoReflectsANewBaseAndRequestUri() {
        var context = context(get());
        context.setRequestUri(URI.create(V2_BASE), URI.create(V2_ITEM));
        UriInfo info = context.getUriInfo();
        assertEquals(URI.create(V2_BASE), info.getBaseUri());
        assertEquals(URI.create(V2_ITEM), info.getRequestUri());
        assertEquals("items/9", info.getPath());
        assertEquals(URI.create(V2_ITEM), info.getAbsolutePathBuilder().build());

        context.setRequestUri(URI.create(V2_BASE), URI.create("http://elsewhere.org/items"));
        assertEquals("/items", context.getUriInfo().getPath(), "a request outside the base keeps its path");
    }

    /** CASSINI-031: the absolute path has no query, and the matched template is the request's. */
    @Test
    void theChangedUriInfoKeepsTheAbsolutePathAndTheMatchedTemplate() {
        var context = context(get());
        context.setRequestUri(URI.create(V2_BASE), URI.create("http://example.org/v2/items/9?x=1"));
        UriInfo info = context.getUriInfo();
        assertEquals(URI.create(V2_ITEM), info.getAbsolutePath());
        assertEquals("/things/{id}", info.getMatchedResourceTemplate());
    }

    private static SecurityContext securityContext(String user) {
        return new SecurityContext() {
            @Override public Principal getUserPrincipal() { return () -> user; }
            @Override public boolean isUserInRole(String role) { return false; }
            @Override public boolean isSecure() { return false; }
            @Override public String getAuthenticationScheme() { return BASIC_AUTH; }
        };
    }
}
