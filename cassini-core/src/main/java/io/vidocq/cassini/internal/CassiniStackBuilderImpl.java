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
package io.vidocq.cassini.internal;

import io.vidocq.cassini.internal.filter.FilterRegistry;
import io.vidocq.cassini.internal.gen.AdapterRegistry;
import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.cassini.spi.resource.ResourceFactory;
import jakarta.ws.rs.core.Application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Implementation of {@link CassiniStack.Builder} — assembles the router, invoker and
 * registries from a JAX-RS {@link Application} and a {@link ResourceFactory}.
 *
 * <p>Extracts the bootstrap logic that was previously inlined in
 * {@code ChappeRuntimeDelegate.ChappeSeBootstrapInstance}.</p>
 */
final class CassiniStackBuilderImpl implements CassiniStack.Builder {

    private Application application;
    private ResourceFactory resourceFactory;
    private BeanProvider beanProvider;
    private final List<Object> extraProviders = new ArrayList<>();

    @Override
    public CassiniStack.Builder application(Application app) {
        this.application = app;
        return this;
    }

    @Override
    public CassiniStack.Builder resourceFactory(ResourceFactory factory) {
        this.resourceFactory = factory;
        return this;
    }

    @Override
    public CassiniStack.Builder provider(Object providerInstance) {
        if (providerInstance != null) extraProviders.add(providerInstance);
        return this;
    }

    @Override
    public CassiniStack.Builder beanProvider(BeanProvider provider) {
        this.beanProvider = provider;
        return this;
    }

    @Override
    public CassiniStack build() {
        Set<Class<?>> resourceClasses = new LinkedHashSet<>();
        Map<Class<?>, Object> resourceSingletons = new LinkedHashMap<>();

        if (application != null) {
            if (application.getClasses() != null) resourceClasses.addAll(application.getClasses());
            if (application.getSingletons() != null) {
                for (Object o : application.getSingletons()) {
                    Class<?> jaxrsClass = jaxrsAnnotatedClass(o.getClass());
                    resourceClasses.add(jaxrsClass);
                    resourceSingletons.put(jaxrsClass, o);
                }
            }
        }

        // Merge the classes known to the BeanProvider (annotated @Path/@Provider).
        if (beanProvider != null) {
            for (Class<?> c : beanProvider.getResourceClasses()) {
                resourceClasses.add(jaxrsAnnotatedClass(c));
            }
        }

        Set<Class<?>> pathClasses = new LinkedHashSet<>();
        FilterRegistry filters = new FilterRegistry();
        MessageBodyRegistry bodies = new MessageBodyRegistry();
        ExceptionMapperRegistry mappers = new ExceptionMapperRegistry();

        for (Class<?> c : resourceClasses) {
            if (c.isAnnotationPresent(jakarta.ws.rs.Path.class)) pathClasses.add(c);
            if (c.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)) {
                Object inst = resourceSingletons.computeIfAbsent(c, k -> {
                    // Prefer the BeanProvider if the class is managed there.
                    if (beanProvider != null) {
                        try {
                            return beanProvider.getBean(k);
                        } catch (IllegalArgumentException ignored) {
                            // fallback ci-dessous
                        }
                    }
                    try { return k.getDeclaredConstructor().newInstance(); }
                    catch (ReflectiveOperationException e) { return null; }
                });
                if (inst == null) continue;
                filters.register(inst);
                if (inst instanceof jakarta.ws.rs.ext.MessageBodyReader<?> r) bodies.addReader(r);
                if (inst instanceof jakarta.ws.rs.ext.MessageBodyWriter<?> w) bodies.addWriter(w);
                mappers.register(inst);
            }
        }

        // Extra providers passed explicitly via builder.provider()
        for (Object inst : extraProviders) {
            Class<?> jaxrsClass = jaxrsAnnotatedClass(inst.getClass());
            if (!resourceSingletons.containsKey(jaxrsClass)) {
                resourceSingletons.put(jaxrsClass, inst);
            }
            filters.register(inst);
            if (inst instanceof jakarta.ws.rs.ext.MessageBodyReader<?> r) bodies.addReader(r);
            if (inst instanceof jakarta.ws.rs.ext.MessageBodyWriter<?> w) bodies.addWriter(w);
            mappers.register(inst);
        }

        var routes = RouteRegistry.discover(pathClasses.toArray(Class<?>[]::new));
        // §6.5.5: run DynamicFeatures on each resource method — they register
        // their per-method filters/interceptors (e.g. RolesAllowedDynamicFeature →
        // @RolesAllowed/@DenyAll/@PermitAll). Without this call, features were collected
        // but never applied: authorisation was not wired up.
        filters.applyDynamicFeatures(routes);
        var router = new UriRouter(routes);

        final Map<Class<?>, Object> singletons = Map.copyOf(resourceSingletons);
        final ResourceFactory factory = resourceFactory;
        final BeanProvider bp = beanProvider;

        Function<Class<?>, Object> resolver = cls -> {
            Object fixed = singletons.get(cls);
            if (fixed != null) return fixed;
            if (bp != null) {
                try {
                    return bp.getBean(cls);
                } catch (IllegalArgumentException ignored) {
                    // fallback : factory ou newInstance
                }
            }
            if (factory != null) {
                return factory.create(cls);
            }
            // M6a: prefer generated adapter newInstance() over reflection
            var adapterM6a = AdapterRegistry.lookup(cls);
            if (adapterM6a.isPresent()) {
                try {
                    return adapterM6a.get().newInstance();
                } catch (UnsupportedOperationException ignored) {
                    // no no-arg ctor — fall through to reflective path
                }
            }
            try { return cls.getDeclaredConstructor().newInstance(); }
            catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to instantiate " + cls, e);
            }
        };

        var invoker = new Invoker(resolver, bodies, mappers);
        invoker.setFilters(filters);
        // §9: allows the Invoker to de-proxy a CDI bean (actual contextual instance)
        // for @Context injection into @RequestScoped resources (cf. BeanProvider).
        invoker.setBeanProvider(bp);

        var adapter = new DefaultCassiniHttpAdapter(router, invoker);
        return new CassiniStackImpl(adapter, router.routes());
    }

    /**
     * Walks the class hierarchy to find the class carrying {@code @Path}
     * or {@code @Provider}. Required for CDI proxies.
     */
    static Class<?> jaxrsAnnotatedClass(Class<?> c) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            if (cur.isAnnotationPresent(jakarta.ws.rs.Path.class)
                    || cur.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)) {
                return cur;
            }
            cur = cur.getSuperclass();
        }
        return c;
    }
}
