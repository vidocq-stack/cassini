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

import io.vidocq.cassini.spi.bean.BeanProvider;
import jakarta.enterprise.context.NormalScope;
import jakarta.enterprise.context.spi.Context;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;
import java.lang.annotation.Annotation;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * {@link BeanProvider} built only on the standard CDI APIs — {@link CDI}, the {@link BeanManager}
 * and {@link jakarta.enterprise.context.control.RequestContextController} — so Cassini resolves its
 * resources and providers as beans of any CDI 4 container (cassini#53).
 *
 * <p>{@link #getResourceClasses()} adds {@link CdiRequestScopeFilter}, which activates the request
 * context around each request; {@link #getBean(Class)} returns its single instance.</p>
 */
public final class CdiBeanProvider implements BeanProvider {

    private final CDI<Object> cdi;
    private final CdiRequestScopeFilter requestScopeFilter;

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
        if (!instance.isResolvable()) {
            throw new IllegalArgumentException("Not a resolvable CDI bean: " + type);
        }
        return instance.get();
    }

    @Override
    public Object contextualInstance(Class<?> type, Object bean) {
        if (bean == null) return null;
        try {
            BeanManager bm = cdi.getBeanManager();
            Set<Bean<?>> candidates = bm.getBeans(type);
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

    private static <T> Object fromContext(Context ctx, Bean<T> bean, BeanManager bm) {
        T existing = ctx.get(bean);
        if (existing != null) return existing;
        return ctx.get(bean, bm.createCreationalContext(bean));
    }

    @Override
    public Set<Class<?>> getResourceClasses() {
        Set<Class<?>> result = new LinkedHashSet<>();
        for (Bean<?> bean : cdi.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (beanClass == null) continue;
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
