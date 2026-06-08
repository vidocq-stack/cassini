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

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry of {@link ResourceAdapter} instances, keyed by resource bean class.
 *
 * <h2>Lookup sequence</h2>
 * <ol>
 *   <li><b>Cache hit</b>: if the class was already resolved (including the FAILED sentinel),
 *       return immediately.</li>
 *   <li><b>ServiceLoader-registered adapter</b>: a {@code <Class>$$CassiniAdapter} published as a
 *       {@link ServiceLoader} provider — either a module-path {@code provides
 *       io.vidocq.cassini.spi.gen.ResourceAdapter with <Class>$$CassiniAdapter} directive, or a
 *       classpath {@code META-INF/services} entry. Keyed by {@link ResourceAdapter#resourceClass()}.
 *       This is the only path that lets a strict JPMS application keep its resource package
 *       <em>closed</em> (no {@code opens}, no {@code exports}): the module system instantiates the
 *       provider from the encapsulated package, so cassini-core never reflects into it. The
 *       per-class {@code methodId} map is populated here (via {@link RuntimeAdapterGenerator#collectMethods})
 *       so {@code invoke()} runs through the adapter rather than reflective dispatch.</li>
 *   <li><b>APT/plugin pre-generated class</b>: attempt
 *       {@code Class.forName(beanClass.getName() + "$$CassiniAdapter")} — succeeds when a
 *       compile-time APT/plugin adapter is on the classpath (or in an exported module package).</li>
 *   <li><b>Runtime generator</b>: call {@link RuntimeAdapterGenerator#generate(Class)} using
 *       the Class-File API; instantiate and cache the result.</li>
 *   <li><b>Failure / SENTINEL</b>: if generation throws for a class, log and cache the
 *       {@link #SENTINEL} so subsequent lookups return empty without retrying. The
 *       reflective {@code FieldInjector} path then handles the request.</li>
 * </ol>
 *
 * <p><b>Thread safety:</b> the cache is a {@link ConcurrentHashMap}; multiple threads may
 * attempt generation concurrently for the same class — this is benign since adapters are
 * stateless and identical. The last writer wins.</p>
 */
public final class AdapterRegistry {

    private static final Logger LOG = Logger.getLogger(AdapterRegistry.class.getName());

    /**
     * Sentinel value stored in the cache for classes where generation failed.
     * Any entry equal to {@code SENTINEL} is treated as {@link Optional#empty()}.
     */
    private static final ResourceAdapter SENTINEL = new ResourceAdapter() {
        @Override
        public void injectFields(Object target, io.vidocq.cassini.spi.gen.InjectionSupport support,
                                 boolean injectParams) {
            throw new UnsupportedOperationException("SENTINEL — reflective fallback required");
        }
        @Override
        public Object invoke(int methodId, Object target, Object[] args) {
            throw new UnsupportedOperationException("SENTINEL");
        }
        @Override public String toString() { return "AdapterRegistry.SENTINEL"; }
    };

    /** Per-class cache: real adapter or SENTINEL for failed generation. */
    private static final ConcurrentHashMap<Class<?>, ResourceAdapter> CACHE =
            new ConcurrentHashMap<>();

    /**
     * Lazily built map of {@code resourceClass → ServiceLoader-registered adapter}. Populated once,
     * on first {@link #lookup} (or {@link #resetCounters}/test reset), from
     * {@code ServiceLoader.load(ResourceAdapter.class)} — i.e. module-path {@code provides} directives
     * and classpath {@code META-INF/services} entries. {@code null} until the first scan.
     */
    private static volatile Map<Class<?>, ResourceAdapter> serviceAdapters;

    /** Counter: number of adapters resolved via the APT/plugin pre-generated path (Class.forName). */
    private static final java.util.concurrent.atomic.AtomicInteger PRE_GENERATED_HITS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Counter: number of adapters resolved via the runtime Class-File generator. */
    private static final java.util.concurrent.atomic.AtomicInteger RUNTIME_GENERATED_HITS =
            new java.util.concurrent.atomic.AtomicInteger();

    /** Returns the number of adapters resolved via the pre-generated (APT/plugin) path. */
    public static int preGeneratedHits() { return PRE_GENERATED_HITS.get(); }

    /** Returns the number of adapters resolved via the runtime Class-File generator. */
    public static int runtimeGeneratedHits() { return RUNTIME_GENERATED_HITS.get(); }

    /** Resets both counters — intended for testing. */
    public static void resetCounters() {
        PRE_GENERATED_HITS.set(0);
        RUNTIME_GENERATED_HITS.set(0);
    }

    /**
     * Per-class method-id map: maps (beanClass → (method → methodId)).
     * Populated by {@link RuntimeAdapterGenerator#collectMethods} at generation time.
     */
    private static final ConcurrentHashMap<Class<?>, Map<Method, Integer>> METHOD_IDS =
            new ConcurrentHashMap<>();

    private AdapterRegistry() {}

    /**
     * Looks up a {@link ResourceAdapter} for the given resource bean class.
     *
     * <p>See class-level Javadoc for the full lookup sequence.</p>
     *
     * @param beanClass the JAX-RS resource class (concrete class, not a CDI proxy)
     * @return an adapter if one could be resolved, otherwise empty (triggers reflective fallback)
     */
    public static Optional<ResourceAdapter> lookup(Class<?> beanClass) {
        ResourceAdapter cached = CACHE.get(beanClass);
        if (cached != null) {
            return cached == SENTINEL ? Optional.empty() : Optional.of(cached);
        }

        // 1. ServiceLoader-registered adapter (module-path `provides` / classpath META-INF/services).
        //    The module system instantiates the provider even from a CLOSED package, so a strict
        //    JPMS app needs neither `opens` nor `exports`. Populate the methodId map here so the
        //    adapter's invoke() is used (no reflective dispatch into the closed package).
        ResourceAdapter svc = serviceAdapters().get(beanClass);
        if (svc != null) {
            METHOD_IDS.computeIfAbsent(beanClass, RuntimeAdapterGenerator::collectMethods);
            ResourceAdapter existing = CACHE.putIfAbsent(beanClass, svc);
            PRE_GENERATED_HITS.incrementAndGet();
            LOG.fine("ServiceLoader-registered adapter used for " + beanClass.getName());
            return Optional.of(existing != null ? existing : svc);
        }

        // 2. Try APT/plugin pre-generated class (Class.forName succeeds when the adapter
        //    was written to disk by the APT processor or the cassini-maven-plugin).
        String aptName = beanClass.getName() + RuntimeAdapterGenerator.ADAPTER_SUFFIX;
        try {
            Class<?> aptClass = Class.forName(aptName, false, beanClass.getClassLoader());
            ResourceAdapter adapter = (ResourceAdapter) aptClass.getDeclaredConstructor().newInstance();
            ResourceAdapter existing = CACHE.putIfAbsent(beanClass, adapter);
            PRE_GENERATED_HITS.incrementAndGet();
            LOG.fine("Pre-generated adapter used for " + beanClass.getName());
            return Optional.of(existing != null ? existing : adapter);
        } catch (ClassNotFoundException ignored) {
            // No pre-generated adapter; fall through to runtime generator.
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Pre-generated adapter found but failed to instantiate for "
                    + beanClass.getName() + ": " + e.getMessage(), e);
            // fall through to runtime generation
        }

        // 3. Runtime Class-File API generator
        try {
            Class<?> adapterClass = RuntimeAdapterGenerator.generate(beanClass);
            ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();
            ResourceAdapter existing = CACHE.putIfAbsent(beanClass, adapter);
            RUNTIME_GENERATED_HITS.incrementAndGet();
            LOG.fine("Runtime-generated adapter used for " + beanClass.getName());
            return Optional.of(existing != null ? existing : adapter);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Runtime adapter generation failed for "
                    + beanClass.getName() + " — using reflective fallback: " + e.getMessage(), e);
            CACHE.putIfAbsent(beanClass, SENTINEL);
            return Optional.empty();
        }
    }

    /**
     * Returns the stable methodId for the given resource method within the adapter for
     * {@code beanClass}, or {@code -1} if the method was not assigned an id (e.g. the
     * adapter was generated before this method was discovered, or generation failed).
     *
     * @param beanClass  the resource bean class (NOT the CDI proxy class)
     * @param method     the resource method
     * @return the method id, or -1 if not found
     */
    public static int methodId(Class<?> beanClass, Method method) {
        Map<Method, Integer> ids = METHOD_IDS.get(beanClass);
        if (ids == null) return -1;
        Integer id = ids.get(method);
        return id == null ? -1 : id;
    }

    /**
     * Registers an adapter for the given class.
     * Package-visible: intended for unit tests (seam testing) and the P1 runtime generator.
     *
     * @param beanClass the resource class
     * @param adapter   the adapter to register
     */
    static void register(Class<?> beanClass, ResourceAdapter adapter) {
        CACHE.put(beanClass, adapter);
    }

    /**
     * Registers the method-id map for a resource class.
     * Called by {@link RuntimeAdapterGenerator} after generation.
     */
    static void registerMethodIds(Class<?> beanClass, Map<Method, Integer> ids) {
        METHOD_IDS.put(beanClass, ids);
    }

    /**
     * Removes the cached adapter for the given class (including SENTINEL entries).
     * Package-visible: used by unit tests to reset state between tests.
     */
    static void deregister(Class<?> beanClass) {
        CACHE.remove(beanClass);
        METHOD_IDS.remove(beanClass);
    }

    /**
     * Returns the {@code resourceClass → adapter} map of {@link ServiceLoader}-registered adapters,
     * building it once on first use (double-checked locking). Empty when no provider is registered —
     * the normal case on the classpath and for the TCK, so the lookup transparently falls through to
     * {@code Class.forName} / the runtime generator.
     */
    private static Map<Class<?>, ResourceAdapter> serviceAdapters() {
        Map<Class<?>, ResourceAdapter> m = serviceAdapters;
        if (m == null) {
            synchronized (AdapterRegistry.class) {
                m = serviceAdapters;
                if (m == null) {
                    m = loadServiceAdapters();
                    serviceAdapters = m;
                }
            }
        }
        return m;
    }

    /**
     * Scans {@code ServiceLoader.load(ResourceAdapter.class)} and indexes each provider by its
     * {@link ResourceAdapter#resourceClass()}. Providers that fail to instantiate (or advertise no
     * resource class) are skipped — they fall back to the {@code Class.forName} / runtime path. Never
     * throws: a malformed service file degrades to a partial (possibly empty) map.
     */
    private static Map<Class<?>, ResourceAdapter> loadServiceAdapters() {
        Map<Class<?>, ResourceAdapter> map = new HashMap<>();
        Iterator<ResourceAdapter> it = ServiceLoader.load(ResourceAdapter.class).iterator();
        while (true) {
            ResourceAdapter a;
            try {
                if (!it.hasNext()) break;
                a = it.next();
            } catch (java.util.ServiceConfigurationError e) {
                // Iterator state is undefined after a load error — stop scanning; the remaining
                // classes resolve through the Class.forName / runtime-generator fallback.
                LOG.log(Level.FINE, "ResourceAdapter ServiceLoader scan stopped early: " + e.getMessage(), e);
                break;
            }
            Class<?> rc;
            try {
                rc = a.resourceClass();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Skipping ResourceAdapter " + a.getClass().getName()
                        + " (resourceClass() failed): " + t.getMessage(), t);
                continue;
            }
            if (rc != null) {
                map.putIfAbsent(rc, a);
            }
        }
        if (!map.isEmpty()) {
            LOG.fine("AdapterRegistry: " + map.size() + " ServiceLoader-registered adapter(s)");
        }
        return map;
    }

    /**
     * Discards the cached {@link ServiceLoader} adapter map so the next lookup rebuilds it.
     * Package-visible: used by unit tests that register/unregister services between cases.
     */
    static void resetServiceAdapters() {
        serviceAdapters = null;
    }
}
