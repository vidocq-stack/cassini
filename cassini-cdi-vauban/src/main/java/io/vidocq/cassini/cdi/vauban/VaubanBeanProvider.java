package io.vidocq.cassini.cdi.vauban;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Implementation of {@link BeanProvider} for the Vauban CDI container.
 *
 * <p>Retrieves managed instances via {@link VaubanContainer#select(Class)}
 * and exposes the {@code @Path}/{@code @Provider} classes known to the
 * {@link jakarta.enterprise.inject.spi.BeanManager}.</p>
 */
public final class VaubanBeanProvider implements BeanProvider {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private final VaubanContainer container;
    /** Singleton — will activate/deactivate the RequestContext around each HTTP dispatch. */
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

    /**
     * De-proxies: for a normal-scoped bean, returns the real contextual instance to which
     * the client proxy delegates in the active scope, so that Cassini's {@code @Context}
     * injection writes to the fields seen by the resource method body. For pseudo-scopes
     * ({@code @Dependent}), {@link #getBean(Class)} already returned the real instance
     * → it is kept as-is.
     *
     * <p>Uses the standard CDI API ({@link jakarta.enterprise.inject.spi.BeanManager}) — no
     * coupling to Vauban proxy internals. The request scope is guaranteed active during dispatch
     * by {@link VaubanRequestScopeFilter} ({@code @PreMatching}, priority {@code MIN_VALUE}).</p>
     */
    @Override
    public Object contextualInstance(Class<?> type, Object bean) {
        if (bean == null) return null;
        try {
            var bm = container.getBeanManager();
            Set<Bean<?>> candidates = bm.getBeans(type);
            if (candidates.isEmpty()) return bean;
            Bean<?> resolved = bm.resolve(candidates);
            if (resolved == null) return bean;
            Class<? extends Annotation> scope = resolved.getScope();
            // Only normal-scoped beans are proxied (cf. VAU-INJ-001). For @Dependent and
            // other pseudo-scopes, getBean() already returns the real instance.
            if (scope == null || !scope.isAnnotationPresent(NormalScope.class)) return bean;
            Object contextual = fromContext(bm.getContext(scope), resolved, bm);
            return contextual != null ? contextual : bean;
        } catch (RuntimeException e) {
            // inactive scope or unresolved bean → keep the original object (the proxy).
            return bean;
        }
    }

    /** Captures {@code T} from {@code Bean<T>} to satisfy the generic signature of
     *  {@link Context#get(jakarta.enterprise.context.spi.Contextual, jakarta.enterprise.context.spi.CreationalContext)}.
     *  Returns the existing contextual instance from the scope, or creates it if absent. */
    private static <T> Object fromContext(Context ctx, Bean<T> bean, jakarta.enterprise.inject.spi.BeanManager bm) {
        T existing = ctx.get(bean);
        if (existing != null) return existing;
        return ctx.get(bean, bm.createCreationalContext(bean));
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
        // Auto-injects the RequestContext activation filter — Cassini will register it
        // as a provider (annotated @Provider @PreMatching) and resolve it via getBean(),
        // which returns the internal singleton. The user has nothing to register manually.
        result.add(VaubanRequestScopeFilter.class);
        return result;
    }
}
