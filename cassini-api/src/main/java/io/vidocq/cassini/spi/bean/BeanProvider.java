package io.vidocq.cassini.spi.bean;

import java.util.Set;

/**
 * SPI permettant à un container DI (CDI Vauban/Weld/OpenWebBeans, ou tout
 * autre mécanisme) de fournir des instances managées de ressources et
 * providers JAX-RS à Cassini.
 *
 * <p>Découplage : ni cassini-api ni cassini-core ne dépendent de jakarta.cdi.
 * Chaque écosystème fournit son propre {@code BeanProvider} via ServiceLoader.</p>
 *
 * <p>Implémentations fournies :</p>
 * <ul>
 *   <li>{@code cassini-cdi-vauban} → adapter Vauban (priorité 100)</li>
 * </ul>
 *
 * <p>Découverte automatique : {@code CassiniStack#builder()} cherche un
 * {@link Factory} via {@link java.util.ServiceLoader} et utilise celui de
 * priorité la plus haute. L'utilisateur peut aussi en passer un explicitement
 * via {@code CassiniStack.Builder#beanProvider(BeanProvider)}.</p>
 */
public interface BeanProvider {

    /**
     * Retourne une instance gérée de la classe (injection résolue).
     *
     * @throws IllegalArgumentException si la classe n'est pas managée par ce provider
     */
    <T> T getBean(Class<T> type);

    /**
     * Liste les classes annotées {@code @Path} ou {@code @Provider} gérées
     * par ce provider. Utilisé par Cassini pour scanner les ressources sans
     * que l'utilisateur ait à les déclarer dans {@code Application.getClasses()}.
     */
    Set<Class<?>> getResourceClasses();

    /**
     * Retourne l'instance <em>contextuelle</em> sous-jacente à {@code bean}.
     *
     * <p>Pour un bean normal-scoped (ex. {@code @RequestScoped}), {@link #getBean(Class)}
     * renvoie un <em>client proxy</em> qui délègue paresseusement à l'instance contextuelle.
     * L'injection {@code @Context} de Cassini écrit par réflexion dans les champs de l'objet
     * qu'on lui passe : si c'est le proxy, le champ injecté n'est jamais vu par le corps de la
     * méthode (qui s'exécute sur l'instance contextuelle derrière le proxy). Cassini appelle
     * donc cette méthode pour obtenir l'instance contextuelle <em>réelle</em> sur laquelle
     * injecter les champs {@code @Context} ; l'invocation de la méthode resource reste faite
     * via le proxy (qui résout la même instance contextuelle dans le scope actif).</p>
     *
     * <p>Contrat : l'instance retournée DOIT être celle vers laquelle {@code bean} (le proxy)
     * délègue dans le scope courant. Implémentation par défaut : retourne {@code bean} tel quel
     * (cas sans proxy — instanciation directe ou pseudo-scopes type {@code @Dependent}).</p>
     *
     * @param type la classe resource/provider demandée à {@link #getBean(Class)}
     * @param bean l'objet renvoyé par {@link #getBean(Class)} (potentiellement un proxy)
     * @return l'instance contextuelle réelle, ou {@code bean} si non applicable
     */
    default Object contextualInstance(Class<?> type, Object bean) {
        return bean;
    }

    /**
     * SPI ServiceLoader — implémentations enregistrées via
     * {@code provides BeanProvider.Factory with ...} dans {@code module-info}.
     */
    interface Factory {
        /**
         * Crée une instance de {@link BeanProvider}. Appelé une seule fois par
         * {@code CassiniStack#builder()} (le BeanProvider est ensuite réutilisé).
         */
        BeanProvider create();

        /**
         * Priorité (plus grand = préféré) en cas de conflits entre plusieurs
         * implémentations sur le classpath. Défaut : 0.
         */
        default int priority() { return 0; }
    }
}
