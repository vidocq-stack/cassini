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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation of {@link ResourceScanner#discover}: resource methods, sub-resource
 * locators (§3.4.1), {@code Class<T>} locators (§3.4.2), recursive locators and the
 * {@code @Produces}/{@code @Consumes} inheritance between class, locator and method.
 */
class ResourceScannerTest {

    private static final String JSON = "application/json";
    private static final String TEXT = "text/plain";
    private static final String SUB_LOCATOR = "subLocator";

    // ------------------------------------------------------------------ fixtures

    @Path("/root")
    @Produces(JSON)
    @Consumes(JSON)
    public static class RootResource {
        @GET
        public String list() { return "[]"; }

        @POST
        @Path("/text")
        @Consumes(TEXT)
        public String create(String body) { return body; }

        @GET
        @Path("/hidden")
        String notPublic() { return "hidden"; }

        @Path("/void")
        public void voidLocator() {
            // a locator returning void exposes nothing
        }

        public String noAnnotation() { return "plain"; }

        @Path("/direct")
        public Response directLocator() { return Response.ok().build(); }

        @Path("/sub")
        @Produces(TEXT)
        public SubResource subLocator() { return new SubResource(); }

        @Path("/typed")
        public Class<SubResource> typedLocator() { return SubResource.class; }

        @Path("/dynamic")
        public Object dynamicLocator() { return new SubResource(); }
    }

    public static class SubResource {
        @GET
        public String get() { return "sub"; }

        @GET
        @Path("/json")
        @Produces(JSON)
        public String json() { return "{}"; }

        @Path("/leaf")
        public Response leaf() { return Response.ok().build(); }

        @Path("/nested")
        @Consumes(TEXT)
        public NestedResource nested() { return new NestedResource(); }

        @Path("/nothing")
        public void nothing() {
            // a nested locator returning void exposes nothing
        }

        String notPublic() { return "hidden"; }
    }

    @Produces(JSON)
    public static class NestedResource {
        @GET
        public String get() { return "nested"; }
    }

    @Path("/rec")
    public static class RecursiveRoot {
        @Path("/node")
        public Node node() { return new Node(); }
    }

    public static class Node {
        @GET
        public String get() { return "node"; }

        @Path("/node")
        public Node child() { return new Node(); }
    }

    @Path("/cycle")
    public static class CycleRoot {
        @Path("/a")
        public CycleA a() { return new CycleA(); }
    }

    public static class CycleA {
        @GET
        public String get() { return "a"; }

        @Path("/b")
        public CycleB b() { return new CycleB(); }
    }

    public static class CycleB {
        @Path("/a")
        public CycleA backToA() { return new CycleA(); }
    }

    public static class NotAResource {
        @GET
        public String get() { return "never scanned"; }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, ResourceMethod> byRoute(List<ResourceMethod> methods) {
        return methods.stream().collect(Collectors.toMap(
                rm -> rm.httpMethod() + " " + rm.template().template(), Function.identity(),
                (first, second) -> first));
    }

    // ------------------------------------------------------------------ tests

    @Test
    void classWithoutPathIsIgnored() {
        assertTrue(ResourceScanner.discover(NotAResource.class).isEmpty());
    }

    @Test
    void resourceMethodsInheritClassMediaTypesUnlessOverridden() {
        Map<String, ResourceMethod> routes = byRoute(ResourceScanner.discover(RootResource.class));

        ResourceMethod list = routes.get("GET /root");
        assertEquals(Set.of(JSON), list.produces());
        assertEquals(Set.of(JSON), list.consumes());
        assertNull(list.rootBeanClass());

        ResourceMethod create = routes.get("POST /root/text");
        assertEquals(Set.of(JSON), create.produces());
        assertEquals(Set.of(TEXT), create.consumes());
    }

    @Test
    void nonPublicVoidAndUnannotatedMethodsExposeNoRoute() {
        Set<String> routes = byRoute(ResourceScanner.discover(RootResource.class)).keySet();

        assertFalse(routes.contains("GET /root/hidden"));
        assertTrue(routes.stream().noneMatch(r -> r.contains("/void") || r.contains("/nothing")));
    }

    @Test
    void locatorReturningResponseIsATerminalRouteForEveryMethod() {
        ResourceMethod direct = byRoute(ResourceScanner.discover(RootResource.class)).get("* /root/direct");

        assertEquals("directLocator", direct.javaMethod().getName());
        assertEquals(RootResource.class, direct.beanClass());
        assertNull(direct.locatorChain());
    }

    @Test
    void subResourceRoutesCarryTheLocatorChainAndInheritedMediaTypes() {
        Map<String, ResourceMethod> routes = byRoute(ResourceScanner.discover(RootResource.class));

        ResourceMethod get = routes.get("GET /root/sub");
        assertEquals(SubResource.class, get.beanClass());
        assertEquals(RootResource.class, get.rootBeanClass());
        assertEquals(List.of(SUB_LOCATOR), get.locatorChain().stream().map(Method::getName).toList());
        assertEquals(Set.of(TEXT), get.produces());
        assertEquals(Set.of(JSON), get.consumes());
        assertEquals(5, get.classPathLiterals());

        assertEquals(Set.of(JSON), routes.get("GET /root/sub/json").produces());
    }

    @Test
    void nestedLocatorReturningResponseIsATerminalRoute() {
        ResourceMethod leaf = byRoute(ResourceScanner.discover(RootResource.class)).get("* /root/sub/leaf");

        assertEquals(SubResource.class, leaf.beanClass());
        assertEquals(RootResource.class, leaf.rootBeanClass());
        assertEquals(List.of(SUB_LOCATOR), leaf.locatorChain().stream().map(Method::getName).toList());
    }

    @Test
    void nestedLocatorExtendsTheChainAndMediaTypes() {
        ResourceMethod nested = byRoute(ResourceScanner.discover(RootResource.class)).get("GET /root/sub/nested");

        assertEquals(NestedResource.class, nested.beanClass());
        assertEquals(List.of(SUB_LOCATOR, "nested"),
                nested.locatorChain().stream().map(Method::getName).toList());
        assertEquals(Set.of(JSON), nested.produces());
        assertEquals(Set.of(TEXT), nested.consumes());
    }

    @Test
    void classLocatorIsUnwrappedToItsTypeArgument() {
        ResourceMethod typed = byRoute(ResourceScanner.discover(RootResource.class)).get("GET /root/typed");

        assertEquals(SubResource.class, typed.beanClass());
        assertEquals(List.of("typedLocator"), typed.locatorChain().stream().map(Method::getName).toList());
    }

    @Test
    void objectLocatorEmitsTwoDynamicRoutes() {
        Map<String, ResourceMethod> routes = byRoute(ResourceScanner.discover(RootResource.class));

        ResourceMethod exact = routes.get("* /root/dynamic");
        ResourceMethod rest = routes.get("* /root/dynamic/{__rest:.*}");
        assertTrue(exact.dynamicLocator());
        assertTrue(rest.dynamicLocator());
        assertEquals(Object.class, exact.beanClass());
        assertEquals("dynamicLocator", rest.javaMethod().getName());
    }

    @Test
    void selfRecursiveLocatorIsUnrolledUpToTheDepthLimit() {
        List<ResourceMethod> methods = ResourceScanner.discover(RecursiveRoot.class);

        int deepest = methods.stream().mapToInt(rm -> rm.locatorChain().size()).max().orElseThrow();
        assertEquals(12, deepest);
        assertEquals(12, methods.size());
        assertTrue(byRoute(methods).containsKey("GET /rec/node/node"));
    }

    @Test
    void cycleThroughAnotherClassIsBoundedByTheDepthLimit() {
        List<ResourceMethod> methods = ResourceScanner.discover(CycleRoot.class);

        assertEquals(12, methods.size());
        assertTrue(byRoute(methods).containsKey("GET /cycle/a/b/a"));
        int deepest = methods.stream().mapToInt(rm -> rm.locatorChain().size()).max().orElseThrow();
        assertEquals(23, deepest);
    }
}
