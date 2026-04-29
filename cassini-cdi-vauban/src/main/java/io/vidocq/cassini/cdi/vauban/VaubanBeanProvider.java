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

    public VaubanBeanProvider(VaubanContainer container) {
        this.container = container;
    }

    @Override
    public <T> T getBean(Class<T> type) {
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
        return result;
    }
}
