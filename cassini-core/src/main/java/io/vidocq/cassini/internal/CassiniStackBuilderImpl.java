package io.vidocq.cassini.internal;

import io.vidocq.cassini.internal.filter.FilterRegistry;
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
 * Implémentation de {@link CassiniStack.Builder} — assemble router, invoker et
 * registries depuis une {@link Application} JAX-RS et un {@link ResourceFactory}.
 *
 * <p>Réplique la logique de bootstrap précédemment inline dans
 * {@code ChappeRuntimeDelegate.ChappeSeBootstrapInstance}.</p>
 */
final class CassiniStackBuilderImpl implements CassiniStack.Builder {

    private Application application;
    private ResourceFactory resourceFactory;
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

        Set<Class<?>> pathClasses = new LinkedHashSet<>();
        FilterRegistry filters = new FilterRegistry();
        MessageBodyRegistry bodies = new MessageBodyRegistry();
        ExceptionMapperRegistry mappers = new ExceptionMapperRegistry();

        for (Class<?> c : resourceClasses) {
            if (c.isAnnotationPresent(jakarta.ws.rs.Path.class)) pathClasses.add(c);
            if (c.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)) {
                Object inst = resourceSingletons.computeIfAbsent(c, k -> {
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

        var routes = ResourceScanner.discover(pathClasses.toArray(Class<?>[]::new));
        var router = new UriRouter(routes);

        final Map<Class<?>, Object> singletons = Map.copyOf(resourceSingletons);
        final ResourceFactory factory = resourceFactory;

        Function<Class<?>, Object> resolver = cls -> {
            Object fixed = singletons.get(cls);
            if (fixed != null) return fixed;
            if (factory != null) {
                return factory.create(cls);
            }
            try { return cls.getDeclaredConstructor().newInstance(); }
            catch (ReflectiveOperationException e) {
                throw new RuntimeException("Failed to instantiate " + cls, e);
            }
        };

        var invoker = new Invoker(resolver, bodies, mappers);
        invoker.setFilters(filters);

        var adapter = new DefaultCassiniHttpAdapter(router, invoker);
        return new CassiniStackImpl(adapter);
    }

    /**
     * Remonte la hiérarchie de classes pour trouver celle portant {@code @Path}
     * ou {@code @Provider}. Nécessaire pour les proxies CDI.
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
