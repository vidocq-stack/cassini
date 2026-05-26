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

    /**
     * Déproxifie : pour un bean normal-scoped, retourne l'instance contextuelle réelle vers
     * laquelle le client proxy délègue dans le scope actif, afin que l'injection {@code @Context}
     * de Cassini écrive dans les champs vus par le corps de la méthode resource. Pour les
     * pseudo-scopes ({@code @Dependent}), {@link #getBean(Class)} a déjà renvoyé l'instance réelle
     * → on la conserve telle quelle.
     *
     * <p>Utilise l'API CDI standard ({@link jakarta.enterprise.inject.spi.BeanManager}) — aucun
     * couplage aux internes du proxy Vauban. Le scope request est garanti actif pendant le dispatch
     * par {@link VaubanRequestScopeFilter} ({@code @PreMatching}, priorité {@code MIN_VALUE}).</p>
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
            // Seuls les beans normal-scoped sont proxifiés (cf. VAU-INJ-001). Pour @Dependent et
            // autres pseudo-scopes, getBean() renvoie déjà l'instance réelle.
            if (scope == null || !scope.isAnnotationPresent(NormalScope.class)) return bean;
            Object contextual = fromContext(bm.getContext(scope), resolved, bm);
            return contextual != null ? contextual : bean;
        } catch (RuntimeException e) {
            // scope inactif ou bean non résolu → conserver l'objet d'origine (le proxy).
            return bean;
        }
    }

    /** Capture {@code T} depuis {@code Bean<T>} pour satisfaire la signature générique de
     *  {@link Context#get(jakarta.enterprise.context.spi.Contextual, jakarta.enterprise.context.spi.CreationalContext)}.
     *  Retourne l'instance contextuelle existante du scope, ou la crée si absente. */
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
        // Auto-injecte le filter d'activation du RequestContext — Cassini l'enregistrera
        // comme provider (annoté @Provider @PreMatching) et le résoudra via getBean() qui
        // retourne le singleton interne. L'utilisateur n'a rien à enregistrer manuellement.
        result.add(VaubanRequestScopeFilter.class);
        return result;
    }
}
