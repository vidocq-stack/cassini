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

import io.vidocq.cassini.internal.gen.RuntimeAdapterGenerator;
import io.vidocq.cassini.internal.gen.RuntimeRoutesGenerator;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * §3.6: a method without JAX-RS annotations of its own inherits those of the method it overrides or implements,
 * from a superclass first, then from an interface, at any depth. Each way Cassini builds the routes of a class
 * must follow the same rule.
 */
class InheritedResourceMethodsTest {

    private static final String PKG = "io.vidocq.cassini.test.inherit";

    @TempDir
    Path dir;

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    /** Where the route table of each class comes from. */
    enum Routes {
        /** The annotation processor's {@code $$CassiniRoutes} and adapters. */
        PROCESSOR,
        /** The Maven plugin's, which {@code RuntimeRoutesGenerator.toBytecode} writes. */
        PLUGIN,
        /** None: {@code ResourceScanner} reads the annotations at startup. */
        SCANNER
    }

    private static final Map<String, String> SOURCES = Map.ofEntries(
            Map.entry("Api", """
                    package %s;
                    import jakarta.ws.rs.*;
                    public interface Api {
                        @GET @Path("{id}") String get(@PathParam("id") String id);
                    }
                    """),
            Map.entry("ViaInterface", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/via-interface")
                    public class ViaInterface implements Api {
                        public String get(String id) { return "interface:" + id; }
                    }
                    """),
            Map.entry("Base", """
                    package %s;
                    import jakarta.ws.rs.*;
                    public class Base {
                        @GET @Path("inherited") public String inherited() { return "inherited"; }
                        @PUT public String put(String body) { return "base:" + body; }
                    }
                    """),
            Map.entry("ViaSuperclass", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/via-superclass")
                    public class ViaSuperclass extends Base {
                        @Override public String put(String body) { return "put:" + body; }
                    }
                    """),
            Map.entry("Parent", """
                    package %s;
                    import jakarta.ws.rs.*;
                    public interface Parent {
                        @GET @Path("deep") String deep();
                    }
                    """),
            Map.entry("Child", """
                    package %s;
                    public interface Child extends Parent {
                    }
                    """),
            Map.entry("ViaSuperinterface", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/via-superinterface")
                    public class ViaSuperinterface implements Child {
                        public String deep() { return "deep"; }
                    }
                    """),
            Map.entry("AbstractApi", """
                    package %s;
                    public abstract class AbstractApi implements Api {
                    }
                    """),
            Map.entry("ViaAbstractClass", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/via-abstract-class")
                    public class ViaAbstractClass extends AbstractApi {
                        public String get(String id) { return "abstract:" + id; }
                    }
                    """),
            Map.entry("OwnAnnotations", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/own")
                    public class OwnAnnotations implements Api {
                        @POST public String get(String body) { return "own:" + body; }
                    }
                    """),
            Map.entry("LeafApi", """
                    package %s;
                    import jakarta.ws.rs.*;
                    public interface LeafApi {
                        @GET String hello();
                    }
                    """),
            Map.entry("Leaf", """
                    package %s;
                    public class Leaf implements LeafApi {
                        public String hello() { return "leaf"; }
                    }
                    """),
            Map.entry("LocatorApi", """
                    package %s;
                    import jakarta.ws.rs.*;
                    public interface LocatorApi {
                        @Path("sub") Object sub();
                    }
                    """),
            Map.entry("ViaLocator", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/via-locator")
                    public class ViaLocator implements LocatorApi {
                        public Object sub() { return new Leaf(); }
                    }
                    """));

    private static final List<String> ROOTS = List.of("ViaInterface", "ViaSuperclass", "ViaSuperinterface",
            "ViaAbstractClass", "OwnAnnotations", "ViaLocator");

    @ParameterizedTest
    @EnumSource(Routes.class)
    void aMethodInheritsTheAnnotationsOfTheMethodItOverrides(Routes routes) throws Exception {
        CassiniStack stack = stack(routes);

        assertEquals("interface:7", send(stack, "GET", "/via-interface/7", null), "from an interface");
        assertEquals("inherited", send(stack, "GET", "/via-superclass/inherited", null), "not overridden");
        assertEquals("put:x", send(stack, "PUT", "/via-superclass", "x"), "from a superclass");
        assertEquals("deep", send(stack, "GET", "/via-superinterface/deep", null), "from a superinterface");
        assertEquals("abstract:7", send(stack, "GET", "/via-abstract-class/7", null), "from a superclass's interface");
        assertEquals("leaf", send(stack, "GET", "/via-locator/sub", null), "a locator, and what it returns");
    }

    @ParameterizedTest
    @EnumSource(Routes.class)
    void aMethodWithAnnotationsOfItsOwnInheritsNone(Routes routes) throws Exception {
        CassiniStack stack = stack(routes);

        assertEquals("own:x", send(stack, "POST", "/own", "x"));
        assertEquals("404", send(stack, "GET", "/own/7", null), "the interface's @GET @Path is not inherited");
    }

    @ParameterizedTest
    @EnumSource(value = Routes.class, names = {"PROCESSOR", "PLUGIN"})
    void aGeneratedTableIsUsedForAnInheritedMethod(Routes routes) throws Exception {
        // The table names the method on the resource class: it used to be looked up among its declared methods
        // only, so an inherited one sent the whole class back to the scanner.
        ClassLoader loader = CompiledFixtures.loaderOf(compile(routes));
        RouteRegistry.resetCounters();

        RouteRegistry.discover(loader.loadClass(PKG + ".ViaSuperclass"), loader.loadClass(PKG + ".ViaInterface"));

        assertEquals(0, RouteRegistry.scannerFallbackHits());
        assertEquals(2, RouteRegistry.preGeneratedHits());
    }

    private Path compile(Routes routes) throws Exception {
        Path out = CompiledFixtures.compile(dir, PKG, SOURCES, routes == Routes.PROCESSOR);
        if (routes == Routes.PLUGIN) {
            ClassLoader loader = CompiledFixtures.loaderOf(out);
            for (String root : ROOTS) writePluginClasses(out, loader.loadClass(PKG + "." + root));
            writePluginClasses(out, loader.loadClass(PKG + ".Leaf"));
        }
        return out;
    }

    private CassiniStack stack(Routes routes) throws Exception {
        ClassLoader loader = CompiledFixtures.loaderOf(compile(routes));
        Set<Class<?>> classes = new HashSet<>();
        for (String root : ROOTS) classes.add(loader.loadClass(PKG + "." + root));
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return classes;
            }
        }).build();
    }

    /** What {@code cassini:generate} writes for {@code resource}: its adapter and its route table. */
    private static void writePluginClasses(Path out, Class<?> resource) throws Exception {
        Path file = out.resolve(resource.getName().replace('.', '/'));
        Files.write(file.resolveSibling(file.getFileName() + "$$CassiniAdapter.class"),
                RuntimeAdapterGenerator.toBytecode(resource));
        Files.write(file.resolveSibling(file.getFileName() + "$$CassiniRoutes.class"),
                RuntimeRoutesGenerator.toBytecode(resource));
    }

    /** The response text, or the status when it is not 200. */
    private static String send(CassiniStack stack, String method, String path, String body) {
        InMemoryExchange exchange = InMemoryExchange.request(method, path, body == null ? null : "text/plain", body);
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange.status() == 200 ? exchange.responseText() : String.valueOf(exchange.status());
    }
}
