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
package io.vidocq.cassini.cdi;

import io.vidocq.cassini.cdi.internal.BeansByClass;
import io.vidocq.cassini.cdi.internal.BeansByClass.BeanOfClass;
import io.vidocq.cassini.spi.bean.BeanProvider;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.context.spi.Contextual;
import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link BeanProvider} built only on the standard CDI APIs — {@link CDI}, the {@link BeanManager}
 * and {@link jakarta.enterprise.context.control.RequestContextController} — so Cassini resolves its
 * resources and providers as beans of any CDI 4 container (cassini#53).
 *
 * <p>{@link #getResourceClasses()} adds {@link CdiRequestScopeFilter}, which activates the request
 * context around each request; {@link #getBean(Class)} returns its single instance.</p>
 *
 * <p>A class its raw type does not resolve, a bounded generic {@code @Dependent} class (CDI 4.1 §5.2.4), is found
 * by its bean class (CASSINI-061).</p>
 */
public final class CdiBeanProvider implements BeanProvider {

    /** The bean of a class its raw type does not resolve, found by its bean class with its bean type; null when it
     *  has none. Never changes while the container runs. */
    private record ByBeanClass(BeanOfClass found) {}

    private static final ByBeanClass NONE = new ByBeanClass(null);

    private final CDI<Object> cdi;
    private final CdiRequestScopeFilter requestScopeFilter;
    /** Per class its raw type does not resolve, decided on its first lookup. */
    private final ConcurrentHashMap<Class<?>, ByBeanClass> foundByBeanClass = new ConcurrentHashMap<>();
    /** The beans by bean class, read once: by {@link #getResourceClasses()} at stack build, or by the first
     *  class its raw type does not resolve. */
    private final AtomicReference<BeansByClass> beanIndex = new AtomicReference<>();
    private final DependentsOwner dependentsOwner = new DependentsOwner();

    public CdiBeanProvider(CDI<Object> cdi) {
        this.cdi = cdi;
        this.requestScopeFilter = new CdiRequestScopeFilter(cdi);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        if (type == CdiRequestScopeFilter.class) {
            return (T) requestScopeFilter;
        }
        var instance = cdi.select(type);
        if (instance.isResolvable()) {
            return instance.get();
        }
        BeanOfClass found = byBeanClass(type).found();
        if (found == null) {
            throw new IllegalArgumentException("Not a resolvable CDI bean: " + type);
        }
        // Made as one its raw class resolves: a @Dependent instance, in the creational context of DependentsOwner. A
        // generic managed bean is @Dependent (CDI 4.1 §3.1): no client proxy to look behind (CASSINI-065).
        BeanManager bm = cdi.getBeanManager();
        Owner owner = fromContext(bm.getContext(ApplicationScoped.class), dependentsOwner, bm);
        return (T) found.reference(bm, owner.context());
    }

    /**
     * A resource for one request, destroyed when the request ends: {@code Instance.destroy} for one its raw class
     * resolves, which a {@code @Dependent} instance needs, as the {@code Instance} keeps it until it is destroyed
     * itself, at shutdown for {@code CDI.current()}; for one found by its bean class, a creational context of its own,
     * which its bean destroys. A resource of another scope is left to it (CASSINI-062).
     */
    @Override
    @SuppressWarnings("unchecked")
    public <T> RequestInstance<T> getRequestBean(Class<T> type) {
        if (type == CdiRequestScopeFilter.class) {
            return new RequestInstance<>(getBean(type), null);
        }
        var instance = cdi.select(type);
        if (instance.isResolvable()) {
            var handle = instance.getHandle();
            T bean = handle.get();
            // Destroying a client proxy would end its contextual instance, an application-scoped one included
            boolean dependent = handle.getBean().getScope() == Dependent.class;
            return new RequestInstance<>(bean, dependent ? handle::destroy : null);
        }
        BeanOfClass found = byBeanClass(type).found();
        if (found == null) {
            throw new IllegalArgumentException("Not a resolvable CDI bean: " + type);
        }
        BeanManager bm = cdi.getBeanManager();
        var bean = (Bean<Object>) found.bean();
        CreationalContext<Object> context = bm.createCreationalContext(bean);
        Object made = found.reference(bm, context);
        return new RequestInstance<>((T) made, () -> bean.destroy(made, context));
    }

    @Override
    public Object contextualInstance(Class<?> type, Object bean) {
        if (bean == null) return null;
        try {
            BeanManager bm = cdi.getBeanManager();
            Set<Bean<?>> candidates = bm.getBeans(type);
            // A bean found by its bean class (CASSINI-061) is generic, so @Dependent: the instance itself
            // (CASSINI-065).
            if (candidates.isEmpty()) return bean;
            Bean<?> resolved = bm.resolve(candidates);
            if (resolved == null) return bean;
            Class<? extends Annotation> scope = resolved.getScope();
            // Only normal-scoped beans are reached through a client proxy; for @Dependent and the
            // other pseudo-scopes, getBean() already returns the instance itself.
            if (scope == null || !scope.isAnnotationPresent(NormalScope.class)) return bean;
            Object contextual = fromContext(bm.getContext(scope), resolved, bm);
            return contextual != null ? contextual : bean;
        } catch (RuntimeException _) {
            // Inactive scope or unresolved bean: keep the client proxy.
            return bean;
        }
    }

    private static <T> T fromContext(Context ctx, Contextual<T> contextual, BeanManager bm) {
        T existing = ctx.get(contextual);
        if (existing != null) return existing;
        return ctx.get(contextual, bm.createCreationalContext(contextual));
    }

    private ByBeanClass byBeanClass(Class<?> type) {
        ByBeanClass known = foundByBeanClass.get(type);
        if (known != null) return known;
        ByBeanClass found = findByBeanClass(type);
        ByBeanClass raced = foundByBeanClass.putIfAbsent(type, found);
        return raced != null ? raced : found;
    }

    /**
     * Only a class whose raw type resolves no bean at all is looked up as a bean class: when it resolves several, an
     * ambiguity stays one, as before.
     */
    private ByBeanClass findByBeanClass(Class<?> type) {
        try {
            BeanManager bm = cdi.getBeanManager();
            if (!bm.getBeans(type).isEmpty()) return NONE;
            BeanOfClass found = beansByClass(bm).beanOf(type, bm);
            return found == null ? NONE : new ByBeanClass(found);
        } catch (RuntimeException _) {
            // Several beans of that bean class: nothing resolves, as getBean() reports.
            return NONE;
        }
    }

    private BeansByClass beansByClass(BeanManager bm) {
        BeansByClass known = beanIndex.get();
        if (known != null) return known;
        BeansByClass read = new BeansByClass(bm);
        return beanIndex.compareAndSet(null, read) ? read : beanIndex.get();
    }

    /** The creational context of {@link DependentsOwner}, which the container gives it on its creation. */
    private record Owner(CreationalContext<Owner> context) {}

    /**
     * The parent of the {@code @Dependent} instances {@link #getBean(Class)} makes from a bean found by its bean class,
     * so they are destroyed when the instances of {@code cdi.select(type).get()} are. Weld keeps those, when they have
     * a {@code @PreDestroy}, until the container shuts down, and destroys them then. This contextual lives in the
     * application context: when that context ends, at shutdown, the container destroys it, and its creational context,
     * released, destroys the instances made in it.
     */
    private static final class DependentsOwner implements Contextual<Owner> {

        @Override
        public Owner create(CreationalContext<Owner> context) {
            return new Owner(context);
        }

        @Override
        public void destroy(Owner owner, CreationalContext<Owner> context) {
            context.release();
        }
    }

    @Override
    public Set<Class<?>> getResourceClasses() {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Class<?> beanClass : beansByClass(cdi.getBeanManager()).beanClasses()) {
            if (beanClass.isAnnotationPresent(Path.class) || beanClass.isAnnotationPresent(Provider.class)) {
                result.add(beanClass);
            }
        }
        // Registered for the user: Cassini sees a @Provider @PreMatching filter and resolves it
        // through getBean(), which returns the single instance above.
        result.add(CdiRequestScopeFilter.class);
        return result;
    }
}
