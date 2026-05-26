package io.vidocq.cassini.internal.filter;

import jakarta.annotation.Priority;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedElement;
import java.util.HashSet;
import java.util.Set;

/**
 * Entrée du registre : un filtre/intercepteur + ses métadonnées
 * (priorité, pre-matching, bindings par annotation de nom).
 *
 * <p>§6 : la priorité par défaut est {@code Priorities.USER = 5000}. Un
 * filtre sans {@link Priority} utilise cette valeur.</p>
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
        // §6 : un filtre fourni par un BeanProvider CDI est un client proxy (ex.
        // JwtAuthenticationFilter_ClientProxy extends JwtAuthenticationFilter). @Provider /
        // @PreMatching / @Priority / @NameBinding ne sont pas @Inherited, donc on remonte la
        // hiérarchie jusqu'à la classe @Provider (cf. CassiniStackBuilderImpl.jaxrsAnnotatedClass)
        // pour les lire — sinon @PreMatching est lu false et le filtre est classé post-matching.
        Class<?> cls = providerClass(instance.getClass());
        int prio = DEFAULT_PRIORITY;
        Priority p = cls.getAnnotation(Priority.class);
        if (p != null) prio = p.value();
        boolean pre = cls.getAnnotation(jakarta.ws.rs.container.PreMatching.class) != null;
        Set<Class<? extends Annotation>> bindings = collectNameBindings(cls);
        return new FilterEntry<>(instance, cls, prio, pre, bindings, null);
    }

    /** §6.5.5 : crée une entrée bornée à une méthode cible (DynamicFeature). */
    public static <T> FilterEntry<T> dynamicFor(T instance, java.lang.reflect.Method target) {
        Class<?> cls = providerClass(instance.getClass());
        int prio = DEFAULT_PRIORITY;
        Priority p = cls.getAnnotation(Priority.class);
        if (p != null) prio = p.value();
        return new FilterEntry<>(instance, cls, prio, false, Set.of(), target);
    }

    /**
     * Remonte la hiérarchie jusqu'à la classe portant {@code @Provider} (la classe réelle du
     * filtre derrière un éventuel client proxy CDI). Repli sur la classe d'origine si aucune
     * superclasse n'est annotée {@code @Provider} (ex. filtre enregistré programmatiquement).
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

    /** Vrai si le filtre s'applique à la méthode cible (name-bindings + dynamic target). */
    public boolean appliesTo(AnnotatedElement method, AnnotatedElement declaringClass) {
        // §6.5.5 : binding dynamique — applique strictement à la méthode cible.
        if (dynamicTarget != null) {
            return method instanceof java.lang.reflect.Method m && m.equals(dynamicTarget);
        }
        if (nameBindings.isEmpty()) return true; // pas de binding → global
        Set<Class<? extends Annotation>> owned = new HashSet<>();
        collectAnnotationsOfType(declaringClass, owned);
        collectAnnotationsOfType(method, owned);
        // §6.5.2 : une @NameBinding sur la sous-classe Application
        // (récupérée via ParamExtractor.currentApplication) s'applique à
        // toutes les ressources et tous les filtres → bindings globaux.
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
