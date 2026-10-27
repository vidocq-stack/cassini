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
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Providers;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.sse.Sse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Where each argument and field of a resource comes from (§3.2, §9.2), resolved end to end through
 * the in-memory stack: query, header, cookie, matrix, form and path parameters, defaults and
 * {@code @Encoded}, bean parameters, every {@code @Context} type, and the 404 / 400 of a value that
 * does not convert. The official TCK covers the same ground on a real transport; these tests keep
 * the request path covered by the unit tests of the module.
 */
class RequestParametersTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    public static class Bean {
        @QueryParam("q") String q;
        @HeaderParam("X-H") String h;
        @CookieParam("c") String c;
        @MatrixParam("m") String m;
        @PathParam("id") String id;
        @QueryParam("n") @DefaultValue("3") int n;
        @Encoded @QueryParam("e") String e;
        @Context UriInfo uri;
        @BeanParam Inner inner;

        @Override
        public String toString() {
            return String.join("|", q, h, c, m, id, String.valueOf(n), e, uri.getPath(), inner.q);
        }
    }

    public static class Inner {
        @QueryParam("q") String q;
    }

    /** No no-arg constructor: neither a bean parameter nor a ResourceContext can build it. */
    public static class Unbuildable {
        public Unbuildable(String required) {
            // Required argument only.
        }
    }

    public static class BrokenBean {
        @BeanParam Unbuildable unbuildable;
    }

    public static class FormBean {
        @FormParam("f") String f;
        @Encoded @FormParam("g") String g;
    }

    @Path("/p")
    public static class Params {
        @GET
        @Path("/query")
        public String query(@QueryParam("a") List<String> a, @QueryParam("s") Set<String> s,
                            @QueryParam("o") SortedSet<Integer> o, @QueryParam("n") @DefaultValue("5") int n,
                            @Encoded @QueryParam("e") String e, @QueryParam("absent") String absent) {
            return a + "|" + s + "|" + o + "|" + n + "|" + e + "|" + absent;
        }

        @GET
        @Path("/header")
        public String header(@HeaderParam("X-A") String a, @HeaderParam("X-L") List<String> l,
                             @CookieParam("c") String c, @CookieParam("missing") @DefaultValue("d") String d) {
            return a + "|" + l + "|" + c + "|" + d;
        }

        @GET
        @Path("/matrix")
        public String matrix(@MatrixParam("m") String m, @MatrixParam("flag") String flag,
                             @Encoded @MatrixParam("e") String e, @MatrixParam("none") @DefaultValue("x") String none) {
            return m + "|" + flag + "|" + e + "|" + none;
        }

        @POST
        @Path("/form")
        @Consumes("application/x-www-form-urlencoded")
        public String form(@FormParam("f") String f, @Encoded @FormParam("g") String g,
                           @FormParam("none") @DefaultValue("z") String none, @BeanParam FormBean bean) {
            return f + "|" + g + "|" + none + "|" + bean.f + "|" + bean.g;
        }

        @GET
        @Path("/path/{seg}/{n}")
        public String path(@PathParam("seg") String seg, @Encoded @PathParam("seg") String raw,
                           @PathParam("seg") PathSegment segment, @PathParam("n") long n) {
            return seg + "|" + raw + "|" + segment.getPath() + "|" + n;
        }

        @GET
        @Path("/bean/{id}")
        public String bean(@BeanParam Bean bean) {
            return bean.toString();
        }

        @GET
        @Path("/broken-bean")
        public String brokenBean(@BeanParam BrokenBean bean) {
            return "unreachable";
        }

        @GET
        @Path("/bad-query")
        public String badQuery(@QueryParam("n") int n) {
            return "n=" + n;
        }

        @GET
        @Path("/bad-header")
        public String badHeader(@HeaderParam("X-N") int n) {
            return "n=" + n;
        }

        @GET
        @Path("/bad-path/{n}")
        public String badPath(@PathParam("n") int n) {
            return "n=" + n;
        }

        @GET
        @Path("/ctx")
        public String ctx(@Context UriInfo ui, @Context HttpHeaders h, @Context Request r,
                          @Context SecurityContext sc, @Context Providers p, @Context ResourceInfo ri,
                          @Context Application app, @Context ContainerRequestContext crc,
                          @Context ResourceContext rc, @Context Configuration cfg, @Context Sse sse) {
            return String.join("|", ui.getPath(), h.getHeaderString("X-A"), r.getMethod(),
                    String.valueOf(sc.isSecure()), String.valueOf(p != null), ri.getResourceMethod().getName(),
                    ri.getResourceClass().getSimpleName(), String.valueOf(app != null), crc.getMethod(),
                    String.valueOf(rc.getResource(Bean.class) != null), String.valueOf(rc.getResource(Unbuildable.class)),
                    String.valueOf(rc.initResource(new Inner()).q), describe(cfg), String.valueOf(sse != null));
        }

        @GET
        @Path("/unsupported")
        public String unsupported(@Context String nothing) {
            return nothing;
        }
    }

    /** Every method of the server-side Configuration: nothing registered, nothing enabled. */
    static String describe(Configuration cfg) {
        return cfg.getRuntimeType().name() + cfg.getProperties() + cfg.getProperty("x") + cfg.getPropertyNames()
                + cfg.isEnabled((jakarta.ws.rs.core.Feature) context -> true) + cfg.isEnabled(jakarta.ws.rs.core.Feature.class)
                + cfg.isRegistered(new Object()) + cfg.isRegistered(Object.class) + cfg.getContracts(Object.class)
                + cfg.getClasses() + cfg.getInstances();
    }

    @Path("/fields/{id}")
    public static class Fields {
        @Context UriInfo uri;
        @Context HttpHeaders headers;
        @Context Request request;
        @Context SecurityContext security;
        @Context Providers providers;
        @Context Application application;
        @Context ResourceInfo info;
        @Context ContainerRequestContext requestContext;
        @Context ResourceContext resources;
        @Context Configuration configuration;
        @QueryParam("q") List<String> q;
        @QueryParam("n") @DefaultValue("7") int n;
        @HeaderParam("X-H") String h;
        @CookieParam("c") String c;
        @CookieParam("none") @DefaultValue("dc") String noCookie;
        @MatrixParam("m") String m;
        @PathParam("id") String id;
        @Encoded @PathParam("id") String rawId;
        @BeanParam Bean bean;

        @GET
        public String get() {
            return String.join("|", uri.getPath(), headers.getHeaderString("X-H"), request.getMethod(),
                    String.valueOf(security != null), String.valueOf(providers != null),
                    String.valueOf(application != null), info.getResourceClass().getSimpleName(),
                    requestContext.getMethod(), String.valueOf(resources.getResource(Inner.class) != null),
                    String.valueOf(resources.getResource(Unbuildable.class)), String.valueOf(resources.initResource(new Inner()).q),
                    describe(configuration), String.valueOf(q), String.valueOf(n), h, c, noCookie,
                    m, id, rawId, bean.q, info.getResourceMethod().getName());
        }
    }

    @Path("/bad-fields")
    public static class BadFields {
        @QueryParam("n") int n;

        @GET
        public String get() {
            return "n=" + n;
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Params.class, Fields.class, BadFields.class);
        }
    }).build();

    private static InMemoryExchange send(InMemoryExchange exchange) {
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    private static final String EMPTY_CONFIGURATION = "SERVER{}null[]falsefalsefalsefalse{}[][]";

    private static InMemoryExchange get(String path) {
        return InMemoryExchange.request("GET", path, null, null);
    }

    @Test
    void queryParametersFillListsSetsAndDefaults() {
        var exchange = send(get("/p/query?a=1&a=2&s=x&s=x&o=3&o=1&e=a%20b"));
        assertEquals(200, exchange.status());
        assertEquals("[1, 2]|[x]|[1, 3]|5|a%20b|null", exchange.responseText());
    }

    @Test
    void headersAndCookiesAreReadWithTheirDefaults() {
        var exchange = send(get("/p/header").header("X-A", "va").header("X-L", "l1").header("X-L", "l2")
                .header("Cookie", "other=1; c=\"quoted\""));
        assertEquals("va|[l1, l2]|quoted|d", exchange.responseText());
    }

    @Test
    void matrixParametersAreReadFromThePathSegments() {
        var exchange = send(get("/p/matrix;m=v;flag;e=a%20b"));
        assertEquals(200, exchange.status());
        assertEquals("v||a%20b|x", exchange.responseText());
    }

    @Test
    void formParametersAreReadOnceForParametersAndBeanFields() {
        var exchange = send(InMemoryExchange.request("POST", "/p/form",
                "application/x-www-form-urlencoded", "f=a+b&g=c%20d"));
        assertEquals(200, exchange.status());
        assertEquals("a b|c%20d|z|a b|c%20d", exchange.responseText());
    }

    @Test
    void pathParametersAreDecodedUnlessEncoded() {
        var exchange = send(get("/p/path/a%20b/42"));
        assertEquals("a b|a%20b|a b|42", exchange.responseText());
    }

    @Test
    void aBeanParameterGetsItsFieldsInjected() {
        var exchange = send(get("/p/bean/7;m=mv?q=qv&e=x%20y").header("X-H", "hv").header("Cookie", "c=cv"));
        assertEquals(200, exchange.status());
        assertEquals("qv|hv|cv|mv|7|3|x%20y|p/bean/7;m=mv|qv", exchange.responseText());
        assertEquals(500, send(get("/p/broken-bean")).status());
    }

    @Test
    void aValueThatDoesNotConvertIsA404ForAQueryOrPathParameterAndA400ForAHeader() {
        assertEquals(404, send(get("/p/bad-query?n=abc")).status());
        assertEquals(404, send(get("/p/bad-path/abc")).status());
        assertEquals(400, send(get("/p/bad-header").header("X-N", "abc")).status());
        assertEquals(404, send(get("/bad-fields?n=abc")).status());
    }

    @Test
    void everyContextTypeIsInjectedAsAParameter() {
        var exchange = send(get("/p/ctx").header("X-A", "va"));
        assertEquals(200, exchange.status());
        assertEquals("p/ctx|va|GET|false|true|ctx|Params|true|GET|true|null|null|" + EMPTY_CONFIGURATION + "|true",
                exchange.responseText());
    }

    @Test
    void anUnsupportedContextTypeIsAServerError() {
        assertEquals(500, send(get("/p/unsupported")).status());
    }

    @Test
    void theFieldsOfAResourceAreInjected() {
        var exchange = send(get("/fields/a%20b;m=mv?q=1&q=2").header("X-H", "hv").header("Cookie", "c=cv"));
        assertEquals(200, exchange.status());
        assertEquals("fields/a b;m=mv|hv|GET|true|true|true|Fields|GET|true|null|1|" + EMPTY_CONFIGURATION
                + "|[1, 2]|7|hv|cv|dc|mv|a b|a%20b|1|get", exchange.responseText());
    }
}
