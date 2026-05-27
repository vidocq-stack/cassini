package io.vidocq.cassini.maven;

import io.vidocq.cassini.internal.gen.RuntimeAdapterGenerator;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

/**
 * Maven Mojo that pre-generates {@code <Class>$$CassiniAdapter} bytecode at build time.
 *
 * <h2>Scope and purpose</h2>
 * <p>The APT processor ({@code cassini-processor}) handles resource classes compiled from source
 * in the current build. This Mojo covers the complementary case: {@code @Path}/{@code @Provider}
 * classes arriving as pre-compiled {@code .class} files in external dependency JARs (e.g. the
 * Jakarta REST TCK jar, legacy/3rd-party archives) that APT cannot reach.</p>
 *
 * <p>Together with the APT processor they close AOT coverage: in a GraalVM native-image or
 * Project Leyden CDS environment the runtime Class-File generator cannot run, so every adapter
 * must exist at build time. {@link io.vidocq.cassini.internal.gen.AdapterRegistry} tries
 * {@code Class.forName(<class>$$CassiniAdapter)} first, so pre-generated adapters are picked up
 * automatically.</p>
 *
 * <h2>JPMS named-module rule</h2>
 * <p>Adapters must live in the <em>resource's package</em> so that the {@code <clinit>}
 * {@code privateLookupIn(ResourceClass, lookup)} can access private fields without requiring
 * app-side {@code opens}. For <em>plain classpath JARs</em> (no {@code module-info.class}) the
 * adapter is written into the project's output directory — no split-package problem.
 * For <em>named JPMS modules</em> (JAR contains {@code module-info.class}) writing the adapter
 * into the project's output would split the package across two modules (forbidden). The plugin
 * handles this via the {@code repackageModularDependencies} option:</p>
 * <ul>
 *   <li>{@code false} (default): FAIL THE BUILD with a clear, actionable message.</li>
 *   <li>{@code true}: repackage the dependency — produce a derived JAR that contains the
 *       original module's classes PLUS the generated adapters woven into the same
 *       module/package. The repackaged JAR is written to
 *       {@code target/cassini-repackaged/<groupId>-<artifactId>.jar}.</li>
 * </ul>
 *
 * <h2>Discovery scopes</h2>
 * <ul>
 *   <li>{@code project} (default): project's own {@code target/classes} only.</li>
 *   <li>{@code dependencies}: also scan dependency JARs (configurable
 *       {@code includeArtifacts}/{@code excludeArtifacts}).</li>
 * </ul>
 */
@Mojo(
        name = "generate",
        defaultPhase = LifecyclePhase.PROCESS_CLASSES,
        requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME,
        threadSafe = true
)
public class GenerateAdaptersMojo extends AbstractMojo {

    private static final Logger LOG = Logger.getLogger(GenerateAdaptersMojo.class.getName());
    private static final String ADAPTER_SUFFIX = RuntimeAdapterGenerator.ADAPTER_SUFFIX;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    /**
     * Discovery scope. Valid values: {@code project}, {@code dependencies}.
     * <ul>
     *   <li>{@code project}: scan the project's own {@code target/classes} only.</li>
     *   <li>{@code dependencies}: also scan compile+runtime dependency JARs.</li>
     * </ul>
     */
    @Parameter(property = "cassini.scope", defaultValue = "project")
    private String scope;

    /**
     * Artifact coordinates ({@code groupId:artifactId}) to include when scanning dependencies.
     * If empty, all dependency JARs are scanned. Only effective when scope={@code dependencies}.
     */
    @Parameter
    private List<String> includeArtifacts = new ArrayList<>();

    /**
     * Artifact coordinates ({@code groupId:artifactId}) to exclude from dependency scanning.
     * Only effective when scope={@code dependencies}.
     */
    @Parameter
    private List<String> excludeArtifacts = new ArrayList<>();

    /**
     * When {@code true}: if a dependency JAR is a named JPMS module (contains
     * {@code module-info.class}) and contains {@code @Path}/{@code @Provider} resource classes,
     * the plugin will <em>repackage</em> that dependency: produce a derived JAR containing the
     * original module's classes plus the generated adapters woven into the same module/package,
     * and write it to {@code target/cassini-repackaged/}. The user must then wire that derived
     * JAR onto the classpath/modulepath.
     *
     * <p>When {@code false} (default) the build FAILS with an actionable message when a named
     * JPMS module dependency with {@code @Path}/{@code @Provider} resources is detected.</p>
     */
    @Parameter(property = "cassini.repackageModularDependencies", defaultValue = "false")
    private boolean repackageModularDependencies;

    /**
     * Output directory where generated adapter {@code .class} files are written.
     * Defaults to the project's main output directory ({@code target/classes}).
     * Set to {@code ${project.build.testOutputDirectory}} when generating adapters
     * for test-scoped dependency JARs (e.g. the TCK archive).
     */
    @Parameter(defaultValue = "${project.build.outputDirectory}",
               property = "cassini.outputDirectory")
    private File outputDirectory;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        getLog().info("cassini:generate — pre-generating $$CassiniAdapters (scope=" + scope + ")");

        List<URL> classpathUrls = buildClasspathUrls();
        URLClassLoader cl = buildClassLoader(classpathUrls);

        try {
            int generated = 0;

            // Always scan project's own classes
            generated += scanDirectory(new File(outputDirectory, ""), cl, outputDirectory);

            // Optionally scan dependencies
            if ("dependencies".equalsIgnoreCase(scope)) {
                generated += scanDependencies(cl);
            }

            getLog().info("cassini:generate — " + generated + " adapter(s) generated.");
        } catch (MojoExecutionException | MojoFailureException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Unexpected error in cassini:generate", e);
        } finally {
            try { cl.close(); } catch (IOException ignored) {}
        }
    }

    // ---- Classpath building ----

    private List<URL> buildClasspathUrls() throws MojoExecutionException {
        List<URL> urls = new ArrayList<>();
        try {
            urls.add(outputDirectory.toURI().toURL());
            for (org.apache.maven.artifact.Artifact artifact : project.getArtifacts()) {
                if (artifact.getFile() != null) {
                    urls.add(artifact.getFile().toURI().toURL());
                }
            }
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to build plugin classpath", e);
        }
        return urls;
    }

    private URLClassLoader buildClassLoader(List<URL> urls) {
        return new URLClassLoader(urls.toArray(new URL[0]),
                Thread.currentThread().getContextClassLoader());
    }

    // ---- Project-classes scanning ----

    private int scanDirectory(File dir, URLClassLoader cl, File outputDir)
            throws MojoExecutionException {
        if (!dir.exists()) return 0;
        List<String> classNames = new ArrayList<>();
        collectClassNames(dir, dir, classNames);
        int count = 0;
        for (String className : classNames) {
            if (className.endsWith(ADAPTER_SUFFIX)) continue; // skip already-generated
            try {
                Class<?> cls = cl.loadClass(className);
                if (!isResource(cls)) continue;
                count += writeAdapter(cls, outputDir, null, null);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                getLog().warn("Cannot load class " + className + " — skipping: " + e.getMessage());
            }
        }
        return count;
    }

    private void collectClassNames(File root, File current, List<String> names) {
        File[] files = current.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                collectClassNames(root, f, names);
            } else if (f.getName().endsWith(".class") && !f.getName().contains("$")) {
                // Relative path from root → binary class name
                String rel = root.toURI().relativize(f.toURI()).getPath();
                String className = rel.replace('/', '.').replace('\\', '.').replace(".class", "");
                names.add(className);
            }
        }
    }

    // ---- Dependency scanning ----

    private int scanDependencies(URLClassLoader cl)
            throws MojoExecutionException, MojoFailureException {
        int count = 0;
        for (org.apache.maven.artifact.Artifact artifact : project.getArtifacts()) {
            String ga = artifact.getGroupId() + ":" + artifact.getArtifactId();
            if (!includeArtifacts.isEmpty() && !includeArtifacts.contains(ga)) continue;
            if (excludeArtifacts.contains(ga)) continue;
            File jar = artifact.getFile();
            if (jar == null || !jar.getName().endsWith(".jar") || !jar.exists()) continue;
            count += scanJar(jar, artifact, cl);
        }
        return count;
    }

    private int scanJar(File jar, org.apache.maven.artifact.Artifact artifact, URLClassLoader cl)
            throws MojoExecutionException, MojoFailureException {
        boolean isNamedModule = jarHasModuleInfo(jar);
        List<String> resourceClasses = new ArrayList<>();

        try (JarFile jf = new JarFile(jar)) {
            for (JarEntry entry : java.util.Collections.list(jf.entries())) {
                String name = entry.getName();
                if (!name.endsWith(".class")) continue;
                if (name.contains("$")) continue; // skip inner/anonymous
                if (name.equals("module-info.class")) continue;
                String className = name.replace('/', '.').replace(".class", "");
                if (className.endsWith(ADAPTER_SUFFIX)) continue;
                try {
                    Class<?> cls = cl.loadClass(className);
                    if (isResource(cls)) {
                        resourceClasses.add(className);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError e) {
                    getLog().debug("Cannot load " + className + " from " + jar.getName()
                            + ": " + e.getMessage());
                }
            }
        } catch (IOException e) {
            getLog().warn("Cannot scan JAR " + jar + ": " + e.getMessage());
            return 0;
        }

        if (resourceClasses.isEmpty()) return 0;

        if (isNamedModule) {
            String moduleName = detectModuleName(jar);
            if (!repackageModularDependencies) {
                // Build a representative message for the first class
                String fqn = resourceClasses.get(0);
                String pkg = fqn.contains(".") ? fqn.substring(0, fqn.lastIndexOf('.')) : "";
                String ga = artifact.getGroupId() + ":" + artifact.getArtifactId()
                        + ":" + artifact.getVersion();
                throw new MojoExecutionException(
                        "Resource class " + fqn + " is in named JPMS module '" + moduleName
                                + "' (dependency " + ga + "). "
                                + "Generating its adapter in this module would split-package '"
                                + pkg + "'. "
                                + "Set <repackageModularDependencies>true</repackageModularDependencies> "
                                + "(or -Dcassini.repackageModularDependencies=true) to repackage that "
                                + "module with the adapter woven in, OR keep it on the classpath, "
                                + "OR rely on the runtime fallback (non-AOT).");
            } else {
                // Repackage: derive a jar with adapters woven into the same module/package
                return repackageNamedModule(jar, artifact, resourceClasses, cl);
            }
        }

        // Plain classpath jar → write adapters into project output
        int count = 0;
        for (String className : resourceClasses) {
            try {
                Class<?> cls = cl.loadClass(className);
                count += writeAdapter(cls, outputDirectory, null, null);
            } catch (Exception e) {
                getLog().warn("Adapter generation failed for " + className + ": " + e.getMessage());
            }
        }
        return count;
    }

    // ---- Named-module repackaging ----

    private int repackageNamedModule(File originalJar, org.apache.maven.artifact.Artifact artifact,
                                     List<String> resourceClasses, URLClassLoader cl)
            throws MojoExecutionException {
        File repackDir = new File(project.getBuild().getDirectory(), "cassini-repackaged");
        repackDir.mkdirs();
        String jarName = artifact.getArtifactId() + "-" + artifact.getVersion() + "-cassini.jar";
        File repackJar = new File(repackDir, jarName);

        getLog().info("Repackaging named-module dependency " + artifact.getArtifactId()
                + " with woven adapters → " + repackJar.getAbsolutePath());

        // Generate adapter bytecodes
        List<String[]> adapterEntries = new ArrayList<>(); // [0]=entryName [1]=...stored separately
        List<byte[]> adapterBytecodes = new ArrayList<>();
        for (String className : resourceClasses) {
            try {
                Class<?> cls = cl.loadClass(className);
                byte[] bc = RuntimeAdapterGenerator.toBytecode(cls);
                String adapterName = (cls.getName() + ADAPTER_SUFFIX).replace('.', '/') + ".class";
                adapterEntries.add(new String[]{adapterName});
                adapterBytecodes.add(bc);
                getLog().info("  + woven adapter for " + cls.getName());
            } catch (Exception e) {
                getLog().warn("Cannot generate adapter for " + className + ": " + e.getMessage());
            }
        }

        // Write derived jar: original entries + adapter entries
        try (JarFile orig = new JarFile(originalJar);
             java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
                     Files.newOutputStream(repackJar.toPath()))) {
            // Copy original entries
            for (java.util.zip.ZipEntry entry : java.util.Collections.list(orig.entries())) {
                jos.putNextEntry(new java.util.jar.JarEntry(entry.getName()));
                try (InputStream in = orig.getInputStream(entry)) {
                    in.transferTo(jos);
                }
                jos.closeEntry();
            }
            // Append adapter class entries
            for (int i = 0; i < adapterEntries.size(); i++) {
                jos.putNextEntry(new java.util.jar.JarEntry(adapterEntries.get(i)[0]));
                jos.write(adapterBytecodes.get(i));
                jos.closeEntry();
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to repackage " + originalJar, e);
        }

        getLog().info("Repackaged jar written to: " + repackJar.getAbsolutePath());
        getLog().info("IMPORTANT: Replace the original dependency on the classpath/modulepath "
                + "with " + repackJar.getAbsolutePath() + " to use pre-generated adapters.");

        return adapterEntries.size();
    }

    // ---- Adapter writing (plain classpath path) ----

    /**
     * Generates adapter bytecode for {@code cls} via {@link RuntimeAdapterGenerator#toBytecode}
     * and writes the {@code .class} file to the appropriate output directory.
     *
     * @param cls        the resource class
     * @param outputDir  the root output directory (e.g. {@code target/classes})
     * @param jarFile    null when writing to outputDir; non-null when writing into a JAR stream
     * @param jos        JAR output stream (non-null only when jarFile != null)
     * @return 1 if written, 0 if skipped
     */
    private int writeAdapter(Class<?> cls, File outputDir,
                             JarFile jarFile, java.util.jar.JarOutputStream jos)
            throws MojoExecutionException {
        String adapterBinaryName = cls.getName() + ADAPTER_SUFFIX;
        String relPath = adapterBinaryName.replace('.', '/') + ".class";
        File dest = new File(outputDir, relPath);
        if (dest.exists()) {
            getLog().debug("Adapter already exists, skipping: " + dest);
            return 0;
        }
        try {
            byte[] bc = RuntimeAdapterGenerator.toBytecode(cls);
            dest.getParentFile().mkdirs();
            Files.write(dest.toPath(), bc);
            getLog().info("Generated adapter: " + relPath);
            return 1;
        } catch (Exception e) {
            getLog().warn("Failed to generate adapter for " + cls.getName() + ": " + e.getMessage());
            return 0;
        }
    }

    // ---- Helpers ----

    private boolean isResource(Class<?> cls) {
        return cls.isAnnotationPresent(jakarta.ws.rs.Path.class)
                || cls.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class);
    }

    private boolean jarHasModuleInfo(File jar) {
        try (JarFile jf = new JarFile(jar)) {
            return jf.getEntry("module-info.class") != null;
        } catch (IOException e) {
            return false;
        }
    }

    private String detectModuleName(File jar) {
        // Read module name from module-info.class using the Class-File API (JEP 484).
        try (JarFile jf = new JarFile(jar)) {
            java.util.zip.ZipEntry mi = jf.getEntry("module-info.class");
            if (mi == null) return "<unknown>";
            try (InputStream is = jf.getInputStream(mi)) {
                byte[] bytes = is.readAllBytes();
                var cf = java.lang.classfile.ClassFile.of().parse(bytes);
                // findAttribute(AttributeMapper) — use the mapper from Attributes registry
                var opt = cf.findAttribute(java.lang.classfile.Attributes.module());
                if (opt.isPresent()) {
                    // moduleName() -> ModuleEntry; .name() -> Utf8Entry; .stringValue() -> String
                    return opt.get().moduleName().name().stringValue();
                }
            }
        } catch (Exception e) {
            getLog().debug("Cannot detect module name from " + jar + ": " + e.getMessage());
        }
        return "<unknown>";
    }
}
