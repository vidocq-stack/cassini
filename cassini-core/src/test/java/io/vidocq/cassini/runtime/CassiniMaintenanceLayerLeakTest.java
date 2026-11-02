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
package io.vidocq.cassini.runtime;

import io.vidocq.cassini.internal.json.CassiniJsonbReaderWriter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.ref.WeakReference;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What Cassini keeps in statics must not outlive the application layer it served: the Vidocq dev mode boots a fresh
 * layer on every hot reload, in the same JVM, while Cassini stays in the parent layer. The host calls
 * {@link CassiniMaintenance#resetDiscoveryCaches()} when a layer stops; after it, the layer must be collectable
 * (static audit, vidocq-workspace#40 item 25).
 */
class CassiniMaintenanceLayerLeakTest {

    private static final String PACKAGE = "io.vidocq.cassini.runtime.";
    private static final String BINDING = "LeakProbeBinding";

    /**
     * The default JSON-B instance of the built-in reader/writer is created by the first JSON request, under the
     * context class loader of the layer, so its index holds the generated bindings of that layer: it must be dropped.
     */
    @Test
    void theLayerIsCollectableOnceTheMaintenanceResetRan(@TempDir Path dir) throws Exception {
        CassiniMaintenance.resetDiscoveryCaches(); // no default instance of an earlier test is reused
        WeakReference<ClassLoader> layer = serveAnEntityOfADiscardedLayer(dir);

        CassiniMaintenance.resetDiscoveryCaches();

        assertNull(collected(layer), "the layer stays reachable after the maintenance reset");
    }

    /** A module of the host can add what it caches to the reset. */
    @Test
    void aRegisteredHookRunsOnEveryReset() {
        var runs = new AtomicInteger();
        CassiniMaintenance.onReset(runs::incrementAndGet);

        CassiniMaintenance.resetDiscoveryCaches();
        CassiniMaintenance.resetDiscoveryCaches();

        assertEquals(2, runs.get());
    }

    private static WeakReference<ClassLoader> serveAnEntityOfADiscardedLayer(Path dir) throws Exception {
        URLClassLoader loader = layerLoader(dir);
        Class<?> probe = loader.loadClass(LeakProbe.class.getName());
        Object entity = probe.getDeclaredConstructor().newInstance();
        var out = new ByteArrayOutputStream();
        ClassLoader before = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        try {
            new CassiniJsonbReaderWriter().writeTo(entity, probe, probe, new Annotation[0],
                    MediaType.APPLICATION_JSON_TYPE, new MultivaluedHashMap<>(), out);
        } finally {
            Thread.currentThread().setContextClassLoader(before);
        }
        assertEquals("{\"name\":\"probe\"}", out.toString(), "the generated binding of the layer wrote the entity");
        return new WeakReference<>(loader);
    }

    /**
     * A stand-in for an application layer: a loader that defines the entity and its generated JSON-B binding itself
     * (child first) and lists the binding in a services file, as the layer of a Vidocq application does.
     */
    private static URLClassLoader layerLoader(Path dir) throws Exception {
        Path jar = dir.resolve("layer.jar");
        try (var jarOut = new JarOutputStream(Files.newOutputStream(jar))) {
            copy(jarOut, "LeakProbe");
            copy(jarOut, BINDING);
            jarOut.putNextEntry(new JarEntry("META-INF/services/io.vidocq.champollion.jsonb.spi.JsonbBinding"));
            jarOut.write((PACKAGE + BINDING).getBytes(StandardCharsets.UTF_8));
            jarOut.closeEntry();
        }
        return new URLClassLoader(new URL[] {jar.toUri().toURL()}, CassiniMaintenanceLayerLeakTest.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.startsWith(LeakProbe.class.getName())) {
                    return super.loadClass(name, resolve);
                }
                synchronized (getClassLoadingLock(name)) {
                    Class<?> loaded = findLoadedClass(name);
                    return loaded != null ? loaded : findClass(name);
                }
            }
        };
    }

    /** By name: the module of this test does not read the JSON-B SPI, so the binding is never loaded in it. */
    private static void copy(JarOutputStream jarOut, String simpleName) throws Exception {
        try (InputStream in = LeakProbe.class.getResourceAsStream(simpleName + ".class")) {
            jarOut.putNextEntry(new JarEntry((PACKAGE + simpleName).replace('.', '/') + ".class"));
            in.transferTo(jarOut);
            jarOut.closeEntry();
        }
    }

    /** {@code null} once the loader is collected, the loader itself while something still holds it. */
    private static ClassLoader collected(WeakReference<ClassLoader> loader) {
        for (int i = 0; i < 40 && loader.get() != null; i++) {
            System.gc();
            LockSupport.parkNanos(25_000_000L);
        }
        return loader.get();
    }
}
