package io.vidocq.cassini.internal;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.OPTIONS;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Scanne le {@link BeanManager} CDI pour produire la liste des
 * {@link ResourceMethod} adressables.
 *
 * <p>M1 : support des annotations JAX-RS standard de verbe HTTP ({@link GET},
 * {@link POST}, {@link PUT}, {@link DELETE}, {@link HEAD}, {@link OPTIONS},
 * {@link PATCH}) et des méta-annotations {@link HttpMethod} pour les verbes
 * custom. Pas d'URI templates (voir M2a), pas de sub-resource locators.</p>
 */
public final class ResourceScanner {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private static final Class<?>[] BUILTIN_VERBS = {
            GET.class, POST.class, PUT.class, DELETE.class,
            HEAD.class, OPTIONS.class, PATCH.class
    };

    private ResourceScanner() {}

    public static List<ResourceMethod> discover(BeanManager beanManager) {
        Set<Class<?>> classes = new LinkedHashSet<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, ANY)) {
            classes.add(bean.getBeanClass());
        }
        // §3.1.1 : les classes @Path sans scope CDI sont quand même des
        // ressources JAX-RS (par défaut per-request). Vauban expose la liste
        // complète des classes scannées via META-INF/vauban-beans.list — on
        // ajoute celles annotées @Path qui auraient échappé au BeanManager.
        classes.addAll(discoverVaubanBeans());
        return discover(classes.toArray(Class<?>[]::new));
    }

    private static Set<Class<?>> discoverVaubanBeans() {
        Set<Class<?>> out = new LinkedHashSet<>();
        try {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) cl = ResourceScanner.class.getClassLoader();
            java.util.Enumeration<java.net.URL> urls = cl.getResources("META-INF/vauban-beans.list");
            while (urls.hasMoreElements()) {
                java.net.URL url = urls.nextElement();
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        url.openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#")) continue;
                        try {
                            Class<?> c = Class.forName(s, false, cl);
                            if (c.isAnnotationPresent(Path.class)) out.add(c);
                        } catch (Throwable ignored) {}
                    }
                }
            }
        } catch (java.io.IOException ignored) {}
        return out;
    }

    public static List<ResourceMethod> discover(Class<?>... classes) {
        List<ResourceMethod> out = new ArrayList<>();
        for (Class<?> cls : classes) {
            Path root = cls.getAnnotation(Path.class);
            if (root == null) continue;
            String basePath = normalize(root.value());
            int classLits = countLiterals(basePath);
            Set<String> classProduces = produces(cls.getAnnotation(Produces.class));
            Set<String> classConsumes = consumes(cls.getAnnotation(Consumes.class));

            for (Method m : collectInheritedMethods(cls)) {
                // §3.3.1 : une méthode de ressource doit être publique.
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
                String verb = resolveHttpMethod(m);
                Path sub = m.getAnnotation(Path.class);
                // Sub-resource locator §3.4.1 : @Path sur méthode SANS verbe HTTP
                // → la méthode retourne une instance dont on scanne les routes
                //   en préfixant par le path courant + @Path(method).
                if (verb == null) {
                    if (sub == null) continue;
                    Class<?> returnCls = m.getReturnType();
                    if (returnCls == void.class || returnCls == null) continue;
                    String locatorPath = combine(basePath, normalize(sub.value()));
                    // Héritage @Produces/@Consumes : locator méthode > classe.
                    Set<String> locatorProduces = produces(m.getAnnotation(Produces.class));
                    Set<String> locatorConsumes = consumes(m.getAnnotation(Consumes.class));
                    Set<String> inhProd = locatorProduces.isEmpty() ? classProduces : locatorProduces;
                    Set<String> inhCons = locatorConsumes.isEmpty() ? classConsumes : locatorConsumes;
                    m.setAccessible(true);
                    // §3.4.1 : si le type de retour est Response, le locator EST le handler
                    // terminal — on crée une route directe "*" (toutes méthodes HTTP).
                    if (jakarta.ws.rs.core.Response.class.isAssignableFrom(returnCls)) {
                        out.add(new ResourceMethod(cls, m, "*", UriTemplate.compile(locatorPath),
                                inhProd, inhCons, null, null, classLits));
                        continue;
                    }
                    scanLocatorType(returnCls, locatorPath, inhProd, inhCons,
                            cls, new java.util.ArrayList<>(java.util.List.of(m)),
                            classLits, new java.util.HashSet<>(), out);
                    continue;
                }
                String full = (sub == null) ? basePath : combine(basePath, normalize(sub.value()));
                Set<String> methodProduces = produces(m.getAnnotation(Produces.class));
                Set<String> methodConsumes = consumes(m.getAnnotation(Consumes.class));
                Set<String> effProd = methodProduces.isEmpty() ? classProduces : methodProduces;
                Set<String> effCons = methodConsumes.isEmpty() ? classConsumes : methodConsumes;
                m.setAccessible(true);
                out.add(new ResourceMethod(cls, m, verb, UriTemplate.compile(full),
                        effProd, effCons, null, null, classLits));
            }
        }
        return out;
    }

    /**
     * §3.6 : les annotations JAX-RS portées par une super-classe ou une
     * interface sont héritées. On collecte les méthodes et on remplace
     * chacune par sa version la plus dérivée portant au moins une
     * annotation JAX-RS reconnue.
     */
    private static java.util.List<Method> collectInheritedMethods(Class<?> cls) {
        java.util.LinkedHashMap<String, Method> merged = new java.util.LinkedHashMap<>();
        // 1. méthodes déclarées directement
        for (Method m : cls.getDeclaredMethods()) {
            merged.put(signature(m), effectiveMethod(m, cls));
        }
        // 2. méthodes de la hiérarchie héritées (super-classes + interfaces)
        for (Method m : cls.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            String sig = signature(m);
            if (merged.containsKey(sig)) continue;
            Method eff = effectiveMethod(m, cls);
            merged.put(sig, eff);
        }
        return new java.util.ArrayList<>(merged.values());
    }

    /** Signature = nom + types de param (ignore le type de retour). */
    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        for (Class<?> p : m.getParameterTypes()) sb.append(p.getName()).append(',');
        return sb.append(')').toString();
    }

    /** Retourne la méthode la plus dérivée dans la hiérarchie de {@code cls}
     *  correspondant à la même signature que {@code m}, en priorisant celle
     *  qui porte une annotation JAX-RS (héritage §3.6). */
    private static Method effectiveMethod(Method m, Class<?> cls) {
        if (hasJaxrsAnnotation(m)) return m;
        // Remonter super-classes
        Class<?> sup = cls.getSuperclass();
        while (sup != null && sup != Object.class) {
            try {
                Method parent = sup.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (hasJaxrsAnnotation(parent)) return parent;
            } catch (NoSuchMethodException ignored) {}
            sup = sup.getSuperclass();
        }
        // Remonter interfaces
        for (Class<?> iface : cls.getInterfaces()) {
            try {
                Method parent = iface.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (hasJaxrsAnnotation(parent)) return parent;
            } catch (NoSuchMethodException ignored) {}
        }
        return m;
    }

    private static boolean hasJaxrsAnnotation(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (a.annotationType().getName().startsWith("jakarta.ws.rs.")) return true;
        }
        return false;
    }

    /** Compte les caractères littéraux hors {templates} dans un path. */
    private static int countLiterals(String path) {
        if (path == null) return 0;
        int n = 0; int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { if (depth > 0) depth--; }
            else if (depth == 0) n++;
        }
        return n;
    }

    private static String resolveHttpMethod(Method m) {
        for (Class<?> verb : BUILTIN_VERBS) {
            @SuppressWarnings("unchecked")
            Class<? extends Annotation> annType = (Class<? extends Annotation>) verb;
            if (m.isAnnotationPresent(annType)) {
                return verb.getAnnotation(HttpMethod.class).value();
            }
        }
        for (Annotation a : m.getAnnotations()) {
            HttpMethod meta = a.annotationType().getAnnotation(HttpMethod.class);
            if (meta != null) return meta.value();
        }
        return null;
    }

    private static void scanLocatorType(Class<?> cls, String basePath,
                                        Set<String> inheritedProduces, Set<String> inheritedConsumes,
                                        Class<?> rootBeanClass, java.util.List<Method> locatorChain,
                                        int rootClassLiterals,
                                        java.util.Set<Class<?>> visited, List<ResourceMethod> out) {
        if (cls == null || cls == Object.class) return;
        if (!visited.add(cls)) return; // cycle détecté
        Set<String> clsProduces = produces(cls.getAnnotation(Produces.class));
        if (clsProduces.isEmpty()) clsProduces = inheritedProduces;
        Set<String> clsConsumes = consumes(cls.getAnnotation(Consumes.class));
        if (clsConsumes.isEmpty()) clsConsumes = inheritedConsumes;
        for (Method m : collectInheritedMethods(cls)) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            String verb = resolveHttpMethod(m);
            Path sub = m.getAnnotation(Path.class);
            if (verb == null) {
                // Sous-locator de niveau N+1 : §3.4.1 récursion
                if (sub == null) continue;
                Class<?> nestedReturn = m.getReturnType();
                if (nestedReturn == void.class || nestedReturn == null) continue;
                String nestedPath = combine(basePath, normalize(sub.value()));
                Set<String> np = produces(m.getAnnotation(Produces.class));
                Set<String> nc = consumes(m.getAnnotation(Consumes.class));
                Set<String> inhP = np.isEmpty() ? clsProduces : np;
                Set<String> inhC = nc.isEmpty() ? clsConsumes : nc;
                m.setAccessible(true);
                if (jakarta.ws.rs.core.Response.class.isAssignableFrom(nestedReturn)) {
                    java.util.List<Method> chain = java.util.List.copyOf(locatorChain);
                    out.add(new ResourceMethod(cls, m, "*", UriTemplate.compile(nestedPath),
                            inhP, inhC, rootBeanClass, chain, rootClassLiterals));
                    continue;
                }
                java.util.List<Method> extended = new java.util.ArrayList<>(locatorChain);
                extended.add(m);
                scanLocatorType(nestedReturn, nestedPath, inhP, inhC,
                        rootBeanClass, extended, rootClassLiterals,
                        new java.util.HashSet<>(visited), out);
                continue;
            }
            String full = (sub == null) ? basePath : combine(basePath, normalize(sub.value()));
            Set<String> mp = produces(m.getAnnotation(Produces.class));
            Set<String> mc = consumes(m.getAnnotation(Consumes.class));
            Set<String> effP = mp.isEmpty() ? clsProduces : mp;
            Set<String> effC = mc.isEmpty() ? clsConsumes : mc;
            m.setAccessible(true);
            out.add(new ResourceMethod(cls, m, verb, UriTemplate.compile(full), effP, effC,
                    rootBeanClass, java.util.List.copyOf(locatorChain), rootClassLiterals));
        }
    }

    private static Set<String> produces(Produces ann) {
        if (ann == null || ann.value().length == 0) return Set.of();
        return new LinkedHashSet<>(List.of(ann.value()));
    }

    private static Set<String> consumes(Consumes ann) {
        if (ann == null || ann.value().length == 0) return Set.of();
        return new LinkedHashSet<>(List.of(ann.value()));
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String combine(String base, String sub) {
        if ("/".equals(sub)) return base;
        if ("/".equals(base)) return sub;
        return base + sub;
    }
}
