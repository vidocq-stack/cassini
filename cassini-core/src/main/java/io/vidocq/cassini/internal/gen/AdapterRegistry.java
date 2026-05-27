package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.spi.gen.ResourceAdapter;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of {@link ResourceAdapter} instances, keyed by resource bean class.
 *
 * <p><b>P0 behaviour:</b> {@link #lookup} always returns {@link Optional#empty()},
 * so 100% of requests go through the existing reflective path ({@code FieldInjector} /
 * {@code ParamExtractor} / {@code Method.invoke}). No behaviour change.</p>
 *
 * <p><b>P1 extension point:</b> the concurrent cache is already allocated so
 * P1 can populate it from the runtime Class-File API generator:
 * <pre>{@code
 *   AdapterRegistry.register(MyResource.class, runtimeGeneratedAdapter);
 * }</pre>
 * The lookup sequence will then be:
 * <ol>
 *   <li>Cache hit → return cached adapter.</li>
 *   <li>APT-generated: {@code Class.forName(beanClass.getName() + "$$CassiniAdapter")} →
 *       instantiate, cache, return.</li>
 *   <li>Runtime generator → generate via Class-File API, cache, return.</li>
 *   <li>Otherwise → {@link Optional#empty()} → reflective fallback.</li>
 * </ol>
 * </p>
 */
public final class AdapterRegistry {

    /** Per-class cache for P1+ (pre-allocated, empty in P0). */
    private static final ConcurrentHashMap<Class<?>, ResourceAdapter> CACHE =
            new ConcurrentHashMap<>();

    private AdapterRegistry() {}

    /**
     * Looks up a {@link ResourceAdapter} for the given resource bean class.
     *
     * <p>P0 stub: always returns {@link Optional#empty()}. P1 will add the
     * Class-File API generator path before returning empty.</p>
     *
     * @param beanClass the JAX-RS resource class
     * @return an adapter if one is registered, otherwise empty
     */
    public static Optional<ResourceAdapter> lookup(Class<?> beanClass) {
        // P0: no adapters exist yet → always fall back to reflective path.
        // P1 will add: cache check + APT Class.forName + runtime generation.
        ResourceAdapter cached = CACHE.get(beanClass);
        if (cached != null) return Optional.of(cached);
        return Optional.empty();
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
     * Removes the cached adapter for the given class.
     * Package-visible: used by unit tests to reset state between tests.
     */
    static void deregister(Class<?> beanClass) {
        CACHE.remove(beanClass);
    }
}
