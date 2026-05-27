package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.spi.gen.ResourceAdapter;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Registry of {@link ResourceAdapter} instances, keyed by resource bean class.
 *
 * <h2>Lookup sequence (P1a)</h2>
 * <ol>
 *   <li><b>Cache hit</b>: if the class was already resolved (including the FAILED sentinel),
 *       return immediately.</li>
 *   <li><b>APT-generated class</b>: attempt
 *       {@code Class.forName(beanClass.getName() + "$$CassiniAdapter")} — succeeds when a
 *       compile-time APT adapter was placed on the classpath (P2, not yet).</li>
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
        public Object invoke(int methodId, Object target,
                             io.vidocq.cassini.spi.gen.InjectionSupport support) {
            throw new UnsupportedOperationException("SENTINEL");
        }
        @Override public String toString() { return "AdapterRegistry.SENTINEL"; }
    };

    /** Per-class cache: real adapter or SENTINEL for failed generation. */
    private static final ConcurrentHashMap<Class<?>, ResourceAdapter> CACHE =
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

        // 1. Try APT-generated class (P2 path; Class.forName fails silently here in P1)
        String aptName = beanClass.getName() + RuntimeAdapterGenerator.ADAPTER_SUFFIX;
        try {
            Class<?> aptClass = Class.forName(aptName, false, beanClass.getClassLoader());
            ResourceAdapter adapter = (ResourceAdapter) aptClass.getDeclaredConstructor().newInstance();
            ResourceAdapter existing = CACHE.putIfAbsent(beanClass, adapter);
            return Optional.of(existing != null ? existing : adapter);
        } catch (ClassNotFoundException ignored) {
            // expected in P1: no APT-generated adapter yet
        } catch (Exception e) {
            LOG.log(Level.WARNING, "APT adapter found but failed to instantiate for "
                    + beanClass.getName() + ": " + e.getMessage(), e);
            // fall through to runtime generation
        }

        // 2. Runtime Class-File API generator
        try {
            Class<?> adapterClass = RuntimeAdapterGenerator.generate(beanClass);
            ResourceAdapter adapter = (ResourceAdapter) adapterClass.getDeclaredConstructor().newInstance();
            ResourceAdapter existing = CACHE.putIfAbsent(beanClass, adapter);
            return Optional.of(existing != null ? existing : adapter);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Runtime adapter generation failed for "
                    + beanClass.getName() + " — using reflective fallback: " + e.getMessage(), e);
            CACHE.putIfAbsent(beanClass, SENTINEL);
            return Optional.empty();
        }
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
     * Removes the cached adapter for the given class (including SENTINEL entries).
     * Package-visible: used by unit tests to reset state between tests.
     */
    static void deregister(Class<?> beanClass) {
        CACHE.remove(beanClass);
    }
}
