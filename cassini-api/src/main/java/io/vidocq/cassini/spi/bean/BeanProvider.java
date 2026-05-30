package io.vidocq.cassini.spi.bean;

import java.util.Set;

/**
 * SPI letting a DI container (CDI Vauban/Weld/OpenWebBeans, or any other
 * mechanism) supply managed instances of JAX-RS resources and providers to
 * Cassini.
 *
 * <p>Decoupling: neither cassini-api nor cassini-core depend on jakarta.cdi.
 * Each ecosystem supplies its own {@code BeanProvider} via ServiceLoader.</p>
 *
 * <p>Built-in implementations:</p>
 * <ul>
 *   <li>{@code cassini-cdi-vauban} → Vauban adapter (priority 100)</li>
 * </ul>
 *
 * <p>Auto-discovery: {@code CassiniStack#builder()} looks up a {@link Factory}
 * via {@link java.util.ServiceLoader} and selects the one with the highest
 * priority. Callers may also pass one explicitly via
 * {@code CassiniStack.Builder#beanProvider(BeanProvider)}.</p>
 */
public interface BeanProvider {

    /**
     * Returns a managed instance of the class (injection resolved).
     *
     * @throws IllegalArgumentException if the class is not managed by this provider
     */
    <T> T getBean(Class<T> type);

    /**
     * Lists the {@code @Path}- or {@code @Provider}-annotated classes managed
     * by this provider. Used by Cassini to scan resources without requiring
     * the user to declare them in {@code Application.getClasses()}.
     */
    Set<Class<?>> getResourceClasses();

    /**
     * Returns the <em>contextual</em> instance underlying {@code bean}.
     *
     * <p>For a normal-scoped bean (e.g. {@code @RequestScoped}), {@link #getBean(Class)}
     * returns a <em>client proxy</em> that lazily delegates to the contextual instance.
     * Cassini's {@code @Context} injection writes into the fields of the object handed
     * to it via reflection: if that object is the proxy, the injected field is never seen
     * by the method body (which runs on the contextual instance behind the proxy). Cassini
     * therefore calls this method to obtain the <em>real</em> contextual instance on which
     * to inject {@code @Context} fields; the resource-method invocation still goes through
     * the proxy (which resolves to the same contextual instance in the active scope).</p>
     *
     * <p>Contract: the returned instance MUST be the one to which {@code bean} (the proxy)
     * delegates in the current scope. Default implementation: returns {@code bean} as-is
     * (no-proxy case — direct instantiation or pseudo-scopes like {@code @Dependent}).</p>
     *
     * @param type the resource/provider class requested from {@link #getBean(Class)}
     * @param bean the object returned by {@link #getBean(Class)} (possibly a proxy)
     * @return the real contextual instance, or {@code bean} if not applicable
     */
    default Object contextualInstance(Class<?> type, Object bean) {
        return bean;
    }

    /**
     * ServiceLoader SPI — implementations registered via
     * {@code provides BeanProvider.Factory with ...} in {@code module-info}.
     */
    interface Factory {
        /**
         * Creates a {@link BeanProvider} instance. Called once by
         * {@code CassiniStack#builder()} (the BeanProvider is then reused).
         */
        BeanProvider create();

        /**
         * Priority (higher = preferred) when several implementations are on the
         * classpath. Default: 0.
         */
        default int priority() { return 0; }
    }
}
