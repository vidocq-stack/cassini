package io.vidocq.cassini.internal;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Modèle d'une méthode de ressource JAX-RS découverte côté Cassini.
 *
 * @param beanClass       classe portant la méthode finale (sous-ressource)
 * @param javaMethod      méthode Java (déjà rendue accessible) — null si dynamicLocator
 * @param httpMethod      verbe HTTP (GET, POST, …)
 * @param template        template URI combiné (classe + méthode)
 * @param produces        media types déclarés via {@code @Produces}
 * @param consumes        media types déclarés via {@code @Consumes}
 * @param rootBeanClass   classe racine instanciée en premier (§3.4.1)
 * @param locatorChain    chaîne ordonnée de méthodes locator à invoquer sur la
 *                        racine pour atteindre l'instance de {@code beanClass}
 * @param dynamicLocator  §3.4.1 : route catch-all émise quand un sub-resource
 *                        locator retourne {@code Object} ou un type non-scannable.
 *                        Au runtime, l'Invoker exécute la chaîne et scanne la
 *                        classe effective de l'instance retournée pour le dispatch.
 */
public record ResourceMethod(
        Class<?> beanClass,
        Method javaMethod,
        String httpMethod,
        UriTemplate template,
        Set<String> produces,
        Set<String> consumes,
        Class<?> rootBeanClass,
        List<Method> locatorChain,
        int classPathLiterals,
        boolean dynamicLocator) {

    public ResourceMethod(Class<?> beanClass, Method javaMethod, String httpMethod,
                          UriTemplate template, Set<String> produces, Set<String> consumes,
                          Class<?> rootBeanClass, List<Method> locatorChain, int classPathLiterals) {
        this(beanClass, javaMethod, httpMethod, template, produces, consumes,
                rootBeanClass, locatorChain, classPathLiterals, false);
    }

    public ResourceMethod(Class<?> beanClass, Method javaMethod, String httpMethod,
                          UriTemplate template, Set<String> produces, Set<String> consumes) {
        this(beanClass, javaMethod, httpMethod, template, produces, consumes, null, null, 0, false);
    }

    /** Compatibilité : locator unique (chaîne de longueur 1). */
    public ResourceMethod(Class<?> beanClass, Method javaMethod, String httpMethod,
                          UriTemplate template, Set<String> produces, Set<String> consumes,
                          Class<?> rootBeanClass, Method locator) {
        this(beanClass, javaMethod, httpMethod, template, produces, consumes, rootBeanClass,
                locator == null ? null : List.of(locator), 0, false);
    }

    public String path() {
        return template.template();
    }

    /** Vrai si cette route est issue d'un sub-resource locator §3.4.1. */
    public boolean isLocated() {
        return locatorChain != null && !locatorChain.isEmpty() && rootBeanClass != null;
    }
}
