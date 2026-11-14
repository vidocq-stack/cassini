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
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Jakarta REST 4.0 §3.7.2, §4.4, §6.7.1: a request the matching rejects ({@code 404}, {@code 405}, {@code 415},
 * {@code 406}) gets the response of a {@code WebApplicationException}, processed as a resource method's response is:
 * through the response filters. No resource method is matched, so only the global ones run (CASSINI-053). Covered
 * at the root router, in the negotiation of a matched route, and in the router of a class that a locator returns as
 * {@code Object}.
 */
class UnmatchedResponseFilterTest {

    private static final String TEXT = MediaType.TEXT_PLAIN;
    private static final String JSON = MediaType.APPLICATION_JSON;
    private static final String GLOBAL = "X-Global";
    private static final String BOUND = "X-Bound";
    private static final String YES = "yes";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---------------------------------------------------------------- fixtures

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Bound { }

    @Provider
    public static final class GlobalResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().add(GLOBAL, YES);
        }
    }

    @Provider
    @Bound
    public static final class BoundResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().add(BOUND, YES);
        }
    }

    @Path("/plain")
    public static class PlainResource {
        @GET
        @Bound
        @Produces(TEXT)
        public String get() {
            return "plain";
        }

        @POST
        @Bound
        @Consumes(TEXT)
        @Produces(TEXT)
        public String post(String body) {
            return body;
        }
    }

    public static class Sub {
        @GET
        @Bound
        @Produces(TEXT)
        public String get() {
            return "sub";
        }
    }

    @Path("/object")
    public static class ObjectRoot {
        @Path("sub")
        public Object sub() {
            return new Sub();
        }
    }

    @Path("/typed")
    public static class TypedRoot {
        @Path("sub")
        public Sub sub() {
            return new Sub();
        }
    }

    @Provider
    public static final class NotFoundMapper implements ExceptionMapper<NotFoundException> {
        @Override
        public Response toResponse(NotFoundException e) {
            return Response.status(404).type(MediaType.TEXT_PLAIN_TYPE).entity("no such resource").build();
        }
    }

    private static CassiniStack stack(Class<?>... extra) {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                Set<Class<?>> classes = new java.util.HashSet<>(Set.of(PlainResource.class, ObjectRoot.class,
                        TypedRoot.class, GlobalResponseFilter.class, BoundResponseFilter.class));
                classes.addAll(List.of(extra));
                return classes;
            }
        }).build();
    }

    private static final CassiniStack STACK = stack();
    private static final CassiniStack MAPPED = stack(NotFoundMapper.class);

    private static InMemoryExchange send(CassiniStack stack, InMemoryExchange exchange) {
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    private static InMemoryExchange send(String method, String path, String contentType, String accept) {
        InMemoryExchange exchange = InMemoryExchange.request(method, path, contentType, contentType == null ? null : "{}");
        if (accept != null) exchange.header("Accept", accept);
        return send(STACK, exchange);
    }

    /** All the values of a response header. */
    private static List<String> headerValues(InMemoryExchange ex, String name) {
        return ex.responseHeaders().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream())
                .toList();
    }

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0} {1} -> {4}")
    @CsvSource({
            "GET,    /missing,            ,      , 404",
            "DELETE, /plain,              ,      , 405",
            "POST,   /plain,              " + JSON + ", , 415",
            "GET,    /plain,              ,      " + JSON + ", 406",
            "GET,    /object/sub/missing, ,      , 404",
            "DELETE, /object/sub,         ,      , 405",
            "GET,    /object/sub,         ,      " + JSON + ", 406"})
    void theGlobalResponseFiltersRunOnARequestTheMatchingRejects(String method, String path, String contentType,
                                                                  String accept, int status) {
        InMemoryExchange ex = send(method, path, contentType, accept);
        assertEquals(status, ex.status(), ex::responseText);
        assertEquals(List.of(YES), headerValues(ex, GLOBAL), "the global response filter, once");
        assertNull(ex.responseHeader(BOUND), "no resource method is matched: no bound filter (§6.7.1)");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/plain", "/object/sub"})
    void aRejectedMethodKeepsItsAllowHeader(String path) {
        InMemoryExchange ex = send("DELETE", path, null, null);
        assertEquals(405, ex.status());
        assertNotNull(ex.responseHeader("Allow"));
    }

    /**
     * §3.3.5, §6.7.1: the automatic answer to an {@code OPTIONS} request is no resource method, so the global response
     * filters process it, and the bound ones do not; it keeps its {@code Allow} header (CASSINI-058).
     */
    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(delimiter = '|', value = {
            "/plain      | GET POST HEAD OPTIONS",
            "/typed/sub  | GET HEAD OPTIONS",
            // The class a locator returns as Object gets the automatic answer too (CASSINI-060).
            "/object/sub | GET HEAD OPTIONS"})
    void theGlobalResponseFiltersRunOnTheAutomaticOptionsAnswer(String path, String methods) {
        InMemoryExchange ex = send("OPTIONS", path, null, null);
        assertEquals(200, ex.status(), ex::responseText);
        String allow = ex.responseHeader("Allow");
        assertNotNull(allow, "the automatic answer names the methods of the resource");
        assertEquals(Set.of(methods.split(" ")), Set.of(allow.split(",\\s*")), allow);
        assertEquals(List.of(YES), headerValues(ex, GLOBAL), "the global response filter, once");
        assertNull(ex.responseHeader(BOUND), "no resource method is matched: no bound filter (§6.7.1)");
    }

    /** §4.4: a mapped response goes through the response filters too. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/missing", "/object/sub/missing"})
    void theGlobalResponseFiltersRunOnAMapped404(String path) {
        InMemoryExchange ex = send(MAPPED, InMemoryExchange.get(path));
        assertEquals(404, ex.status());
        assertEquals("no such resource", ex.responseText());
        assertEquals(List.of(YES), headerValues(ex, GLOBAL));
    }

    /** A matched request is filtered as before: the global and the bound filters, once each. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"/plain", "/object/sub"})
    void aMatchedRequestRunsTheGlobalAndTheBoundFilters(String path) {
        InMemoryExchange ex = send("GET", path, null, null);
        assertEquals(200, ex.status());
        assertEquals(List.of(YES), headerValues(ex, GLOBAL));
        assertEquals(List.of(YES), headerValues(ex, BOUND));
    }
}
