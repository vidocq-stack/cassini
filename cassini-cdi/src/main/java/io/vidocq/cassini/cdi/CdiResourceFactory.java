package io.vidocq.cassini.cdi;

import io.vidocq.cassini.spi.resource.ResourceFactory;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.enterprise.util.AnnotationLiteral;

import java.util.Set;

/**
 * Implémentation {@link ResourceFactory} qui délègue au {@link BeanManager} CDI.
 *
 * <p>Mode B (Cassini + CDI) — supporte les scopes ({@code @RequestScoped},
 * {@code @ApplicationScoped}, etc.) et l'injection ({@code @Inject}).
 *
 * <p>Si la classe n'a pas de bean enregistré dans le BeanManager (par exemple
 * une classe {@code @Path} sans annotation de scope, dans une appli sans BCE
 * {@link CassiniScopeExtension}), on instancie via
 * {@code BeanManager.getInjectionTargetFactory()} pour obtenir au moins
 * l'injection des champs.
 */
public final class CdiResourceFactory implements ResourceFactory {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private final BeanManager beanManager;

    public CdiResourceFactory() {
        this(CDI.current().getBeanManager());
    }

    public CdiResourceFactory(BeanManager beanManager) {
        this.beanManager = beanManager;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T> T create(Class<T> resourceClass) {
        Set<Bean<?>> beans = beanManager.getBeans(resourceClass, ANY);
        Bean<?> bean = beanManager.resolve(beans);
        if (bean != null) {
            var cc = beanManager.createCreationalContext(bean);
            return (T) beanManager.getReference(bean, resourceClass, cc);
        }
        return instantiateWithInjection(resourceClass);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> T instantiateWithInjection(Class<T> type) {
        try {
            jakarta.enterprise.inject.spi.AnnotatedType at = beanManager.createAnnotatedType(type);
            jakarta.enterprise.inject.spi.InjectionTargetFactory factory =
                    beanManager.getInjectionTargetFactory(at);
            jakarta.enterprise.inject.spi.InjectionTarget it = factory.createInjectionTarget(null);
            jakarta.enterprise.context.spi.CreationalContext cc =
                    beanManager.createCreationalContext(null);
            Object instance = it.produce(cc);
            it.inject(instance, cc);
            it.postConstruct(instance);
            return (T) instance;
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "Failed to instantiate " + type.getName() + " via CDI InjectionTarget", e);
        }
    }
}
