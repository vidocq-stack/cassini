/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.cassini.cdi.vauban;

import io.vidocq.cassini.cdi.internal.BeansByClass;
import io.vidocq.cassini.cdi.internal.BeansByClass.BeanOfClass;
import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Implementation of {@link BeanProvider} for the Vauban CDI container.
 *
 * <p>Retrieves managed instances via {@link VaubanContainer#select(Class)}
 * and exposes the {@code @Path}/{@code @Provider} classes known to the
 * {@link jakarta.enterprise.inject.spi.BeanManager}. A class its raw type does not resolve, a bounded generic
 * {@code @Dependent} class (CDI 4.1 §5.2.4), is found by its bean class (CASSINI-061).</p>
 */
public final class VaubanBeanProvider implements BeanProvider {

    /** What the container resolved for a class: its bean; the client proxy it hands out for it when
     *  the bean is normal-scoped, always the same one; and the bean with its bean type when it was
     *  found by its bean class, its raw class resolving nothing. None changes while the container
     *  runs. All null when the class resolves to no single bean. */
    private record Resolution(Bean<?> bean, Object clientProxy, BeanOfClass ofBeanClass) {}

    private static final Resolution UNRESOLVED = new Resolution(null, null, null);

    private static final String PATH = Path.class.getName();
    private static final String PROVIDER = Provider.class.getName();

    private final VaubanContainer container;
    /** Singleton — will activate/deactivate the RequestContext around each HTTP dispatch. */
    private final VaubanRequestScopeFilter requestScopeFilter;
    /** Per resource or provider class, resolved on the first request: Cassini looks the resource
     *  up on every request, and resolving it walks every bean of the container. */
    private final ConcurrentHashMap<Class<?>, Resolution> resolutions = new ConcurrentHashMap<>();
    /** The beans by bean class, read once: by {@link #getResourceClasses()} at stack build, or by the first
     *  class its raw type does not resolve. */
    private final AtomicReference<BeansByClass> beanIndex = new AtomicReference<>();

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
        Resolution resolution = resolution(type);
        if (resolution.clientProxy() != null) return (T) resolution.clientProxy();
        try {
            // @Dependent and the other pseudo-scopes: a new instance on each lookup.
            BeanOfClass ofBeanClass = resolution.ofBeanClass();
            return ofBeanClass != null ? (T) dependent(ofBeanClass) : container.select(type);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not managed by Vauban: " + type, e);
        }
    }

    /** A new instance of a {@code @Dependent} bean found by its bean class, typed as its class. Its creational
     *  context is not kept: Vauban destroys no {@code @Dependent} instance that {@link VaubanContainer#select(Class)}
     *  hands out either. */
    private Object dependent(BeanOfClass ofBeanClass) {
        BeanManager bm = container.getBeanManager();
        return ofBeanClass.reference(bm, bm.createCreationalContext(ofBeanClass.bean()));
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
        Resolution resolution = resolution(type);
        // Only normal-scoped beans are proxied (cf. VAU-INJ-001). For @Dependent and other
        // pseudo-scopes, getBean() already returns the real instance.
        if (resolution.clientProxy() == null) return bean;
        try {
            var bm = container.getBeanManager();
            Object contextual = fromContext(bm.getContext(resolution.bean().getScope()), resolution.bean(), bm);
            return contextual != null ? contextual : bean;
        } catch (RuntimeException _) {
            // inactive scope → keep the original object (the proxy).
            return bean;
        }
    }

    private Resolution resolution(Class<?> type) {
        Resolution known = resolutions.get(type);
        if (known != null) return known;
        Resolution resolved = resolve(type);
        Resolution raced = resolutions.putIfAbsent(type, resolved);
        return raced != null ? raced : resolved;
    }

    /**
     * Typesafe resolution of the raw class first, as before: an alternative, a specialization or a producer of the
     * class keeps the bean it resolves to. Only when it resolves no bean is the class looked up as a bean class.
     */
    private Resolution resolve(Class<?> type) {
        try {
            var bm = container.getBeanManager();
            Set<Bean<?>> candidates = bm.getBeans(type);
            if (candidates.isEmpty()) return byBeanClass(type, bm);
            Bean<?> bean = bm.resolve(candidates);
            if (bean == null) return UNRESOLVED;
            return new Resolution(bean, isNormalScoped(bean) ? container.select(type) : null, null);
        } catch (RuntimeException _) {
            // Unresolvable: getBean() asks the container on each call and reports its error.
            return UNRESOLVED;
        }
    }

    /**
     * A bounded generic class: its bean, found by its bean class, is served as {@link #resolve} serves the bean of a
     * raw class. A normal-scoped one hands out its client proxy, which {@link #contextualInstance} looks behind; the
     * raw class selecting nothing, the proxy comes from {@code getReference} with the bean type. CDI 4.1 §3.1 makes a
     * generic managed bean {@code @Dependent}, but Vauban accepts the {@code @RequestScoped} that
     * {@code CassiniScopeExtension} gives a scope-less generic {@code @Path} class (Vauban BUG-20261117-35): this
     * normal-scoped path is transitional, and the Cassini companion of BUG-35 removes it.
     */
    private Resolution byBeanClass(Class<?> type, BeanManager bm) {
        BeanOfClass ofBeanClass = beansByClass().beanOf(type, bm);
        if (ofBeanClass == null) return UNRESOLVED;
        Bean<?> bean = ofBeanClass.bean();
        Object clientProxy = isNormalScoped(bean) ? ofBeanClass.reference(bm, bm.createCreationalContext(bean)) : null;
        return new Resolution(bean, clientProxy, ofBeanClass);
    }

    private static boolean isNormalScoped(Bean<?> bean) {
        var scope = bean.getScope();
        return scope != null && scope.isAnnotationPresent(NormalScope.class);
    }

    private BeansByClass beansByClass() {
        BeansByClass known = beanIndex.get();
        if (known != null) return known;
        BeansByClass read = new BeansByClass(container.getBeanManager());
        return beanIndex.compareAndSet(null, read) ? read : beanIndex.get();
    }

    /** Captures {@code T} from {@code Bean<T>} to satisfy the generic signature of
     *  {@link Context#get(jakarta.enterprise.context.spi.Contextual, jakarta.enterprise.context.spi.CreationalContext)}.
     *  Returns the existing contextual instance from the scope, or creates it if absent. */
    private static <T> Object fromContext(Context ctx, Bean<T> bean, jakarta.enterprise.inject.spi.BeanManager bm) {
        T existing = ctx.get(bean);
        if (existing != null) return existing;
        return ctx.get(bean, bm.createCreationalContext(bean));
    }

    /**
     * Whether {@code beanClass} is annotated {@code @Path} or {@code @Provider}: from the annotations the
     * deployment's index read in its class file, without reflection; by reflection, as before, for a class the
     * index does not know.
     */
    boolean isJaxrsClass(Class<?> beanClass) {
        var indexed = container.indexedAnnotationNames(beanClass);
        if (indexed.isPresent()) {
            Set<String> names = indexed.get();
            return names.contains(PATH) || names.contains(PROVIDER);
        }
        return beanClass.isAnnotationPresent(Path.class) || beanClass.isAnnotationPresent(Provider.class);
    }

    @Override
    public Set<Class<?>> getResourceClasses() {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> beanClass : beansByClass().beanClasses()) {
            if (isJaxrsClass(beanClass)) {
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
