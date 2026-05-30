package io.vidocq.cassini.internal.filter;

import jakarta.ws.rs.core.Configurable;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;

import java.lang.reflect.Method;
import java.util.Map;

/**
 * §6.5.5 — FeatureContext passed to DynamicFeature.configure(resourceInfo, ctx).
 * register(Class) instantiates the class and adds it to the registry as a
 * dynamic entry bound to {@code targetMethod}.
 */
public final class CassiniDynamicFeatureContext implements FeatureContext {

    private final FilterRegistry registry;
    private final Method targetMethod;

    public CassiniDynamicFeatureContext(FilterRegistry registry, Method targetMethod) {
        this.registry = registry;
        this.targetMethod = targetMethod;
    }

    @Override public Configuration getConfiguration() {
        return new Configuration() {
            @Override public jakarta.ws.rs.RuntimeType getRuntimeType() { return jakarta.ws.rs.RuntimeType.SERVER; }
            @Override public Map<String, Object> getProperties() { return Map.of(); }
            @Override public Object getProperty(String name) { return null; }
            @Override public java.util.Collection<String> getPropertyNames() { return java.util.List.of(); }
            @Override public boolean isEnabled(Feature feature) { return false; }
            @Override public boolean isEnabled(Class<? extends Feature> featureClass) { return false; }
            @Override public boolean isRegistered(Object component) { return false; }
            @Override public boolean isRegistered(Class<?> componentClass) { return false; }
            @Override public Map<Class<?>, Integer> getContracts(Class<?> componentClass) { return Map.of(); }
            @Override public java.util.Set<Class<?>> getClasses() { return java.util.Set.of(); }
            @Override public java.util.Set<Object> getInstances() { return java.util.Set.of(); }
        };
    }

    @Override public FeatureContext property(String name, Object value) { return this; }

    @Override public FeatureContext register(Class<?> componentClass) {
        try {
            Object instance = componentClass.getDeclaredConstructor().newInstance();
            registry.registerDynamic(instance, targetMethod);
        } catch (ReflectiveOperationException ignored) {}
        return this;
    }

    @Override public FeatureContext register(Class<?> componentClass, int priority) {
        return register(componentClass);
    }

    @Override public FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
        return register(componentClass);
    }

    @Override public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) {
        return register(componentClass);
    }

    @Override public FeatureContext register(Object component) {
        registry.registerDynamic(component, targetMethod);
        return this;
    }

    @Override public FeatureContext register(Object component, int priority) {
        return register(component);
    }

    @Override public FeatureContext register(Object component, Class<?>... contracts) {
        return register(component);
    }

    @Override public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) {
        return register(component);
    }
}
