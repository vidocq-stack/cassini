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
package io.vidocq.cassini.internal;

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code ContainerRequestContext.setRequestUri(URI, URI)} contract and Jakarta REST 4.0 §6.2: a pre-matching filter
 * that sets a new base URI moves the application under that base. The request is matched on its path relative to the
 * new base, and the {@code UriInfo} of the resource and of the filters, and the response builder, resolve against it
 * (CASSINI-059). With {@code setRequestUri(URI)}, the base stays the transport's.
 */
class RequestFilterBaseUriTest {

    private static final String TEXT = MediaType.TEXT_PLAIN;
    private static final String BASE = "X-Base";
    private static final String TARGET = "X-Target";
    private static final String SEEN_BASE = "X-Seen-Base";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---------------------------------------------------------------- fixtures

    /** Sets the base and request URIs its headers give, as a filter behind a reverse proxy would. */
    @Provider
    @PreMatching
    public static final class RebaseFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            String target = request.getHeaderString(TARGET);
            if (target == null) return;
            String base = request.getHeaderString(BASE);
            if (base == null) request.setRequestUri(URI.create(target));
            else request.setRequestUri(URI.create(base), URI.create(target));
        }
    }

    /** What a response filter's request context gives as the base URI. */
    @Provider
    public static final class SeenBaseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle(SEEN_BASE, request.getUriInfo().getBaseUri().toString());
        }
    }

    @Path("/where")
    public static class Where {
        @GET
        @Path("sub")
        @Produces(TEXT)
        public String get(@Context UriInfo uri) {
            return "base=" + uri.getBaseUri() + " path=" + uri.getPath() + " absolute=" + uri.getAbsolutePath()
                    + " resolved=" + uri.resolve(URI.create("other"));
        }

        @POST
        @Path("sub")
        public Response create() {
            return Response.created(URI.create("where/created")).build();
        }
    }

    @Path("/object")
    public static class ObjectRoot {
        @Path("where")
        public Object where() {
            return new Where();
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Where.class, ObjectRoot.class, RebaseFilter.class, SeenBaseFilter.class);
        }
    }).build();

    private static InMemoryExchange send(InMemoryExchange exchange, String base, String target) {
        if (base != null) exchange.header(BASE, base);
        if (target != null) exchange.header(TARGET, target);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource(delimiter = '|', value = {
            // Under a prefix the transport does not know: matched on the path relative to the new base.
            "/api/where/sub | http://public.example/api/ | http://public.example/api/where/sub"
                    + " | base=http://public.example/api/ path=where/sub absolute=http://public.example/api/where/sub"
                    + " resolved=http://public.example/api/other",
            // A base without its trailing slash, on another authority than the request URI.
            "/api/where/sub | http://localhost:888/api | http://xx.yy:888/api/where/sub"
                    + " | base=http://localhost:888/api/ path=where/sub absolute=http://xx.yy:888/api/where/sub"
                    + " resolved=http://localhost:888/api/other",
            // A base with no path at all.
            "/where/sub | http://public.example | http://public.example/where/sub"
                    + " | base=http://public.example/ path=where/sub absolute=http://public.example/where/sub"
                    + " resolved=http://public.example/other",
            // A relative base: the scheme and the authority of the request complete it.
            "/where/sub | /api/ | /api/where/sub"
                    + " | base=http://127.0.0.1/api/ path=where/sub absolute=http://127.0.0.1/api/where/sub"
                    + " resolved=http://127.0.0.1/api/other",
            // The sub-resource of a locator that returns Object.
            "/api/object/where/sub | http://public.example/api/ | http://public.example/api/object/where/sub"
                    + " | base=http://public.example/api/ path=object/where/sub"
                    + " absolute=http://public.example/api/object/where/sub resolved=http://public.example/api/other",
            // The one-argument form keeps the base of the application.
            "/where/sub | | http://127.0.0.1/where/sub"
                    + " | base=http://127.0.0.1/ path=where/sub absolute=http://127.0.0.1/where/sub"
                    + " resolved=http://127.0.0.1/other"})
    void theResourceIsMatchedAndResolvedAgainstTheBaseAFilterSets(String path, String base, String target,
                                                                   String expected) {
        InMemoryExchange ex = send(InMemoryExchange.get(path), base, target);
        assertEquals(200, ex.status(), ex::responseText);
        assertEquals(expected, ex.responseText());
        String seen = expected.substring("base=".length(), expected.indexOf(' '));
        assertEquals(seen, ex.responseHeader(SEEN_BASE), "the response filter's request context");
    }

    /** A new base replaces the context path of the transport, too. */
    @Test
    void aNewBaseReplacesTheContextPathOfTheTransport() {
        InMemoryExchange ex = send(InMemoryExchange.get("/ctx/where/sub").contextPath("/ctx"),
                "http://public.example/", "http://public.example/where/sub");
        assertEquals(200, ex.status(), ex::responseText);
        assertEquals("base=http://public.example/ path=where/sub absolute=http://public.example/where/sub"
                + " resolved=http://public.example/other", ex.responseText());
    }

    /** The response builder resolves a relative {@code Location} against the new base (§3.5 of the API). */
    @Test
    void aRelativeLocationIsResolvedAgainstTheNewBase() {
        InMemoryExchange ex = send(InMemoryExchange.post("/api/where/sub", TEXT, ""),
                "http://public.example/api/", "http://public.example/api/where/sub");
        assertEquals(201, ex.status(), ex::responseText);
        assertEquals("http://public.example/api/where/created", ex.responseHeader("Location"));
    }
}
