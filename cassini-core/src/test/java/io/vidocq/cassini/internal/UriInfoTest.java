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

import io.vidocq.cassini.internal.context.CassiniUriInfo;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §9.2.1 / §10.2: the {@link UriInfo} of a request. */
class UriInfoTest {

    private static final String PLUS_DECODED = "a+b c";
    private static final String PLUS_PATH = "/a+b%20c";
    private static final String PLAIN = "plain";

    private static final String API = "/api";
    private static final String ITEM = "/r/{id}";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static CassiniUriInfo info(String path, String contextPath) {
        return new CassiniUriInfo(InMemoryExchange.get(path), contextPath, Map.of());
    }

    // ---- paths ----

    @Test
    void thePathIsRelativeToTheContextPathAndDecodedOnDemand() {
        CassiniUriInfo info = info("/api/a%20b/c", API);
        assertEquals("a b/c", info.getPath());
        assertEquals("a%20b/c", info.getPath(false));
        assertEquals("things", info("/things", "/").getPath());
        assertEquals("other/x", info("/other/x", API).getPath(), "a path outside the context path is kept");
        assertEquals("", info("/api", API).getPath());
    }

    @Test
    void pathSegmentsSkipEmptySegments() {
        List<PathSegment> segments = info("/api/a//b%20c/", API).getPathSegments();
        assertEquals(List.of("a", "b c"), segments.stream().map(PathSegment::getPath).toList());
        assertEquals(List.of("a", "b%20c"),
                info("/api/a/b%20c", API).getPathSegments(false).stream().map(PathSegment::getPath).toList());
        assertTrue(segments.get(0).getMatrixParameters().isEmpty());
    }

    /** CASSINI-029: '+' is not an escaped octet in a path, it stays a '+' once decoded. */
    @Test
    void aPlusSignInThePathIsKeptByTheDecoding() {
        assertEquals(PLUS_DECODED, info(PLUS_PATH, "").getPath());
        assertEquals(List.of(PLUS_DECODED), info(PLUS_PATH, "").getPathSegments().stream().map(PathSegment::getPath).toList());
        assertEquals(List.of(PLUS_DECODED), info(PLUS_PATH, "").getMatchedURIs());
    }

    /** CASSINI-029: a path segment carries its matrix parameters apart from its path. */
    @Test
    void pathSegmentsSeparateTheirMatrixParameters() {
        List<PathSegment> segments = info("/cars;color=red%20dark;year=2020;year=2021;flag/x", "").getPathSegments();
        assertEquals(List.of("cars", "x"), segments.stream().map(PathSegment::getPath).toList());
        MultivaluedMap<String, String> matrix = segments.get(0).getMatrixParameters();
        assertEquals(List.of("red dark"), matrix.get("color"));
        assertEquals(List.of("2020", "2021"), matrix.get("year"));
        assertEquals(List.of(""), matrix.get("flag"));
        assertTrue(segments.get(1).getMatrixParameters().isEmpty());
        assertEquals(List.of("red%20dark"), info("/cars;color=red%20dark", "").getPathSegments(false).get(0)
                .getMatrixParameters().get("color"));
    }

    // ---- request, absolute and base URIs ----

    @Test
    void anAbsoluteRequestUriIsUsedAsIs() {
        CassiniUriInfo info = info("/api/things?x=1", API);
        assertEquals(URI.create("http://127.0.0.1/api/things?x=1"), info.getRequestUri());
        assertEquals(URI.create("http://127.0.0.1/api/things?x=1"), info.getRequestUriBuilder().build());
        assertEquals(URI.create("http://127.0.0.1/api/things"), info.getAbsolutePath());
        assertEquals(URI.create("http://127.0.0.1/api/things"), info.getAbsolutePathBuilder().build());
        assertEquals(URI.create("http://127.0.0.1/api/"), info.getBaseUriBuilder().build());
        assertEquals(URI.create("http://127.0.0.1/api/other"), info.resolve(URI.create("other")));
        assertEquals(URI.create("x/y"), info("/api/", API).relativize(URI.create("http://127.0.0.1/api/x/y")));
    }

    @Test
    void aRelativeRequestUriIsResolvedAgainstTheHostHeaderAndTheScheme() {
        var secure = new CassiniUriInfo(exchange(InMemoryExchange.get("/x").header("Host", "example.org:8443"),
                URI.create("/x?q=1"), true), "", Map.of());
        assertEquals(URI.create("https://example.org:8443/x?q=1"), secure.getRequestUri());
        assertEquals(URI.create("https://example.org:8443/"), secure.getBaseUri());

        var noHost = new CassiniUriInfo(exchange(InMemoryExchange.get("/x").replaceHeader("Host", null),
                URI.create("x"), false), "", Map.of());
        assertEquals(URI.create("http://127.0.0.1/x"), noHost.getRequestUri());

        var noUri = new CassiniUriInfo(exchange(InMemoryExchange.get("/x"), null, false), "", Map.of());
        assertEquals(URI.create("http://127.0.0.1/"), noUri.getRequestUri());
    }

    @Test
    void anOpaqueRequestUriHasAnEmptyPath() {
        var opaque = new CassiniUriInfo(exchange(InMemoryExchange.get("/x"), URI.create("urn:isbn:1"), false),
                null, null);
        assertEquals("", opaque.getPath());
        assertTrue(opaque.getPathParameters().isEmpty());
    }

    /** CASSINI-031: the two examples of the UriInfo.relativize Javadoc. */
    @Test
    void relativizeFollowsTheExamplesOfTheSpecification() {
        var info = new CassiniUriInfo(InMemoryExchange.get("example.com:8080", "/app/root/a/b/c/resource.html"),
                "/app/root", Map.of());
        assertEquals(URI.create("http://example.com:8080/app/root/"), info.getBaseUri());
        assertEquals(URI.create("d/file.txt"), info.relativize(URI.create("a/b/c/d/file.txt")));
        URI elsewhere = URI.create("http://example2.com:9090/app2/root2/a/d/file.txt");
        assertEquals(elsewhere, info.relativize(elsewhere));
    }

    // ---- parameters ----

    @Test
    void pathParametersAreDecodedOnDemandKeepingThePlusSign() {
        var info = new CassiniUriInfo(InMemoryExchange.get("/x"), "",
                Map.of("id", List.of("a%20b", "x+y%21", PLAIN)));
        assertEquals(List.of("a b", "x+y!", PLAIN), info.getPathParameters().get("id"));
        assertEquals(List.of("a%20b", "x+y%21", PLAIN), info.getPathParameters(false).get("id"));
        var bad = new CassiniUriInfo(InMemoryExchange.get("/x"), "", Map.of("id", List.of("%zz")));
        assertEquals("%zz", bad.getPathParameters().getFirst("id"), "an undecodable value is kept");
    }

    @Test
    void queryParametersAreDecodedOrRaw() {
        CassiniUriInfo info = info("/x?a=1&b&&c=%20&a=2", "");
        MultivaluedMap<String, String> raw = info.getQueryParameters(false);
        assertEquals(List.of("1", "2"), raw.get("a"));
        assertEquals(List.of(""), raw.get("b"));
        assertEquals(List.of("%20"), raw.get("c"));
        MultivaluedMap<String, String> decoded = info.getQueryParameters();
        assertEquals(List.of("1", "2"), decoded.get("a"));
        assertEquals(List.of(" "), decoded.get("c"));
        assertTrue(info("/x", "").getQueryParameters(false).isEmpty());
        assertTrue(info("/x?", "").getQueryParameters(false).isEmpty());
    }

    // ---- matching ----

    @Test
    void matchedUrisGoFromTheMethodToTheResource() {
        assertEquals(List.of("a/b c", "a"), info("/a/b%20c", "").getMatchedURIs());
        assertEquals(List.of("a/b%20c", "a"), info("/a/b%20c", "").getMatchedURIs(false));
        assertEquals(List.of("a"), info("/a", "").getMatchedURIs());
        assertEquals(List.of(), info("/", "").getMatchedURIs());
    }

    @Test
    void matchedResourcesComeFromTheExchange() {
        InMemoryExchange exchange = InMemoryExchange.get("/x");
        var info = new CassiniUriInfo(exchange, "", Map.of());
        assertEquals(List.of(), info.getMatchedResources());
        Object resource = new Object();
        exchange.setAttribute(Invoker.ATTR_MATCHED_RESOURCES, List.of(resource));
        assertEquals(List.of(resource), info.getMatchedResources());
    }

    @Test
    void theMatchedTemplateIsEmptyWithoutAMatch() {
        assertEquals("", info("/x", "").getMatchedResourceTemplate());
        assertEquals(ITEM, new CassiniUriInfo(InMemoryExchange.get("/r/1"), "", Map.of(), ITEM)
                .getMatchedResourceTemplate());
    }

    /** Answers the matched resource template seen through {@code @Context UriInfo}. */
    @Path("/r")
    public static class TemplateResource {
        @GET
        @Path("{id}")
        @Produces("text/plain")
        public String template(@Context UriInfo uriInfo) {
            return uriInfo.getMatchedResourceTemplate();
        }

        @GET
        @Path("application")
        @Produces("text/plain")
        public String application(@Context Application application) {
            return application.getClass().getSimpleName();
        }
    }

    @ApplicationPath("shop/")
    public static class ShopApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(TemplateResource.class);
        }
    }

    @ApplicationPath("/")
    public static class RootApplication extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(TemplateResource.class);
        }
    }

    /** CASSINI-028: a stack built from an Application must expose it to the requests. */
    @Test
    void theMatchedTemplateStartsWithTheApplicationPath() {
        assertEquals("/shop" + ITEM, templateSeenBy(new ShopApplication()));
        assertEquals(ITEM, templateSeenBy(new RootApplication()));
        assertEquals(ITEM, templateSeenBy(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(TemplateResource.class);
            }
        }));
    }

    /** CASSINI-028: {@code @Context Application} is the application the stack was built from. */
    @Test
    void contextApplicationIsTheApplicationOfTheStack() {
        assertEquals("ShopApplication", answer(new ShopApplication(), "/r/application"));
    }

    private static String templateSeenBy(Application application) {
        return answer(application, "/r/7");
    }

    private static String answer(Application application, String path) {
        CassiniStack stack = new io.vidocq.cassini.internal.CassiniStackBuilderImpl().application(application).build();
        InMemoryExchange exchange = InMemoryExchange.get(path);
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals(200, exchange.status(), exchange.responseText());
        return exchange.responseText();
    }

    /** {@code delegate} with another request URI and security. */
    private static CassiniHttpExchange exchange(InMemoryExchange delegate, URI requestUri, boolean secure) {
        return (CassiniHttpExchange) Proxy.newProxyInstance(UriInfoTest.class.getClassLoader(),
                new Class<?>[]{CassiniHttpExchange.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "requestUri": return requestUri;
                        case "isSecure": return secure;
                        default:
                            try {
                                return method.invoke(delegate, args);
                            } catch (InvocationTargetException e) {
                                throw e.getCause();
                            }
                    }
                });
    }
}
