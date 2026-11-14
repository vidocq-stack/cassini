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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Jakarta REST 4.0 §9.2, §6.7.1: the {@code @Context} fields of a singleton response filter describe the request it
 * filters, matched or not. A response that comes before any resource method is matched (a pre-matching abort, a
 * request the matching rejects, a failing sub-resource locator) gave the filter the fields of the previous request,
 * since they were injected for a matched request only (CASSINI-054). Without a matched method, the
 * {@link ResourceInfo} gives no method and no class.
 */
class UnmatchedProviderContextTest {

    private static final String TEXT = MediaType.TEXT_PLAIN;
    private static final String WHO = "X-Who";
    private static final String SEEN_WHO = "X-Seen-Who";
    private static final String SEEN_METHOD = "X-Seen-Method";
    private static final String SEEN_PATH = "X-Seen-Path";
    private static final String ABORT = "X-Abort";
    private static final String NONE = "none";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---------------------------------------------------------------- fixtures

    /** Echoes what its injected contexts say about the request it filters. */
    @Provider
    public static final class EchoingResponseFilter implements ContainerResponseFilter {
        @Context
        HttpHeaders headers;
        @Context
        UriInfo uriInfo;
        @Context
        ResourceInfo resourceInfo;

        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().add(SEEN_WHO, String.valueOf(headers.getHeaderString(WHO)));
            response.getHeaders().add(SEEN_PATH, uriInfo.getPath());
            response.getHeaders().add(SEEN_METHOD, resourceInfo.getResourceMethod() == null
                    ? NONE : resourceInfo.getResourceMethod().getName());
        }
    }

    @Provider
    @PreMatching
    public static final class AbortingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            if (request.getHeaderString(ABORT) != null) request.abortWith(Response.status(403).build());
        }
    }

    @Path("/echo")
    public static class EchoResource {
        @GET
        @Produces(TEXT)
        public String get() {
            return "echo";
        }

        @POST
        @Consumes(TEXT)
        @Produces(TEXT)
        public String post(String body) {
            return body;
        }
    }

    @Path("/locator")
    public static class LocatorRoot {
        @Path("failing")
        public EchoResource failing() {
            throw new NotFoundException();
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(EchoResource.class, LocatorRoot.class, EchoingResponseFilter.class, AbortingFilter.class);
        }
    }).build();

    private static InMemoryExchange send(InMemoryExchange exchange) {
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0} {1} -> {3}")
    @CsvSource({
            "GET,    /missing,        ,         404",
            "DELETE, /echo,           ,         405",
            "POST,   /echo,           " + MediaType.APPLICATION_JSON + ", 415",
            "GET,    /locator/failing,,         404",
            "GET,    /echo,           abort,    403"})
    void aResponseFilterSeesTheRequestItFiltersWhenNoMethodIsMatched(String method, String path, String extra,
                                                                      int status) {
        InMemoryExchange matched = send(InMemoryExchange.get("/echo").header(WHO, "first"));
        assertEquals("first", matched.responseHeader(SEEN_WHO));
        assertEquals("get", matched.responseHeader(SEEN_METHOD));

        String contentType = extra != null && extra.contains("/") ? extra : null;
        InMemoryExchange unmatched = InMemoryExchange.request(method, path, contentType,
                contentType == null ? null : "{}").header(WHO, "second");
        if ("abort".equals(extra)) unmatched.header(ABORT, "yes");
        send(unmatched);

        assertEquals(status, unmatched.status(), unmatched::responseText);
        assertEquals("second", unmatched.responseHeader(SEEN_WHO), "the headers of this request, not the last one's");
        assertEquals(path.substring(1), unmatched.responseHeader(SEEN_PATH));
        assertEquals(NONE, unmatched.responseHeader(SEEN_METHOD), "no resource method is matched (§6.7.1)");
    }
}
