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
import io.vidocq.cassini.internal.gen.RuntimeRoutesGenerator;
import io.vidocq.cassini.maven.fixture.LocatorJarResource;
import io.vidocq.cassini.maven.fixture.NotAResource;
import io.vidocq.cassini.maven.fixture.PlainJarResource;
import io.vidocq.cassini.maven.fixture.RichJarResource;
import io.vidocq.cassini.spi.gen.RouteDescriptor;
import io.vidocq.cassini.spi.gen.RouteProvider;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.ModuleAttribute;
import java.lang.constant.ModuleDesc;
import java.lang.constant.PackageDesc;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GenerateAdaptersMojo#execute()} as Maven runs it: the project's own classes, the
 * dependency jars of the {@code dependencies} scope with their filters, and the named-module
 * dependencies, which fail the build or are repackaged (and sealed) with their adapters and routes.
 */
class GenerateAdaptersMojoExecuteTest {

    private static final String GROUP = "com.acme";
    private static final String LIB = "lib";
    private static final String FIXTURE_PACKAGE = "io/vidocq/cassini/maven/fixture";
    private static final String CASSINI_API = "io.vidocq.cassini.api";
    private static final String MODULE = "com.acme.lib";

    // ---- project scope ----

    @Test
    void generatesAnAdapterForEachResourceOfTheProjectAndKeepsExistingOnes(@TempDir File dir) throws Exception {
        File classes = new File(dir, "classes");
        copyClass(PlainJarResource.class, classes);
        copyClass(RichJarResource.class, classes);
        copyClass(NotAResource.class, classes);
        // A class file whose name does not match its content cannot be loaded: it is skipped.
        File misplaced = new File(classes, "other/Misplaced.class");
        misplaced.getParentFile().mkdirs();
        Files.write(misplaced.toPath(), classBytes(NotAResource.class));

        newMojo(dir, classes, Map.of()).execute();

        File plain = adapterFile(classes, PlainJarResource.class);
        assertTrue(plain.isFile(), "adapter of PlainJarResource");
        assertTrue(adapterFile(classes, RichJarResource.class).isFile(), "adapter of RichJarResource");
        assertFalse(adapterFile(classes, NotAResource.class).exists(), "no adapter for a non-resource");
        assertArrayEquals(RuntimeAdapterGenerator.toBytecode(PlainJarResource.class),
                Files.readAllBytes(plain.toPath()));

        // A second run leaves an adapter already there untouched.
        Files.write(plain.toPath(), new byte[]{1, 2, 3});
        newMojo(dir, classes, Map.of()).execute();
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(plain.toPath()));
    }

    @Test
    void aMissingOutputDirectoryGeneratesNothing(@TempDir File dir) throws Exception {
        File classes = new File(dir, "absent");
        newMojo(dir, classes, Map.of()).execute();
        assertFalse(classes.exists());
    }

    // ---- dependencies scope ----

    @Test
    void theProjectScopeIgnoresTheDependencies(@TempDir File dir) throws Exception {
        File classes = mkdirs(new File(dir, "classes"));
        var mojo = newMojo(dir, classes, Map.of());
        project(mojo).setArtifacts(Set.of(artifact(LIB, plainJar(new File(dir, "lib.jar"), RichJarResource.class))));
        mojo.execute();
        assertFalse(adapterFile(classes, RichJarResource.class).exists());
    }

    @Test
    void includeArtifactsLimitsTheScannedDependencies(@TempDir File dir) throws Exception {
        File classes = mkdirs(new File(dir, "classes"));
        var mojo = newMojo(dir, classes, Map.of("scope", "dependencies",
                "includeArtifacts", List.of(GROUP + ":" + LIB)));
        project(mojo).setArtifacts(artifacts(
                artifact(LIB, plainJar(new File(dir, "lib.jar"), RichJarResource.class)),
                artifact("other", plainJar(new File(dir, "other.jar"), PlainJarResource.class))));

        mojo.execute();

        assertTrue(adapterFile(classes, RichJarResource.class).isFile());
        assertFalse(adapterFile(classes, PlainJarResource.class).exists(), "not in includeArtifacts");
    }

    @Test
    void excludedUnresolvedAndNonJarDependenciesAreSkipped(@TempDir File dir) throws Exception {
        File classes = mkdirs(new File(dir, "classes"));
        var mojo = newMojo(dir, classes, Map.of("scope", "DEPENDENCIES",
                "excludeArtifacts", List.of(GROUP + ":other")));
        File pom = new File(dir, "parent.pom");
        Files.writeString(pom.toPath(), "<project/>");
        project(mojo).setArtifacts(artifacts(
                artifact(LIB, plainJar(new File(dir, "lib.jar"), RichJarResource.class)),
                artifact("other", plainJar(new File(dir, "other.jar"), PlainJarResource.class)),
                artifact("unresolved", null),
                artifact("parent", pom),
                artifact("gone", new File(dir, "gone.jar"))));

        mojo.execute();

        assertTrue(adapterFile(classes, RichJarResource.class).isFile());
        assertFalse(adapterFile(classes, PlainJarResource.class).exists(), "excluded");
    }

    @Test
    void aJarThatIsNotAZipOrHoldsNoResourceYieldsNoAdapter(@TempDir File dir) throws Exception {
        var mojo = newMojo(dir, mkdirs(new File(dir, "classes")), Map.of());
        File corrupt = new File(dir, "corrupt.jar");
        Files.writeString(corrupt.toPath(), "not a zip");
        File plainOnly = plainJar(new File(dir, "plain.jar"), NotAResource.class);
        try (var cl = loader(corrupt, plainOnly)) {
            assertEquals(0, mojo.scanJar(corrupt, artifact(LIB, corrupt), cl));
            assertEquals(0, mojo.scanJar(plainOnly, artifact(LIB, plainOnly), cl));
        }
    }

    // ---- named-module dependencies ----

    @Test
    void aNamedModuleDependencyWithResourcesFailsTheBuildByDefault(@TempDir File dir) throws Exception {
        var mojo = newMojo(dir, mkdirs(new File(dir, "classes")), Map.of("scope", "dependencies"));
        project(mojo).setArtifacts(artifacts(artifact(LIB,
                modularJar(new File(dir, "lib.jar"), moduleInfo(false), RichJarResource.class))));

        var failure = assertThrows(MojoExecutionException.class, mojo::execute);

        assertTrue(failure.getMessage().contains("named Java module '" + MODULE + "'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("repackageModularDependencies"), failure.getMessage());
        assertTrue(failure.getMessage().contains(GROUP + ":" + LIB + ":1.0"), failure.getMessage());
    }

    @Test
    void aRepackagedNamedModuleCarriesItsAdaptersRoutesAndReadsCassiniApi(@TempDir File dir) throws Exception {
        var mojo = newMojo(dir, mkdirs(new File(dir, "classes")), Map.of("scope", "dependencies",
                "repackageModularDependencies", true));
        project(mojo).setArtifacts(artifacts(artifact(LIB, modularJar(new File(dir, "lib.jar"), moduleInfo(false),
                RichJarResource.class, LocatorJarResource.class, NotAResource.class))));

        mojo.execute();

        File repackaged = new File(dir, "target/cassini-repackaged/lib-1.0-cassini.jar");
        assertTrue(repackaged.isFile());
        Set<String> entries = entries(repackaged);
        assertTrue(entries.contains(classEntry(NotAResource.class)), "original classes are kept");
        for (Class<?> resource : List.of(RichJarResource.class, LocatorJarResource.class)) {
            assertTrue(entries.contains(classEntry(resource.getName() + RuntimeAdapterGenerator.ADAPTER_SUFFIX)));
            assertTrue(entries.contains(classEntry(RuntimeRoutesGenerator.routesClassName(resource))));
        }

        ModuleAttribute module = moduleOf(repackaged);
        assertEquals(1, countRequires(module, CASSINI_API));
        assertEquals(Set.of(FIXTURE_PACKAGE, "com/acme/api"), exportedPackages(module));
        assertTrue(module.provides().isEmpty(), "an unsealed module publishes no provider");

        try (var cl = loader(repackaged)) {
            Map<String, RouteDescriptor> routes = routesOf(cl, RichJarResource.class).routes().stream()
                    .collect(Collectors.toMap(RouteDescriptor::methodName, Function.identity()));
            assertEquals(Set.of("byId", "create"), routes.keySet());
            RouteDescriptor byId = routes.get("byId");
            assertEquals(RichJarResource.class, byId.beanClass());
            assertEquals("GET", byId.httpMethod());
            assertTrue(byId.pathTemplate().endsWith("{id}"), byId.pathTemplate());
            assertArrayEquals(new String[]{"int", "java.lang.String[]"}, byId.paramTypeNames());
            assertArrayEquals(new String[]{"text/plain"}, byId.produces());
            RouteDescriptor create = routes.get("create");
            assertEquals("POST", create.httpMethod());
            assertArrayEquals(new String[]{"application/json"}, create.consumes());
            assertEquals(2, create.produces().length);

            RouteProvider locator = routesOf(cl, LocatorJarResource.class);
            assertTrue(locator.hasLocators());
            assertTrue(locator.routes().isEmpty());
            assertEquals(LocatorJarResource.class, locator.resourceClass());
        }
    }

    @Test
    void aModuleThatAlreadyReadsCassiniApiIsNotGivenASecondRequires(@TempDir File dir) throws Exception {
        var mojo = newMojo(dir, mkdirs(new File(dir, "classes")), Map.of("scope", "dependencies",
                "repackageModularDependencies", true));
        project(mojo).setArtifacts(artifacts(artifact(LIB,
                modularJar(new File(dir, "lib.jar"), moduleInfo(true), RichJarResource.class))));

        mojo.execute();

        assertEquals(1, countRequires(moduleOf(new File(dir, "target/cassini-repackaged/lib-1.0-cassini.jar")),
                CASSINI_API));
    }

    @Test
    void aSealedModuleClosesItsResourcePackageAndProvidesTheWovenClasses(@TempDir File dir) throws Exception {
        var mojo = newMojo(dir, mkdirs(new File(dir, "classes")), Map.of("scope", "dependencies",
                "repackageModularDependencies", true, "sealModules", true));
        project(mojo).setArtifacts(artifacts(artifact(LIB, modularJar(new File(dir, "lib.jar"), moduleInfo(false),
                RichJarResource.class, PlainJarResource.class))));

        mojo.execute();

        ModuleAttribute module = moduleOf(new File(dir, "target/cassini-repackaged/lib-1.0-cassini.jar"));
        assertEquals(1, countRequires(module, CASSINI_API));
        assertEquals(Set.of("com/acme/api"), exportedPackages(module), "the resource package is no longer exported");
        Map<String, Set<String>> provides = module.provides().stream().collect(Collectors.toMap(
                p -> p.provides().asInternalName(),
                p -> p.providesWith().stream().map(c -> c.asInternalName()).collect(Collectors.toSet())));
        assertEquals(Set.of(
                        internal(RichJarResource.class.getName() + RuntimeAdapterGenerator.ADAPTER_SUFFIX),
                        internal(PlainJarResource.class.getName() + RuntimeAdapterGenerator.ADAPTER_SUFFIX)),
                provides.get("io/vidocq/cassini/spi/gen/ResourceAdapter"));
        assertEquals(Set.of(
                        internal(RuntimeRoutesGenerator.routesClassName(RichJarResource.class)),
                        internal(RuntimeRoutesGenerator.routesClassName(PlainJarResource.class))),
                provides.get("io/vidocq/cassini/spi/gen/RouteProvider"));
    }

    @Test
    void sealingSkipsCassiniInfrastructureModules(@TempDir File dir) throws Exception {
        File classes = mkdirs(new File(dir, "classes"));
        var mojo = newMojo(dir, classes, Map.of("scope", "dependencies",
                "repackageModularDependencies", true, "sealModules", true));
        project(mojo).setArtifacts(artifacts(artifact(LIB,
                modularJar(new File(dir, "lib.jar"), moduleInfo(true), RichJarResource.class))));

        mojo.execute();

        assertFalse(new File(dir, "target/cassini-repackaged").exists());
        assertFalse(adapterFile(classes, RichJarResource.class).exists());
    }

    // ---- helpers ----

    private static GenerateAdaptersMojo newMojo(File dir, File classes, Map<String, Object> settings) throws Exception {
        var mojo = new GenerateAdaptersMojo();
        var project = new MavenProject();
        project.getBuild().setDirectory(new File(dir, "target").getAbsolutePath());
        project.setArtifacts(Set.of());
        set(mojo, "project", project);
        set(mojo, "outputDirectory", classes);
        set(mojo, "scope", "project");
        for (var setting : settings.entrySet()) {
            set(mojo, setting.getKey(), setting.getValue());
        }
        return mojo;
    }

    private static void set(GenerateAdaptersMojo mojo, String name, Object value) throws Exception {
        var field = GenerateAdaptersMojo.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(mojo, value);
    }

    private static MavenProject project(GenerateAdaptersMojo mojo) throws Exception {
        var field = GenerateAdaptersMojo.class.getDeclaredField("project");
        field.setAccessible(true);
        return (MavenProject) field.get(mojo);
    }

    private static Set<Artifact> artifacts(Artifact... artifacts) {
        return new LinkedHashSet<>(List.of(artifacts));
    }

    private static Artifact artifact(String artifactId, File file) {
        var artifact = new DefaultArtifact(GROUP, artifactId, "1.0", "compile", "jar", null,
                new DefaultArtifactHandler("jar"));
        artifact.setFile(file);
        return artifact;
    }

    private static File mkdirs(File dir) {
        dir.mkdirs();
        return dir;
    }

    private static String classEntry(Class<?> type) {
        return classEntry(type.getName());
    }

    private static String classEntry(String binaryName) {
        return internal(binaryName) + ".class";
    }

    private static String internal(String binaryName) {
        return binaryName.replace('.', '/');
    }

    private static File adapterFile(File classes, Class<?> resource) {
        return new File(classes, classEntry(resource.getName() + RuntimeAdapterGenerator.ADAPTER_SUFFIX));
    }

    private static byte[] classBytes(Class<?> type) throws IOException {
        try (var in = type.getClassLoader().getResourceAsStream(classEntry(type))) {
            assertNotNull(in, type.getName());
            return in.readAllBytes();
        }
    }

    private static void copyClass(Class<?> type, File classes) throws IOException {
        File dest = new File(classes, classEntry(type));
        dest.getParentFile().mkdirs();
        Files.write(dest.toPath(), classBytes(type));
    }

    private static File plainJar(File jar, Class<?>... classes) throws IOException {
        return modularJar(jar, null, classes);
    }

    private static File modularJar(File jar, byte[] moduleInfo, Class<?>... classes) throws IOException {
        try (var jos = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            if (moduleInfo != null) {
                jos.putNextEntry(new java.util.jar.JarEntry("module-info.class"));
                jos.write(moduleInfo);
                jos.closeEntry();
            }
            for (Class<?> type : classes) {
                jos.putNextEntry(new java.util.jar.JarEntry(classEntry(type)));
                jos.write(classBytes(type));
                jos.closeEntry();
            }
        }
        return jar;
    }

    /** module com.acme.lib, exporting the fixture package and com.acme.api, reading cassini.api or not. */
    private static byte[] moduleInfo(boolean readsCassiniApi) {
        return ClassFile.of().buildModule(ModuleAttribute.of(ModuleDesc.of(MODULE), b -> {
            b.requires(ModuleDesc.of("java.base"), ClassFile.ACC_MANDATED, null);
            b.requires(ModuleDesc.of("jakarta.ws.rs"), 0, null);
            if (readsCassiniApi) {
                b.requires(ModuleDesc.of(CASSINI_API), 0, null);
            }
            b.exports(PackageDesc.ofInternalName(FIXTURE_PACKAGE), 0);
            b.exports(PackageDesc.ofInternalName("com/acme/api"), 0);
        }));
    }

    private static Set<String> entries(File jar) throws IOException {
        try (var jf = new JarFile(jar)) {
            return jf.stream().map(java.util.zip.ZipEntry::getName).collect(Collectors.toSet());
        }
    }

    private static ModuleAttribute moduleOf(File jar) throws IOException {
        try (var jf = new JarFile(jar); var in = jf.getInputStream(jf.getEntry("module-info.class"))) {
            return ClassFile.of().parse(in.readAllBytes()).findAttribute(Attributes.module()).orElseThrow();
        }
    }

    private static long countRequires(ModuleAttribute module, String name) {
        return module.requires().stream().filter(r -> r.requires().name().stringValue().equals(name)).count();
    }

    private static Set<String> exportedPackages(ModuleAttribute module) {
        return module.exports().stream().map(e -> e.exportedPackage().name().stringValue())
                .collect(Collectors.toSet());
    }

    private static URLClassLoader loader(File... jars) throws IOException {
        List<URL> urls = new ArrayList<>();
        for (File jar : jars) urls.add(jar.toURI().toURL());
        return new URLClassLoader(urls.toArray(URL[]::new), GenerateAdaptersMojoExecuteTest.class.getClassLoader());
    }

    private static RouteProvider routesOf(ClassLoader cl, Class<?> resource) throws Exception {
        return (RouteProvider) cl.loadClass(RuntimeRoutesGenerator.routesClassName(resource))
                .getConstructor().newInstance();
    }
}
