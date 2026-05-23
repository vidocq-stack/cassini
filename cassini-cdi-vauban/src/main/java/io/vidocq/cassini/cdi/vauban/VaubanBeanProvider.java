package io.vidocq.cassini.cdi.vauban;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Implémentation {@link BeanProvider} pour le container CDI Vauban.
 *
 * <p>Récupère les instances managées via {@link VaubanContainer#select(Class)}
 * et expose les classes {@code @Path}/{@code @Provider} connues du
 * {@link jakarta.enterprise.inject.spi.BeanManager}.</p>
 */
public final class VaubanBeanProvider implements BeanProvider {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private final VaubanContainer container;
    /** Singleton — activera/désactivera le RequestContext autour de chaque dispatch HTTP. */
    private final VaubanRequestScopeFilter requestScopeFilter;

    public VaubanBeanProvider(VaubanContainer container) {
        this.container = container;
        this.requestScopeFilter = new VaubanRequestScopeFilter(container);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        if (type == VaubanRequestScopeFilter.class) {
            return (T) requestScopeFilter;
        }
        try {
            return container.select(type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not managed by Vauban: " + type, e);
        }
    }

    @Override
    public Set<Class<?>> getResourceClasses() {
        Set<Class<?>> result = new LinkedHashSet<>();
        var bm = container.getBeanManager();
        for (Bean<?> bean : bm.getBeans(Object.class, ANY)) {
            Class<?> beanClass = bean.getBeanClass();
            if (beanClass == null) continue;
            if (beanClass.isAnnotationPresent(Path.class)
                    || beanClass.isAnnotationPresent(Provider.class)) {
                result.add(beanClass);
            }
        }
        // Auto-injecte le filter d'activation du RequestContext — Cassini l'enregistrera
        // comme provider (annoté @Provider @PreMatching) et le résoudra via getBean() qui
        // retourne le singleton interne. L'utilisateur n'a rien à enregistrer manuellement.
        result.add(VaubanRequestScopeFilter.class);
        return result;
    }
}
