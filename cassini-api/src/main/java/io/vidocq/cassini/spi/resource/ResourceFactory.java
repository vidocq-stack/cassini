package io.vidocq.cassini.spi.resource;

/**
 * SPI de fabrique de ressources JAX-RS — abstrait l'instanciation des classes
 * annotées {@code @Path} et des providers.
 *
 * <p>Deux modes d'utilisation :
 * <ul>
 *   <li><b>Mode A (Cassini "pur")</b> — la fabrique par défaut
 *       {@link #defaultFactory()} crée une nouvelle instance par requête via
 *       le constructeur sans argument. Pas d'injection.</li>
 *   <li><b>Mode B (Cassini + CDI)</b> — {@code cassini-cdi} fournit
 *       {@code CdiResourceFactory} qui délègue au {@code BeanManager} pour
 *       support des scopes ({@code @RequestScoped}, {@code @ApplicationScoped})
 *       et de l'injection.</li>
 * </ul>
 *
 * <p>L'implémentation est sélectionnée via {@link java.util.ServiceLoader}.
 */
public interface ResourceFactory {

    /**
     * Crée (ou récupère depuis le scope) une instance de {@code resourceClass}.
     *
     * @param resourceClass classe ressource ou provider JAX-RS
     * @return instance prête à être invoquée
     */
    <T> T create(Class<T> resourceClass);

    /**
     * Libère une instance créée par {@link #create(Class)}. Implémentation par
     * défaut : no-op (le GC s'en occupe pour le Mode A).
     */
    default void destroy(Object resource) {
        // no-op
    }

    /**
     * Fabrique par défaut : nouvelle instance par appel via constructeur sans
     * argument. Aucune injection. Utilisée en Mode A.
     */
    static ResourceFactory defaultFactory() {
        return new ResourceFactory() {
            @Override
            public <T> T create(Class<T> resourceClass) {
                try {
                    return resourceClass.getDeclaredConstructor().newInstance();
                } catch (ReflectiveOperationException e) {
                    throw new IllegalStateException(
                            "Cannot instantiate " + resourceClass.getName()
                                    + " — no public no-arg constructor (Mode A requires one, "
                                    + "or use cassini-cdi for injection-based instantiation)",
                            e);
                }
            }
        };
    }
}
