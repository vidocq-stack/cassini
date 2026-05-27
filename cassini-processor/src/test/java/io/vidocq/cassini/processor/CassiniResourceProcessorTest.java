package io.vidocq.cassini.processor;

import io.vidocq.cassini.spi.gen.InjectionSupport;
import io.vidocq.cassini.spi.gen.ParamKind;
import io.vidocq.cassini.spi.gen.ResourceAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.File;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P2 correctness gate: compiles sample @Path resources WITH the cassini-processor
 * on the annotation-processor path (in-process via javax.tools.JavaCompiler),
 * verifies that $$CassiniAdapter sources are generated and behave correctly.
 *
 * Coverage:
 * 1. Processor generates a $$CassiniAdapter source file.
 * 2. Generated adapter implements ResourceAdapter.
 * 3. injectFields injects a private @Context SecurityContext field.
 * 4. injectFields injects a @QueryParam String field.
 * 5. injectFields skips @*Param fields when injectParams=false.
 * 6. invoke dispatches to the correct method by methodId (primitive args, void, reference).
 * 7. Private @Context field accessible via VarHandle (key AOT acid test).
 */
class CassiniResourceProcessorTest {

    @TempDir
    Path tempDir;

    // -------------------------------------------------------------------------
    // Helper: compile sources with the cassini-processor
    // -------------------------------------------------------------------------

    /**
     * Compiles the given source files using the system Java compiler with cassini-processor
     * on the annotation-processor path. Returns a URLClassLoader over the output directory.
     *
     * <p>Works under both classpath and module-path execution: collects the effective
     * classpath from {@code java.class.path} system property AND from the boot/app
     * class loader URLs (covering module-path JARs resolved by Surefire).</p>
     */
    private URLClassLoader compileWithProcessor(File outputDir, File... sources) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "javax.tools.JavaCompiler not available in this JDK");

        // Collect all classpath entries from system property AND from current classloader chain.
        List<File> cpFiles = new ArrayList<>();

        // 1. java.class.path property (set when running on classpath)
        String cpProp = System.getProperty("java.class.path", "");
        if (!cpProp.isBlank()) {
            for (String entry : cpProp.split(File.pathSeparator)) {
                if (!entry.isBlank()) cpFiles.add(new File(entry));
            }
        }

        // 2. Module-path JARs: walk the boot module layer and application class loader.
        //    When Surefire runs with --module-path, java.class.path may be empty.
        //    We grab the locations from URLClassLoader ancestors.
        ClassLoader cl = getClass().getClassLoader();
        while (cl != null) {
            if (cl instanceof java.net.URLClassLoader ucl) {
                for (java.net.URL url : ucl.getURLs()) {
                    if ("file".equals(url.getProtocol())) {
                        cpFiles.add(new File(url.toURI()));
                    }
                }
            }
            cl = cl.getParent();
        }

        // 3. Resolve locations from the named module layer (covers JPMS module-path jars).
        ModuleLayer layer = getClass().getModule().getLayer();
        if (layer != null) {
            layer.configuration().modules().forEach(rm -> {
                rm.reference().location().ifPresent(uri -> {
                    if ("file".equals(uri.getScheme())) {
                        cpFiles.add(new File(uri));
                    }
                });
            });
        }

        // Deduplicate
        List<File> dedupCp = cpFiles.stream().distinct().filter(File::exists).toList();

        DiagnosticCollector<JavaFileObject> diags = new DiagnosticCollector<>();
        StandardJavaFileManager fm = compiler.getStandardFileManager(diags, Locale.ROOT, null);

        fm.setLocation(StandardLocation.CLASS_OUTPUT, List.of(outputDir));
        fm.setLocation(StandardLocation.CLASS_PATH, dedupCp);
        fm.setLocation(StandardLocation.ANNOTATION_PROCESSOR_PATH, dedupCp);

        Iterable<? extends JavaFileObject> compilationUnits = fm.getJavaFileObjects(sources);

        List<String> options = List.of(
                "--release", "25",
                "-proc:full"        // run annotation processing
        );

        JavaCompiler.CompilationTask task =
                compiler.getTask(null, fm, diags, options, null, compilationUnits);

        boolean success = task.call();

        if (!success) {
            StringBuilder sb = new StringBuilder("Compilation failed:\n");
            for (var d : diags.getDiagnostics()) {
                if (d.getKind() == javax.tools.Diagnostic.Kind.ERROR) {
                    sb.append("ERROR: ").append(d.getMessage(Locale.ROOT)).append("\n");
                }
            }
            fail(sb.toString());
        }

        fm.close();
        return new URLClassLoader(
                new java.net.URL[]{outputDir.toURI().toURL()},
                getClass().getClassLoader());
    }

    // -------------------------------------------------------------------------
    // Test resources (written to temp dir as .java files)
    // -------------------------------------------------------------------------

    /** Writes a Java source file under the temp dir and returns the File. */
    private File writeSource(String relativePath, String content) throws Exception {
        Path file = tempDir.resolve(relativePath);
        file.getParent().toFile().mkdirs();
        java.nio.file.Files.writeString(file, content);
        return file.toFile();
    }

    // -------------------------------------------------------------------------
    // FakeInjectionSupport for generated adapter calls
    // -------------------------------------------------------------------------

    static class FakeSupport implements InjectionSupport {
        private final SecurityContext sc;
        private final String queryValue;

        FakeSupport(SecurityContext sc, String queryValue) {
            this.sc = sc;
            this.queryValue = queryValue;
        }

        @SuppressWarnings("unchecked")
        @Override
        public <T> T context(Class<T> type) {
            if (type == SecurityContext.class) return (T) sc;
            return null;
        }

        @Override
        public Object param(ParamKind kind, String name, boolean encoded,
                            String defaultValue, Class<?> rawType, Class<?> elementType) {
            if (kind == ParamKind.QUERY && "q".equals(name)) return queryValue;
            return defaultValue;
        }

        @Override
        public Object beanParam(Class<?> type) { return null; }

        @Override
        public Object suspendedAsyncResponse() { return null; }
    }

    private static SecurityContext fakeSc() {
        return new SecurityContext() {
            @Override public Principal getUserPrincipal() { return null; }
            @Override public boolean isUserInRole(String role) { return false; }
            @Override public boolean isSecure() { return true; }
            @Override public String getAuthenticationScheme() { return "TEST"; }
        };
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    /**
     * Core test: compile a @Path resource, verify the $$CassiniAdapter is generated,
     * and test both injectFields and invoke on it.
     */
    @Test
    void processorGeneratesAdapterWithInjectAndInvoke() throws Throwable {
        // Source: a @Path resource with a private @Context SecurityContext field,
        // a @QueryParam String field, and two resource methods.
        String resourceSource = """
                package io.vidocq.cassini.test.apt;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;
                import jakarta.ws.rs.QueryParam;
                import jakarta.ws.rs.core.Context;
                import jakarta.ws.rs.core.SecurityContext;

                @Path("/hello")
                public class HelloResource {

                    @Context
                    private SecurityContext sc;

                    @QueryParam("q")
                    public String query;

                    @GET
                    public String greet() {
                        return "hello";
                    }

                    @GET
                    @Path("/add")
                    public int add(int a, int b) {
                        return a + b;
                    }
                }
                """;

        File src = writeSource("io/vidocq/cassini/test/apt/HelloResource.java", resourceSource);
        File outDir = tempDir.resolve("out").toFile();
        outDir.mkdirs();

        URLClassLoader loader = compileWithProcessor(outDir, src);

        // 1. Verify adapter class was generated and compiled
        Class<?> adapterClass = loader.loadClass(
                "io.vidocq.cassini.test.apt.HelloResource$$CassiniAdapter");
        assertNotNull(adapterClass, "$$CassiniAdapter class must be loadable");

        // 2. Verify it implements ResourceAdapter
        assertTrue(ResourceAdapter.class.isAssignableFrom(adapterClass),
                "generated class must implement ResourceAdapter");

        // 3. Instantiate
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();

        // 4. Load the resource class
        Class<?> resourceClass = loader.loadClass("io.vidocq.cassini.test.apt.HelloResource");
        Object resource = resourceClass.getDeclaredConstructor().newInstance();

        // --- injectFields: @Context SecurityContext (private field) ---
        SecurityContext sc = fakeSc();
        FakeSupport support = new FakeSupport(sc, "test-query");
        adapter.injectFields(resource, support, true);

        // Verify private field was injected
        java.lang.reflect.Field scField = resourceClass.getDeclaredField("sc");
        scField.setAccessible(true);
        assertSame(sc, scField.get(resource), "private @Context SecurityContext must be injected via VarHandle");

        // Verify @QueryParam field
        java.lang.reflect.Field qField = resourceClass.getDeclaredField("query");
        assertEquals("test-query", qField.get(resource), "@QueryParam field must be injected");

        // --- injectParams=false: @QueryParam must NOT be injected ---
        Object resource2 = resourceClass.getDeclaredConstructor().newInstance();
        adapter.injectFields(resource2, new FakeSupport(sc, "should-not-be-set"), false);
        assertNull(qField.get(resource2), "@QueryParam must be skipped when injectParams=false");
        // But @Context must still be injected
        assertSame(sc, scField.get(resource2), "@Context must still be injected when injectParams=false");
    }

    /**
     * Tests that the invoke method dispatches correctly to add(int,int).
     * The methodId must align with the canonical ordering used by RuntimeAdapterGenerator.
     */
    @Test
    void processorGeneratedAdapterInvokeDispatches() throws Throwable {
        String resourceSource = """
                package io.vidocq.cassini.test.apt;

                import jakarta.ws.rs.GET;
                import jakarta.ws.rs.Path;

                @Path("/calc")
                public class CalcResource {

                    @GET
                    public int add(int a, int b) {
                        return a + b;
                    }

                    @GET
                    @Path("/echo")
                    public String echo(String s) {
                        return s == null ? null : s.toUpperCase();
                    }

                    @GET
                    @Path("/void")
                    public void doNothing() {}
                }
                """;

        File src = writeSource("io/vidocq/cassini/test/apt/CalcResource.java", resourceSource);
        File outDir = tempDir.resolve("out-calc").toFile();
        outDir.mkdirs();

        URLClassLoader loader = compileWithProcessor(outDir, src);

        Class<?> adapterClass = loader.loadClass(
                "io.vidocq.cassini.test.apt.CalcResource$$CassiniAdapter");
        ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();
        Class<?> resourceClass = loader.loadClass("io.vidocq.cassini.test.apt.CalcResource");
        Object resource = resourceClass.getDeclaredConstructor().newInstance();

        // Find methodIds by matching the canonical order used at runtime.
        // The canonical order is: (declaringClass, methodName, jvmDescriptor).
        // For CalcResource declared in CalcResource:
        //   add(int,int)    -> "(II)"
        //   doNothing()     -> "()"
        //   echo(String)    -> "(Ljava/lang/String;)"
        // Sorted by (name, descriptor): add < doNothing < echo
        // So: add=0, doNothing=1, echo=2

        // Test add(3, 4) = 7
        Object addResult = adapter.invoke(0, resource, new Object[]{3, 4});
        assertEquals(7, addResult, "add(3,4) must return 7");

        // Test doNothing() returns null
        Object voidResult = adapter.invoke(1, resource, new Object[0]);
        assertNull(voidResult, "void method must return null");

        // Test echo("hello") = "HELLO"
        Object echoResult = adapter.invoke(2, resource, new Object[]{"hello"});
        assertEquals("HELLO", echoResult, "echo must uppercase the argument");

        // Test unknown methodId throws
        assertThrows(UnsupportedOperationException.class, () -> {
            try {
                adapter.invoke(999, resource, new Object[0]);
            } catch (Throwable t) {
                if (t instanceof UnsupportedOperationException u) throw u;
                throw new RuntimeException(t);
            }
        });
    }
}
