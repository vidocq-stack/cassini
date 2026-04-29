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
