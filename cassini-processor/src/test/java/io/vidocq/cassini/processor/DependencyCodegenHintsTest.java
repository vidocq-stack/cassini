package io.vidocq.cassini.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vidocq's build runs this processor over the dependency jars of an application (Vidocq/vidocq#662): the processor
 * ships the hints that select them, and it must write the adapter from the class file of a resource, as there is no
 * source for a library.
 */
class DependencyCodegenHintsTest {

    private static final String HINTS = "META-INF/vidocq/dependency-codegen.properties";
    private static final String ADAPTERS = "META-INF/services/io.vidocq.cassini.spi.gen.ResourceAdapter";
    private static final String ROUTES = "META-INF/services/io.vidocq.cassini.spi.gen.RouteProvider";

    @TempDir
    Path work;

    private static Properties hints() throws IOException {
        try (InputStream in = DependencyCodegenHintsTest.class.getClassLoader().getResourceAsStream(HINTS)) {
            assertNotNull(in, "the processor must ship " + HINTS);
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }

    private static List<String> list(Properties properties, String key) {
        return Arrays.asList(properties.getProperty(key, "").split(","));
    }

    @Test
    void theHintsSelectAJarThatNamesAPathAndNotOneThatOnlyHasPathParam() throws IOException {
        Properties hints = hints();

        assertEquals("Cassini", hints.getProperty("brick"));
        // The whole internal name: a prefix would select every jar that has an @PathParam.
        assertEquals(List.of("jakarta/ws/rs/Path"), list(hints, "class-bytes"));
    }

    @Test
    void aJarAlreadyBuiltWithTheProcessorIsLeftAloneAndItsServiceFilesAreMerged() throws IOException {
        Properties hints = hints();

        assertEquals(List.of(ADAPTERS, ROUTES), list(hints, "markers"));
        assertEquals(List.of(ADAPTERS, ROUTES), list(hints, "merge"));
    }

    @Test
    void theProcessorWritesTheAdapterAndTheRoutesFromTheClassFileOfAResource() throws Exception {
        Path library = work.resolve("library");
        compileLibraryResource(library);
        Path generated = work.resolve("generated");
        Path sources = work.resolve("sources");
        Files.createDirectories(generated);
        Files.createDirectories(sources);

        // What vidocq:generate does for a jar a brick selects: javac -proc:only over the class names of the jar.
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            List<Path> classPath = new ArrayList<>(List.of(library));
            classPath.addAll(effectiveClassPath());
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, classPath);
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(generated));
            files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(sources));
            JavaCompiler.CompilationTask task = compiler.getTask(null, files, diagnostics,
                    List.of("-proc:only", "-implicit:none", "-Xlint:none"), List.of("lib.Hello"), null);
            task.setProcessors(List.of(new CassiniResourceProcessor()));
            assertTrue(task.call(), diagnostics.getDiagnostics().toString());
        }

        assertTrue(Files.isRegularFile(sources.resolve("lib/Hello$$CassiniAdapter.java")),
                "the adapter is generated from the class file");
        assertTrue(Files.isRegularFile(sources.resolve("lib/Hello$$CassiniRoutes.java")),
                "the routes are generated from the class file");
        assertTrue(Files.readString(generated.resolve(ADAPTERS)).contains("lib.Hello$$CassiniAdapter"));
        assertTrue(Files.readString(generated.resolve(ROUTES)).contains("lib.Hello$$CassiniRoutes"));
    }

    /** A library resource, compiled without the processor: no adapter next to it. */
    private void compileLibraryResource(Path output) throws IOException {
        Files.createDirectories(output);
        Path source = work.resolve("Hello.java");
        Files.writeString(source, """
                package lib;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.PathParam;

                @Path("/hello")
                public class Hello {
                    @GET
                    @Path("{name}")
                    public String greet(@PathParam("name") String name) {
                        return "Hello " + name;
                    }
                }
                """);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(diagnostics, null,
                StandardCharsets.UTF_8)) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(output));
            files.setLocationFromPaths(StandardLocation.CLASS_PATH, effectiveClassPath());
            boolean compiled = compiler.getTask(null, files, diagnostics, List.of("-proc:none", "-Xlint:none"), null,
                    files.getJavaFileObjects(source)).call();
            assertTrue(compiled, diagnostics.getDiagnostics().toString());
        }
        assertTrue(Files.isRegularFile(output.resolve("lib/Hello.class")));
        assertTrue(Files.notExists(output.resolve("lib/Hello$$CassiniAdapter.class")));
    }

    /** Surefire runs this module on the module path: the jars of the layer, then the class path if there is one. */
    private static List<Path> effectiveClassPath() {
        LinkedHashSet<Path> entries = new LinkedHashSet<>();
        ModuleLayer layer = DependencyCodegenHintsTest.class.getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(module -> module.reference().location()
                    .filter(uri -> "file".equals(uri.getScheme())).ifPresent(uri -> entries.add(Path.of(uri))));
        }
        for (String entry : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!entry.isBlank()) {
                entries.add(Path.of(entry));
            }
        }
        return List.copyOf(entries);
    }
}
