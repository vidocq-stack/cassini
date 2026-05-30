package io.vidocq.cassini.spi.resource;

/**
 * JAX-RS resource factory SPI — abstracts the instantiation of {@code @Path}-
 * annotated classes and providers.
 *
 * <p>Two usage modes:
 * <ul>
 *   <li><b>Mode A ("pure" Cassini)</b> — the default factory
 *       {@link #defaultFactory()} creates a fresh instance per request via the
 *       no-arg constructor. No injection.</li>
 *   <li><b>Mode B (Cassini + managed DI)</b> — prefer the
 *       {@link io.vidocq.cassini.spi.bean.BeanProvider} SPI and its
 *       {@code cassini-cdi-vauban} adapter (or any other BeanProvider) to
 *       delegate instantiation to a DI container.</li>
 * </ul>
 */
public interface ResourceFactory {

    /**
     * Creates (or fetches from the scope) an instance of {@code resourceClass}.
     *
     * @param resourceClass JAX-RS resource or provider class
     * @return an instance ready to be invoked
     */
    <T> T create(Class<T> resourceClass);

    /**
     * Releases an instance created by {@link #create(Class)}. Default
     * implementation: no-op (GC handles it in Mode A).
     */
    default void destroy(Object resource) {
        // no-op
    }

    /**
     * Default factory: a new instance per call via the no-arg constructor.
     * No injection. Used in Mode A.
     *
     * <p>M6a: if a generated adapter with {@code newInstance()} is available (i.e. the class
     * has an accessible no-arg constructor), it is preferred over reflection. Falls back to
     * {@code getDeclaredConstructor().newInstance()} otherwise.</p>
     */
    static ResourceFactory defaultFactory() {
        return new ResourceFactory() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T create(Class<T> resourceClass) {
                // NOTE: ResourceFactory is in cassini-api which does NOT depend on cassini-core,
                // so we cannot call AdapterRegistry directly here. The M6a rewiring for this SPI
                // is handled at the call sites in cassini-core (CassiniStackBuilderImpl resolver).
                // This defaultFactory() is a convenience SPI that callers may replace; its own
                // reflective path is kept for backward compat and for callers that use it standalone.
                try {
                    return resourceClass.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "Cannot instantiate " + resourceClass.getName()
                                    + " — no public no-arg constructor (Mode A requires one, "
                                    + "or use a BeanProvider for injection-based instantiation)",
                            e);
                }
            }
        };
    }
}
