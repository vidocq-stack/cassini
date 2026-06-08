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
package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.internal.MatchResult;
import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.UriTemplate;
import io.vidocq.cassini.spi.gen.InjectionSupport;
import io.vidocq.cassini.spi.gen.ParamKind;
import io.vidocq.cassini.spi.gen.ResourceAdapter;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P0 TDD: verifies the AdapterRegistry seam.
 *
 * 1. lookup returns empty by default (P0 stub).
 * 2. A registered fake adapter receives injectFields calls.
 * 3. Deregistering restores the empty state.
 * 4. InjectionSupportImpl.param coercion (basic types).
 * 5. InjectionSupportImpl.context for SecurityContext/UriInfo.
 */
class AdapterRegistrySeamTest {

    static class FakeResource {}

    /** Fake adapter that counts injectFields calls and records the last injectParams value. */
    static class CountingAdapter implements ResourceAdapter {
        final AtomicInteger count = new AtomicInteger();
        boolean lastInjectParams;

        @Override
        public void injectFields(Object target, InjectionSupport support, boolean injectParams) {
            count.incrementAndGet();
            lastInjectParams = injectParams;
        }

        @Override
        public Object invoke(int methodId, Object target, Object[] args) {
            throw new UnsupportedOperationException("P1b: direct invoke not implemented in stub");
        }
    }

    @AfterEach
    void cleanup() {
        AdapterRegistry.deregister(FakeResource.class);
    }

    @Test
    void lookupGeneratesAdapterForKnownClass() {
        // P1a: AdapterRegistry generates an adapter for FakeResource (no injectable fields → no-op adapter)
        var result = AdapterRegistry.lookup(FakeResource.class);
        assertTrue(result.isPresent(),
                "P1a: AdapterRegistry must generate an adapter for a generatable class");
    }

    @Test
    void registeredAdapterTakesPrecedenceOverGenerated() {
        // Explicitly registered adapter wins over the generated one (cache-first lookup)
        var adapter = new CountingAdapter();
        AdapterRegistry.register(FakeResource.class, adapter);

        var result = AdapterRegistry.lookup(FakeResource.class);
        assertTrue(result.isPresent(), "registered adapter should be found");
        assertSame(adapter, result.get(), "explicitly registered adapter must take precedence");
    }

    @Test
    void registeredAdapterReceivesInjectFieldsCall() {
        var adapter = new CountingAdapter();
        AdapterRegistry.register(FakeResource.class, adapter);

        var found = AdapterRegistry.lookup(FakeResource.class);
        assertTrue(found.isPresent());

        // Simulate what the Invoker seam does
        var support = new InjectionSupportImpl(minimalMatch(), minimalExchange());
        found.get().injectFields(new FakeResource(), support, true);

        assertEquals(1, adapter.count.get(), "injectFields should have been called once");
        assertTrue(adapter.lastInjectParams);
    }

    @Test
    void registeredAdapterReceivesInjectParamsFalse() {
        var adapter = new CountingAdapter();
        AdapterRegistry.register(FakeResource.class, adapter);

        var found = AdapterRegistry.lookup(FakeResource.class);
        assertTrue(found.isPresent());

        var support = new InjectionSupportImpl(minimalMatch(), minimalExchange());
        found.get().injectFields(new FakeResource(), support, false);

        assertFalse(adapter.lastInjectParams, "injectParams=false should be forwarded");
    }

    @Test
    void deregisteredAdapterRetriesGeneration() {
        // After deregister, the next lookup will re-generate (or re-load the existing adapter class).
        var adapter = new CountingAdapter();
        AdapterRegistry.register(FakeResource.class, adapter);
        AdapterRegistry.deregister(FakeResource.class);

        // P1a: lookup re-generates (the previously defined class will be reused via Class.forName)
        var result = AdapterRegistry.lookup(FakeResource.class);
        assertTrue(result.isPresent(),
                "after deregister, lookup should re-generate a new adapter");
    }

    @Test
    void injectionSupportImplParamCoercionPath() {
        // A query param named "count" with value "42"
        var exchange = exchangeWithUri("http://localhost/test?count=42");
        var match = minimalMatch();
        var support = new InjectionSupportImpl(match, exchange);

        Object result = support.param(ParamKind.QUERY, "count", false, null, int.class, int.class);
        assertEquals(42, result, "QUERY param 'count' should coerce to int 42");
    }

    @Test
    void injectionSupportImplParamDefaultValue() {
        var exchange = exchangeWithUri("http://localhost/test");
        var match = minimalMatch();
        var support = new InjectionSupportImpl(match, exchange);

        Object result = support.param(ParamKind.QUERY, "missing", false, "99", int.class, int.class);
        assertEquals(99, result, "missing QUERY param should fall back to defaultValue");
    }

    @Test
    void injectionSupportImplPathParam() {
        var match = matchWithPathParams(Map.of("id", List.of("hello")));
        var support = new InjectionSupportImpl(match, minimalExchange());

        Object result = support.param(ParamKind.PATH, "id", false, null, String.class, String.class);
        assertEquals("hello", result);
    }

    @Test
    void injectionSupportImplContextUriInfo() {
        var exchange = exchangeWithUri("http://localhost/ctx/info");
        var match = minimalMatch();
        var support = new InjectionSupportImpl(match, exchange);

        jakarta.ws.rs.core.UriInfo uriInfo = support.context(jakarta.ws.rs.core.UriInfo.class);
        assertNotNull(uriInfo, "@Context UriInfo must not be null");
    }

    @Test
    void injectionSupportImplContextHttpHeaders() {
        var exchange = minimalExchange();
        var match = minimalMatch();
        var support = new InjectionSupportImpl(match, exchange);

        jakarta.ws.rs.core.HttpHeaders headers = support.context(jakarta.ws.rs.core.HttpHeaders.class);
        assertNotNull(headers, "@Context HttpHeaders must not be null");
    }

    // ---- minimal test doubles ----

    private static MatchResult minimalMatch() {
        ResourceMethod rm = new ResourceMethod(
                FakeResource.class, null, "GET",
                UriTemplate.compile("/test"), Set.of(), Set.of());
        return new MatchResult(rm, Map.of(), Map.of());
    }

    private static MatchResult matchWithPathParams(Map<String, List<String>> pathParams) {
        ResourceMethod rm = new ResourceMethod(
                FakeResource.class, null, "GET",
                UriTemplate.compile("/test/{id}"), Set.of(), Set.of());
        return new MatchResult(rm, pathParams, pathParams);
    }

    private static CassiniHttpExchange minimalExchange() {
        return exchangeWithUri("http://localhost/test");
    }

    private static CassiniHttpExchange exchangeWithUri(String uri) {
        return new MinimalExchange(URI.create(uri));
    }

    /** Minimal exchange stub with just enough surface for InjectionSupportImpl. */
    static class MinimalExchange implements CassiniHttpExchange {
        private final URI requestUri;
        private final java.util.Map<String, Object> attrs = new java.util.HashMap<>();

        MinimalExchange(URI requestUri) { this.requestUri = requestUri; }

        @Override public URI requestUri() { return requestUri; }
        @Override public String requestUriRaw() { return requestUri.toString(); }
        @Override public String method() { return "GET"; }
        @Override public Map<String, List<String>> requestHeaders() { return Map.of(); }
        @Override public java.io.InputStream requestBody() { return null; }
        @Override public String contextPath() { return ""; }
        @Override public boolean isSecure() { return false; }
        @Override public Object getAttribute(String key) { return attrs.get(key); }
        @Override public void setAttribute(String key, Object value) { attrs.put(key, value); }
        // Response side — not used by injection tests
        @Override public void setStatus(int code) {}
        @Override public Map<String, List<String>> responseHeaders() { return new java.util.HashMap<>(); }
        @Override public java.io.OutputStream responseBody() { return java.io.OutputStream.nullOutputStream(); }
        @Override public java.net.SocketAddress remoteAddress() { return null; }
        @Override public String authScheme() { return null; }
        @Override public java.security.Principal userPrincipal() { return null; }
        @Override public boolean isUserInRole(String role) { return false; }
    }
}
