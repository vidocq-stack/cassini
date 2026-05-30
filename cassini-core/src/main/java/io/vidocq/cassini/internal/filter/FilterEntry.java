package io.vidocq.cassini.internal.filter;

import jakarta.annotation.Priority;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.HashSet;
import java.util.Set;

/**
 * Registry entry: a filter/interceptor + its metadata
 * (priority, pre-matching, name-binding annotations).
 *
 * <p>§6: the default priority is {@code Priorities.USER = 5000}. A
 * filter without {@link Priority} uses this value.</p>
 */
public record FilterEntry<T>(
        T instance,
        Class<?> implClass,
        int priority,
        boolean preMatching,
        Set<Class<? extends Annotation>> nameBindings,
        java.lang.reflect.Method dynamicTarget) {

    public static final int DEFAULT_PRIORITY = 5000;

    public static <T> FilterEntry<T> of(T instance) {
        // §6: a filter provided by a CDI BeanProvider is a client proxy (e.g.
        // JwtAuthenticationFilter_ClientProxy extends JwtAuthenticationFilter). @Provider /
        // @PreMatching / @Priority / @NameBinding are not @Inherited, so we walk up the
        // hierarchy to the @Provider class (cf. CassiniStackBuilderImpl.jaxrsAnnotatedClass)
        // to read them — otherwise @PreMatching is read as false and the filter is
        // classified as post-matching.
        Class<?> cls = providerClass(instance.getClass());
        int prio = DEFAULT_PRIORITY;
        Priority p = cls.getAnnotation(Priority.class);
        if (p != null) prio = p.value();
        boolean pre = cls.getAnnotation(jakarta.ws.rs.container.PreMatching.class) != null;
        Set<Class<? extends Annotation>> bindings = collectNameBindings(cls);
        return new FilterEntry<>(instance, cls, prio, pre, bindings, null);
    }

    /** §6.5.5: creates an entry bound to a specific target method (DynamicFeature). */
    public static <T> FilterEntry<T> dynamicFor(T instance, java.lang.reflect.Method target) {
        Class<?> cls = providerClass(instance.getClass());
        int prio = DEFAULT_PRIORITY;
        Priority p = cls.getAnnotation(Priority.class);
        if (p != null) prio = p.value();
        return new FilterEntry<>(instance, cls, prio, false, Set.of(), target);
    }

    /**
     * Walks up the hierarchy to find the class carrying {@code @Provider} (the real
     * filter class behind an optional CDI client proxy). Falls back to the original
     * class if no superclass is annotated with {@code @Provider} (e.g. filter
     * registered programmatically).
     */
    private static Class<?> providerClass(Class<?> c) {
        Class<?> cur = c;
        while (cur != null && cur != Object.class) {
            if (cur.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)) {
                return cur;
            }
            cur = cur.getSuperclass();
        }
        return c;
    }

    /** True if the filter applies to the target method (name-bindings + dynamic target). */
    public boolean appliesTo(AnnotatedElement method, AnnotatedElement declaringClass) {
        // §6.5.5: dynamic binding — applies strictly to the target method.
        if (dynamicTarget != null) {
            return method instanceof java.lang.reflect.Method m && m.equals(dynamicTarget);
        }
        if (nameBindings.isEmpty()) return true; // no binding → global
        Set<Class<? extends Annotation>> owned = new HashSet<>();
        collectAnnotationsOfType(declaringClass, owned);
        collectAnnotationsOfType(method, owned);
        // §6.5.2: a @NameBinding on the Application subclass
        // (retrieved via ParamExtractor.currentApplication) applies to
        // all resources and all filters → global bindings.
        var app = io.vidocq.cassini.internal.ParamExtractor.currentApplication();
        if (app != null) {
            collectAnnotationsOfType(app.getClass(), owned);
        }
        return owned.containsAll(nameBindings);
    }

    private static Set<Class<? extends Annotation>> collectNameBindings(Class<?> cls) {
        Set<Class<? extends Annotation>> out = new HashSet<>();
        for (Annotation a : cls.getAnnotations()) {
            if (a.annotationType().isAnnotationPresent(jakarta.ws.rs.NameBinding.class)) {
                out.add(a.annotationType());
            }
        }
        return out;
    }

    private static void collectAnnotationsOfType(AnnotatedElement el, Set<Class<? extends Annotation>> out) {
        if (el == null) return;
        for (Annotation a : el.getAnnotations()) {
            if (a.annotationType().isAnnotationPresent(jakarta.ws.rs.NameBinding.class)) {
                out.add(a.annotationType());
            }
        }
    }
}
