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
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Jakarta REST 4.0 §6.2, §6.5.1 and the {@code ContainerRequestContext} contract: what a request filter changes (a
 * header, the entity stream, the security context) is what the resource method gets. A pre-matching filter's header
 * and entity changes never reached it; a post-matching filter could not set the entity stream or the security
 * context, and its header changes came after the arguments were resolved (CASSINI-056).
 */
class RequestFilterChangesTest {

    private static final String TEXT = MediaType.TEXT_PLAIN;
    private static final String WHO = "X-Who";
    private static final String PRE = "X-Pre";
    private static final String POST = "X-Post";
    private static final String HEADER = "header";
    private static final String ENTITY = "entity";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---------------------------------------------------------------- fixtures

    private static InputStream text(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Makes the change its header names, as {@code who} would. */
    private static void change(ContainerRequestContext request, String what, String who) throws IOException {
        switch (what == null ? "" : what) {
            case HEADER -> request.getHeaders().putSingle(WHO, who);
            case ENTITY -> request.setEntityStream(text("from " + who));
            case "security" -> request.setSecurityContext(new SecurityContext() {
                @Override public Principal getUserPrincipal() { return () -> who + "-user"; }
                @Override public boolean isUserInRole(String role) { return false; }
                @Override public boolean isSecure() { return false; }
                @Override public String getAuthenticationScheme() { return "TEST"; }
            });
            // A logging filter: reads the entity, then hands the resource a stream of what it read.
            case "read" -> request.setEntityStream(text(new String(request.getEntityStream().readAllBytes(),
                    StandardCharsets.UTF_8)));
            // Pre-matching only (§6.6.1).
            case "uri" -> request.setRequestUri(request.getUriInfo().getRequestUriBuilder().replaceQuery("q=moved").build());
            case "method" -> request.setMethod("PUT");
            default -> {
                // No change asked for: the request goes on as the client sent it.
            }
        }
    }

    @Provider
    @PreMatching
    public static final class PreMatchingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) throws IOException {
            change(request, request.getHeaderString(PRE), "pre");
        }
    }

    @Provider
    public static final class PostMatchingFilter implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) throws IOException {
            change(request, request.getHeaderString(POST), "post");
        }
    }

    @Path("/changed")
    public static class Changed {
        @HeaderParam(WHO)
        String whoField;

        @GET
        @Produces(TEXT)
        public String get(@HeaderParam(WHO) String who, @Context HttpHeaders headers, @Context SecurityContext security) {
            Principal user = security.getUserPrincipal();
            return "who=" + who + " field=" + whoField + " headers=" + headers.getHeaderString(WHO)
                    + " user=" + (user == null ? null : user.getName());
        }

        @POST
        @Consumes(TEXT)
        @Produces(TEXT)
        public String post(String body) {
            return "body=" + body;
        }

        @GET
        @Path("where")
        @Produces(TEXT)
        public String where(@QueryParam("q") String q, @Context UriInfo uri, @Context Request request) {
            return describe(q, uri, request);
        }

        @PUT
        @Path("where")
        @Produces(TEXT)
        public String whereByPut(@QueryParam("q") String q, @Context UriInfo uri, @Context Request request) {
            return describe(q, uri, request);
        }

        private static String describe(String q, UriInfo uri, Request request) {
            return "method=" + request.getMethod() + " q=" + q + " uri-q=" + uri.getQueryParameters().getFirst("q");
        }
    }

    @Path("/object")
    public static class ObjectRoot {
        @Path("changed")
        public Object changed() {
            return new Changed();
        }
    }

    /** A locator that reads the entity, as a form, before the post-matching filters run. */
    @Path("/typed")
    public static class TypedRoot {
        @Path("changed")
        public Changed changed(@FormParam("a") String a) {
            return new Changed();
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Changed.class, ObjectRoot.class, TypedRoot.class, PreMatchingFilter.class,
                    PostMatchingFilter.class);
        }
    }).build();

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0} {1} {2}={3}")
    @CsvSource({
            "GET,  /changed,        , ,                  who=client field=client headers=client user=null",
            "GET,  /changed,        X-Pre,  header,      who=pre field=pre headers=pre user=null",
            "GET,  /changed,        X-Post, header,      who=post field=post headers=post user=null",
            "GET,  /changed,        X-Post, security,    who=client field=client headers=client user=post-user",
            "POST, /changed,        X-Pre,  entity,      body=from pre",
            "POST, /changed,        X-Post, entity,      body=from post",
            "POST, /changed,        X-Post, read,        body=from client",
            "GET,  /object/changed, X-Pre,  header,      who=pre field=null headers=pre user=null",
            "GET,  /object/changed, X-Post, header,      who=post field=null headers=post user=null",
            "POST, /object/changed, X-Post, entity,      body=from post",
            "POST, /typed/changed,  X-Post, entity,      body=from post",
            "POST, /typed/changed,  ,       ,            body=from client",
            "GET,  /changed/where?q=sent,        X-Pre, uri,    method=GET q=moved uri-q=moved",
            "POST, /changed/where?q=sent,        X-Pre, method, method=PUT q=sent uri-q=sent",
            "GET,  /object/changed/where?q=sent, X-Pre, uri,    method=GET q=moved uri-q=moved",
            "POST, /object/changed/where?q=sent, X-Pre, method, method=PUT q=sent uri-q=sent"})
    void theResourceMethodGetsWhatTheRequestFiltersChanged(String method, String path, String header, String what,
                                                          String expected) {
        InMemoryExchange exchange = "POST".equals(method)
                ? InMemoryExchange.post(path, TEXT, "from client")
                : InMemoryExchange.get(path);
        exchange.header(WHO, "client");
        if (header != null) exchange.header(header, what);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals(expected, exchange.responseText());
    }
}
