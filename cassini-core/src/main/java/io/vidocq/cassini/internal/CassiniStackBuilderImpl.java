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
import io.vidocq.cassini.internal.gen.RuntimeGeneration;
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
    private boolean statistics = true;
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
    public CassiniStack.Builder statistics(boolean enabled) {
        this.statistics = enabled;
        return this;
    }

    @Override
    public CassiniStack build() {
        Set<Class<?>> resourceClasses = new LinkedHashSet<>();
        Map<Class<?>, Object> resourceSingletons = new LinkedHashMap<>();
        collectApplication(resourceClasses, resourceSingletons);

        // Merge the classes known to the BeanProvider (annotated @Path/@Provider).
        if (beanProvider != null) {
            beanProvider.getResourceClasses().forEach(c -> resourceClasses.add(jaxrsAnnotatedClass(c)));
        }

        Set<Class<?>> pathClasses = new LinkedHashSet<>();
        Registries providers = new Registries(new FilterRegistry(), new MessageBodyRegistry(),
                new ExceptionMapperRegistry());

        for (Class<?> c : resourceClasses) {
            if (c.isAnnotationPresent(jakarta.ws.rs.Path.class)) pathClasses.add(c);
            if (c.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)) {
                Object inst = resourceSingletons.computeIfAbsent(c, this::instantiateProvider);
                if (inst != null) providers.register(inst);
            }
        }

        // Extra providers passed explicitly via builder.provider()
        for (Object inst : extraProviders) {
            resourceSingletons.putIfAbsent(jaxrsAnnotatedClass(inst.getClass()), inst);
            providers.register(inst);
        }

        var routes = RouteRegistry.discover(pathClasses.toArray(Class<?>[]::new));
        RuntimeGeneration.stackBuilt(pathClasses);
        // §6.5.5: run DynamicFeatures on each resource method — they register
        // their per-method filters/interceptors (e.g. RolesAllowedDynamicFeature →
        // @RolesAllowed/@DenyAll/@PermitAll). Without this call, features were collected
        // but never applied: authorisation was not wired up.
        providers.filters().applyDynamicFeatures(routes);
        var router = new UriRouter(routes);

        var invoker = new Invoker(resolver(Map.copyOf(resourceSingletons)), providers.bodies(), providers.mappers());
        invoker.setFilters(providers.filters());
        // §9: allows the Invoker to de-proxy a CDI bean (actual contextual instance)
        // for @Context injection into @RequestScoped resources (cf. BeanProvider).
        invoker.setBeanProvider(beanProvider);
        // §9.2.1 / §10.2: @Context Application and the @ApplicationPath of the matched
        // resource template come from the deployment's Application (CASSINI-028).
        if (application != null) invoker.setApplication(application);

        RequestStatistics counters = statistics ? new RequestStatistics() : null;
        var adapter = new DefaultCassiniHttpAdapter(router, invoker, counters);
        return new CassiniStackImpl(adapter, router.routes(), counters);
    }

    /** The registries a provider instance is added to. */
    private record Registries(FilterRegistry filters, MessageBodyRegistry bodies, ExceptionMapperRegistry mappers) {
        void register(Object inst) {
            filters.register(inst);
            if (inst instanceof jakarta.ws.rs.ext.MessageBodyReader<?> r) bodies.addReader(r);
            if (inst instanceof jakarta.ws.rs.ext.MessageBodyWriter<?> w) bodies.addWriter(w);
            mappers.register(inst);
        }
    }

    /** The classes and singletons of the {@link Application}, singletons keyed by their JAX-RS class. */
    private void collectApplication(Set<Class<?>> resourceClasses, Map<Class<?>, Object> resourceSingletons) {
        if (application == null) return;
        if (application.getClasses() != null) resourceClasses.addAll(application.getClasses());
        if (application.getSingletons() != null) {
            for (Object o : application.getSingletons()) {
                Class<?> jaxrsClass = jaxrsAnnotatedClass(o.getClass());
                resourceClasses.add(jaxrsClass);
                resourceSingletons.put(jaxrsClass, o);
            }
        }
    }

    /** A {@code @Provider} class instance: from the BeanProvider when it manages it, else its no-arg constructor. */
    private Object instantiateProvider(Class<?> k) {
        if (beanProvider != null) {
            try {
                return beanProvider.getBean(k);
            } catch (IllegalArgumentException _) {
                // not managed by the BeanProvider: no-arg constructor below
            }
        }
        try {
            return k.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException _) {
            return null;
        }
    }

    /** Resolves a resource instance: singleton, BeanProvider, ResourceFactory, generated adapter, reflection. */
    private Function<Class<?>, Object> resolver(Map<Class<?>, Object> singletons) {
        final ResourceFactory factory = resourceFactory;
        final BeanProvider bp = beanProvider;
        return cls -> {
            Object fixed = singletons.get(cls);
            if (fixed != null) return fixed;
            if (bp != null) {
                try {
                    return bp.getBean(cls);
                } catch (IllegalArgumentException _) {
                    // fallback: factory or newInstance
                }
            }
            if (factory != null) {
                return factory.create(cls);
            }
            return newResource(cls);
        };
    }

    /** M6a: prefers the generated adapter's newInstance() over reflection. */
    private static Object newResource(Class<?> cls) {
        var adapter = AdapterRegistry.lookup(cls);
        if (adapter.isPresent()) {
            try {
                return adapter.get().newInstance();
            } catch (UnsupportedOperationException _) {
                // no no-arg ctor — fall through to the reflective path
            }
        }
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + cls, e);
        }
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
