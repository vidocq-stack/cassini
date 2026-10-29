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

import io.vidocq.cassini.spi.gen.ResourceAdapter;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Resource fixtures compiled by javac in a test, through the annotation processor or without it. */
public final class CompiledFixtures {

    private static final String PROCESSOR = "io.vidocq.cassini.processor.CassiniResourceProcessor";

    private CompiledFixtures() {
    }

    /**
     * Compiles {@code sources} (simple name → source, {@code %s} standing for the package) into {@code dir/out},
     * through the processor when {@code processor}, and returns that directory.
     */
    public static Path compile(Path dir, String pkg, Map<String, String> sources, boolean processor)
            throws Exception {
        var src = Files.createDirectories(dir.resolve("src"));
        var out = Files.createDirectories(dir.resolve("out"));
        var files = new ArrayList<File>();
        for (var entry : sources.entrySet()) {
            var file = src.resolve(entry.getKey() + ".java");
            Files.writeString(file, entry.getValue().formatted(pkg));
            files.add(file.toFile());
        }
        var classPath = String.join(File.pathSeparator, locationOf(jakarta.ws.rs.Path.class.getName()),
                locationOf(ResourceAdapter.class.getName()), locationOf(PROCESSOR));
        var args = new ArrayList<>(List.of("-d", out.toString(), "-classpath", classPath));
        if (processor) {
            args.addAll(List.of("-processor", PROCESSOR, "-processorpath", classPath));
        } else {
            args.add("-proc:none");
        }
        files.forEach(f -> args.add(f.getPath()));
        var log = new StringWriter();
        var javac = ToolProvider.findFirst("javac").orElseThrow();
        int status = javac.run(new PrintWriter(log), new PrintWriter(log), args.toArray(String[]::new));
        assertEquals(0, status, () -> "compilation failed:\n" + log);
        return out;
    }

    /** A loader of the classes in {@code out}, delegating the rest to the test's own. */
    public static ClassLoader loaderOf(Path out) throws Exception {
        return new URLClassLoader(new URL[]{out.toUri().toURL()}, CompiledFixtures.class.getClassLoader());
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
