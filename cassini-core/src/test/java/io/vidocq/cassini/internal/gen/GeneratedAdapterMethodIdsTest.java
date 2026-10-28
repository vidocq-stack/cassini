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

import io.vidocq.cassini.spi.gen.ResourceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.spi.ToolProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The adapter the annotation processor generates is dispatched with the method ids the registry assigns: each id
 * must call the method it was assigned to, whatever the resource inherits.
 */
class GeneratedAdapterMethodIdsTest {

    private static final String PKG = "io.vidocq.cassini.test.ids";
    private static final String PROCESSOR = "io.vidocq.cassini.processor.CassiniResourceProcessor";

    @TempDir
    Path dir;

    private final List<Class<?>> registered = new ArrayList<>();

    @AfterEach
    void cleanup() {
        registered.forEach(AdapterRegistry::deregister);
    }

    /** How the registry comes to hold the adapter: each route must give it the ids its switch follows. */
    enum Route {
        /** A {@code ServiceLoader} provider: the processor's adapter, adopted as {@code lookup} adopts it. */
        SERVICE,
        /** The processor's adapter, found by name next to the resource. */
        BY_NAME,
        /** No pre-generated adapter: {@code lookup} generates one. */
        RUNTIME
    }

    private static final Map<String, String> GENERIC = Map.of(
            "Base", """
                    package %s;
                    public class Base<T> {
                        public String get(T value) { return "base:" + value; }
                    }
                    """,
            "Generic", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/generic")
                    public class Generic extends Base<String> {
                        @GET @Override public String get(String value) { return "get:" + value; }
                        @PUT public String put(String value) { return "put:" + value; }
                    }
                    """);

    private static final Map<String, String> VISIBILITY_BRIDGE = Map.of(
            "Hidden", """
                    package %s;
                    class Hidden {
                        public String hello(String value) { return "hello:" + value; }
                    }
                    """,
            "Visible", """
                    package %s;
                    import jakarta.ws.rs.*;
                    @Path("/visible")
                    public class Visible extends Hidden {
                        @PUT public String bye(String value) { return "bye:" + value; }
                    }
                    """);

    @ParameterizedTest
    @EnumSource(Route.class)
    void aGenericOverrideKeepsTheIdsOfTheOtherMethods(Route route) throws Throwable {
        var resource = compile(GENERIC, route).loadClass(PKG + ".Generic");
        assertDispatches(resource, resource.getMethod("get", String.class), "get:x", route);
        assertDispatches(resource, resource.getMethod("put", String.class), "put:x", route);
    }

    @ParameterizedTest
    @EnumSource(Route.class)
    void aPublicMethodOfAPackagePrivateSuperclassKeepsTheIdsOfTheOtherMethods(Route route) throws Throwable {
        var resource = compile(VISIBILITY_BRIDGE, route).loadClass(PKG + ".Visible");
        assertDispatches(resource, resource.getMethod("hello", String.class), "hello:x", route);
        assertDispatches(resource, resource.getMethod("bye", String.class), "bye:x", route);
    }

    /** The id the registry gives {@code method} must make the adapter call {@code method}. */
    private void assertDispatches(Class<?> resource, Method method, Object expected, Route route) throws Throwable {
        AdapterRegistry.deregister(resource);
        registered.add(resource);
        ResourceAdapter adapter;
        if (route == Route.SERVICE) {
            adapter = AdapterRegistry.adoptServiceAdapter(resource, (ResourceAdapter) resource.getClassLoader()
                    .loadClass(resource.getName() + "$$CassiniAdapter").getDeclaredConstructor().newInstance());
        } else {
            adapter = AdapterRegistry.lookup(resource).orElseThrow();
        }
        int id = AdapterRegistry.methodId(resource, method);
        assertNotEquals(-1, id, () -> method + " has no id: it would be called reflectively");
        var target = resource.getDeclaredConstructor().newInstance();
        assertEquals(expected, adapter.invoke(id, target, new Object[]{"x"}),
                () -> "id " + id + " of " + method + " called another method");
    }

    /** Compiles {@code sources}, through the annotation processor unless the adapter is left to run time. */
    private ClassLoader compile(Map<String, String> sources, Route route) throws Exception {
        var src = Files.createDirectories(dir.resolve("src"));
        var out = Files.createDirectories(dir.resolve("out"));
        var files = new ArrayList<File>();
        for (var entry : sources.entrySet()) {
            var file = src.resolve(entry.getKey() + ".java");
            Files.writeString(file, entry.getValue().formatted(PKG));
            files.add(file.toFile());
        }
        var classPath = String.join(File.pathSeparator, locationOf(jakarta.ws.rs.Path.class.getName()),
                locationOf(ResourceAdapter.class.getName()), locationOf(PROCESSOR));
        var args = new ArrayList<>(List.of("-d", out.toString(), "-classpath", classPath));
        if (route == Route.RUNTIME) {
            args.add("-proc:none");
        } else {
            args.addAll(List.of("-processor", PROCESSOR, "-processorpath", classPath));
        }
        files.forEach(f -> args.add(f.getPath()));
        var log = new StringWriter();
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        int status = javac.run(new PrintWriter(log), new PrintWriter(log), args.toArray(String[]::new));
        assertEquals(0, status, () -> "compilation failed:\n" + log);
        return new URLClassLoader(new URL[]{out.toUri().toURL()}, getClass().getClassLoader());
    }

    /** The jar or directory a class is loaded from, found by its class file: no module needs to read it. */
    private static String locationOf(String className) throws URISyntaxException {
        var resource = className.replace('.', '/') + ".class";
        var url = ClassLoader.getSystemClassLoader().getResource(resource);
        assertNotNull(url, () -> resource + " is not on the test path");
        var text = url.toString();
        if (text.startsWith("jar:")) {
            return new File(URI.create(text.substring(4, text.indexOf("!/")))).getPath();
        }
        return new File(url.toURI()).getPath().replace(resource.replace('/', File.separatorChar), "");
    }
}
