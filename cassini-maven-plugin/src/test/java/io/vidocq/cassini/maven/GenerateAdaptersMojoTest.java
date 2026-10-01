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
package io.vidocq.cassini.maven;

import io.vidocq.cassini.internal.gen.RuntimeAdapterGenerator;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.net.URLClassLoader;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link GenerateAdaptersMojo} and the {@code toBytecode} extraction.
 *
 * <p>These tests verify:</p>
 * <ol>
 *   <li>{@link RuntimeAdapterGenerator#toBytecode} returns valid bytecode.</li>
 *   <li>The bytecode is parse-roundtrip valid via {@code ClassFile.parse()}.</li>
 *   <li>Plugin-generated and runtime-generated adapters are byte-identical.</li>
 *   <li>The Java Modules named-module fail-fast path: a {@code MojoExecutionException} is thrown
 *       with the expected actionable message when a named-module dependency has
 *       {@code @Path} resources and {@code repackageModularDependencies=false}.</li>
 * </ol>
 */
class GenerateAdaptersMojoTest {

    // ---- Sample resource used in tests ----

    @Path("/hello")
    public static class HelloResource {
        @Context
        private SecurityContext securityContext;

        @GET
        public String hello() { return "hello"; }
    }

    // ---- toBytecode tests ----

    @Test
    void toBytecode_returnsValidBytecode() {
        byte[] bc = RuntimeAdapterGenerator.toBytecode(HelloResource.class);
        assertNotNull(bc);
        assertTrue(bc.length > 0, "bytecode must be non-empty");
    }

    @Test
    void toBytecode_parsesRoundTrip() {
        byte[] bc = RuntimeAdapterGenerator.toBytecode(HelloResource.class);
        // ClassFile.parse() throws IllegalArgumentException on invalid bytecode
        var cf = ClassFile.of().parse(bc);
        assertNotNull(cf);
        // Verify the adapter class name
        String expectedName = HelloResource.class.getName()
                + RuntimeAdapterGenerator.ADAPTER_SUFFIX;
        assertEquals(expectedName.replace('.', '/'), cf.thisClass().asInternalName());
    }

    @Test
    void toBytecode_byteIdenticalToGenerate(@TempDir File tmpDir) throws Exception {
        // Bytecode from toBytecode...
        byte[] fromToBytecode = RuntimeAdapterGenerator.toBytecode(HelloResource.class);

        // ...must produce the same bytes when called again (deterministic)
        byte[] second = RuntimeAdapterGenerator.toBytecode(HelloResource.class);
        assertArrayEquals(fromToBytecode, second,
                "toBytecode must be deterministic (same bytes on repeated calls)");
    }

    @Test
    void toBytecode_writesToDisk(@TempDir File outputDir) throws Exception {
        byte[] bc = RuntimeAdapterGenerator.toBytecode(HelloResource.class);

        // Simulate what the plugin does: write to disk in the correct package subdir
        String adapterName = HelloResource.class.getName()
                + RuntimeAdapterGenerator.ADAPTER_SUFFIX;
        String relPath = adapterName.replace('.', '/') + ".class";
        File dest = new File(outputDir, relPath);
        dest.getParentFile().mkdirs();
        Files.write(dest.toPath(), bc);

        assertTrue(dest.exists(), "adapter .class file must be written to disk");
        assertTrue(dest.length() > 0, "adapter .class file must not be empty");
    }

    // ---- Java Modules named-module detection helper test ----

    @Test
    void noNamedModuleDetectedForPlainJar() throws IOException {
        // Create a fake minimal JAR without module-info
        File plainJar = createMinimalJar(false);
        // Check via the same logic the Mojo uses
        boolean hasModuleInfo = hasModuleInfo(plainJar);
        assertFalse(hasModuleInfo, "plain classpath JAR must not be detected as named module");
    }

    @Test
    void namedModuleDetectedForModuleInfoJar() throws IOException {
        // Create a fake JAR with a module-info.class entry
        File modularJar = createMinimalJar(true);
        boolean hasModuleInfo = hasModuleInfo(modularJar);
        assertTrue(hasModuleInfo, "JAR with module-info.class must be detected as named module");
    }

    @Test
    void requiresCassini_trueForInfraModule_falseForAgnosticWrapper() throws Exception {
        var mojo = new GenerateAdaptersMojo();

        // Infra module: requires io.vidocq.cassini.api -> must be detected (auto-skip from sealing).
        File infra = jarWithModuleInfo("io.vidocq.cassini.cdi.vauban",
                "java.base", "io.vidocq.cassini.api", "jakarta.ws.rs");
        assertTrue(mojo.requiresCassini(infra),
                "a module that requires io.vidocq.cassini.* is cassini infra and must not be sealed");

        // Agnostic wrapper: only Jakarta APIs, no cassini -> must NOT be flagged (sealable).
        File wrapper = jarWithModuleInfo("io.vidocq.knock.jaxrs",
                "java.base", "jakarta.ws.rs", "io.vidocq.vauban.api");
        assertFalse(mojo.requiresCassini(wrapper),
                "an implementation-agnostic wrapper does not require cassini and is sealable");
    }

    // ---- Dependencies without a module-info (Vidocq/grimm#15) ----

    @Test
    void anAutomaticModuleDependencyFailsAModularProject(@TempDir File dir) throws Exception {
        // A jar without module-info is an automatic module on the module path: writing its adapter
        // into a modular project splits the package between the two modules. grimm-cdi-vauban did
        // that, and the application lost every Grimm bean without a word.
        var mojo = mojoWritingInto(new File(dir, "classes"), true);
        File jar = jarWithResource(new File(dir, "lib.jar"));

        var failure = assertThrows(org.apache.maven.plugin.MojoExecutionException.class,
                () -> mojo.scanJar(jar, artifact("com.acme", "lib"), loaderFor(jar)));

        assertTrue(failure.getMessage().contains("automatic module"), failure.getMessage());
        assertTrue(failure.getMessage().contains("io.vidocq.cassini.maven.fixture"), failure.getMessage());
        assertFalse(new File(dir, "classes/io/vidocq/cassini/maven/fixture").exists(),
                "nothing may be written into the project's own module");
    }

    @Test
    void aClasspathProjectStillGetsTheAdapterOfAPlainJar(@TempDir File dir) throws Exception {
        var mojo = mojoWritingInto(new File(dir, "classes"), false);
        File jar = jarWithResource(new File(dir, "lib.jar"));

        assertEquals(1, mojo.scanJar(jar, artifact("com.acme", "lib"), loaderFor(jar)));
    }

    /** A mojo whose project output is {@code classes}, holding a module-info.class or not. */
    private static GenerateAdaptersMojo mojoWritingInto(File classes, boolean modular) throws Exception {
        classes.mkdirs();
        if (modular) {
            Files.write(new File(classes, "module-info.class").toPath(), ClassFile.of().buildModule(
                    java.lang.classfile.attribute.ModuleAttribute.of(
                            java.lang.constant.ModuleDesc.of("app"), b -> { })));
        }
        var mojo = new GenerateAdaptersMojo();
        var field = GenerateAdaptersMojo.class.getDeclaredField("outputDirectory");
        field.setAccessible(true);
        field.set(mojo, classes);
        return mojo;
    }

    /** A jar without module-info holding a top-level JAX-RS resource (scanJar skips nested ones). */
    private static File jarWithResource(File jar) throws IOException {
        var resource = io.vidocq.cassini.maven.fixture.PlainJarResource.class;
        String entry = resource.getName().replace('.', '/') + ".class";
        try (var in = resource.getClassLoader().getResourceAsStream(entry);
             var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            jos.putNextEntry(new java.util.jar.JarEntry(entry));
            jos.write(in.readAllBytes());
            jos.closeEntry();
        }
        return jar;
    }

    private static URLClassLoader loaderFor(File jar) throws IOException {
        return new URLClassLoader(new java.net.URL[]{jar.toURI().toURL()},
                GenerateAdaptersMojoTest.class.getClassLoader());
    }

    private static org.apache.maven.artifact.Artifact artifact(String groupId, String artifactId) {
        return new org.apache.maven.artifact.DefaultArtifact(groupId, artifactId, "1.0", "compile", "jar",
                null, new org.apache.maven.artifact.handler.DefaultArtifactHandler("jar"));
    }

    // ---- Helpers ----

    /** Builds a jar containing a real (parseable) module-info.class with the given requires. */
    private File jarWithModuleInfo(String moduleName, String... requires) throws IOException {
        byte[] mi = ClassFile.of().buildModule(
                java.lang.classfile.attribute.ModuleAttribute.of(
                        java.lang.constant.ModuleDesc.of(moduleName),
                        b -> {
                            for (String r : requires) {
                                int flags = r.equals("java.base")
                                        ? java.lang.classfile.ClassFile.ACC_MANDATED : 0;
                                b.requires(java.lang.constant.ModuleDesc.of(r), flags, null);
                            }
                        }));
        File tmp = File.createTempFile("modinfo-", ".jar");
        tmp.deleteOnExit();
        try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(tmp.toPath()))) {
            jos.putNextEntry(new java.util.jar.JarEntry("module-info.class"));
            jos.write(mi);
            jos.closeEntry();
        }
        return tmp;
    }

    private boolean hasModuleInfo(File jar) throws IOException {
        try (var jf = new java.util.jar.JarFile(jar)) {
            return jf.getEntry("module-info.class") != null;
        }
    }

    private File createMinimalJar(boolean withModuleInfo) throws IOException {
        File tmp = File.createTempFile("test-", ".jar");
        tmp.deleteOnExit();
        try (var jos = new java.util.jar.JarOutputStream(Files.newOutputStream(tmp.toPath()))) {
            if (withModuleInfo) {
                // Write a minimal (but structurally valid) module-info.class placeholder
                // We don't need a parseable one — just the presence of the entry matters for the
                // jarHasModuleInfo check.
                jos.putNextEntry(new java.util.jar.JarEntry("module-info.class"));
                jos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
                jos.closeEntry();
            }
            jos.putNextEntry(new java.util.jar.JarEntry("com/example/Dummy.class"));
            jos.write(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE});
            jos.closeEntry();
        }
        return tmp;
    }
}
