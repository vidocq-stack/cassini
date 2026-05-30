package io.vidocq.cassini.internal;

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
 * Scans classes annotated {@code @Path} to produce the list of addressable
 * {@link ResourceMethod}s.
 */
public final class ResourceScanner {

    private static final Class<?>[] BUILTIN_VERBS = {
            GET.class, POST.class, PUT.class, DELETE.class,
            HEAD.class, OPTIONS.class, PATCH.class
    };

    private ResourceScanner() {}

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
                // §3.3.1: a resource method must be public.
                if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
                String verb = resolveHttpMethod(m);
                Path sub = m.getAnnotation(Path.class);
                // Sub-resource locator §3.4.1: @Path on method WITHOUT HTTP verb
                // → the method returns an instance whose routes we scan,
                //   prefixed by the current path + @Path(method).
                if (verb == null) {
                    if (sub == null) continue;
                    Class<?> returnCls = m.getReturnType();
                    if (returnCls == void.class || returnCls == null) continue;
                    // §3.4.2: a locator may return Class<T> — unwrap T via
                    // the generic type to scan its @Path.
                    if (returnCls == Class.class
                            && m.getGenericReturnType() instanceof java.lang.reflect.ParameterizedType pt
                            && pt.getActualTypeArguments().length == 1
                            && pt.getActualTypeArguments()[0] instanceof Class<?> argCls) {
                        returnCls = argCls;
                    }
                    String locatorPath = combine(basePath, normalize(sub.value()));
                    // @Produces/@Consumes inheritance: locator method > class.
                    Set<String> locatorProduces = produces(m.getAnnotation(Produces.class));
                    Set<String> locatorConsumes = consumes(m.getAnnotation(Consumes.class));
                    Set<String> inhProd = locatorProduces.isEmpty() ? classProduces : locatorProduces;
                    Set<String> inhCons = locatorConsumes.isEmpty() ? classConsumes : locatorConsumes;
                    m.setAccessible(true);
                    // §3.4.1: if the return type is Response, the locator IS the terminal
                    // handler — create a direct route "*" (all HTTP methods).
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
     * §3.6: JAX-RS annotations carried by a super-class or an interface are
     * inherited. Collect methods and replace each with its most derived
     * version that carries at least one recognized JAX-RS annotation.
     */
    private static java.util.List<Method> collectInheritedMethods(Class<?> cls) {
        java.util.LinkedHashMap<String, Method> merged = new java.util.LinkedHashMap<>();
        // 1. directly declared methods
        for (Method m : cls.getDeclaredMethods()) {
            merged.put(signature(m), effectiveMethod(m, cls));
        }
        // 2. inherited methods from the hierarchy (super-classes + interfaces)
        for (Method m : cls.getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            String sig = signature(m);
            if (merged.containsKey(sig)) continue;
            Method eff = effectiveMethod(m, cls);
            merged.put(sig, eff);
        }
        return new java.util.ArrayList<>(merged.values());
    }

    /** Signature = name + param types (ignores return type). */
    private static String signature(Method m) {
        StringBuilder sb = new StringBuilder(m.getName()).append('(');
        for (Class<?> p : m.getParameterTypes()) sb.append(p.getName()).append(',');
        return sb.append(')').toString();
    }

    /** Returns the most derived method in the hierarchy of {@code cls}
     *  matching the same signature as {@code m}, prioritizing one carrying
     *  a JAX-RS annotation (inheritance §3.6). */
    private static Method effectiveMethod(Method m, Class<?> cls) {
        if (hasJaxrsAnnotation(m)) return m;
        // Walk up super-classes
        Class<?> sup = cls.getSuperclass();
        while (sup != null && sup != Object.class) {
            try {
                Method parent = sup.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (hasJaxrsAnnotation(parent)) return parent;
            } catch (NoSuchMethodException ignored) {}
            sup = sup.getSuperclass();
        }
        // Walk up interfaces
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

    /** Counts literal characters outside {templates} in a path. */
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

    /** §3.4.1: max depth for recursive sub-resource locators.
     *  The TCK recursiveResourceLocatorTest sends 10 nested segments —
     *  take a comfortable margin. */
    private static final int MAX_RECURSIVE_DEPTH = 12;

    private static void scanLocatorType(Class<?> cls, String basePath,
                                        Set<String> inheritedProduces, Set<String> inheritedConsumes,
                                        Class<?> rootBeanClass, java.util.List<Method> locatorChain,
                                        int rootClassLiterals,
                                        java.util.Set<Class<?>> visited, List<ResourceMethod> out) {
        if (cls == null) return;
        // §3.4.1: a sub-resource locator may return Object — its effective
        // type is only known at runtime. Emit a pair of catch-all routes
        // that trigger dynamic dispatch in the Invoker:
        //   - basePath           → exact match (sub-resource without sub-segments)
        //   - basePath/{__rest:.*} → match with propagated sub-segments
        if (cls == Object.class) {
            emitDynamicLocatorRoute(basePath, inheritedProduces, inheritedConsumes,
                    rootBeanClass, locatorChain, rootClassLiterals, out);
            return;
        }
        if (!visited.add(cls)) {
            // §3.4.1: a recursive locator (cls returns the same class) must
            // be able to match a nested URI. Allow up to MAX_RECURSIVE_DEPTH
            // levels only if the last invoked locator declares the same type
            // as cls (true self-recursion), not a mere revisit via another
            // branch.
            int depth = 0;
            for (Method m : locatorChain) if (m.getDeclaringClass() == cls) depth++;
            if (depth >= MAX_RECURSIVE_DEPTH) return;
            if (locatorChain.isEmpty()) return;
            Method last = locatorChain.get(locatorChain.size() - 1);
            if (last.getDeclaringClass() != cls && last.getReturnType() != cls) return;
        }
        Set<String> clsProduces = produces(cls.getAnnotation(Produces.class));
        if (clsProduces.isEmpty()) clsProduces = inheritedProduces;
        Set<String> clsConsumes = consumes(cls.getAnnotation(Consumes.class));
        if (clsConsumes.isEmpty()) clsConsumes = inheritedConsumes;
        for (Method m : collectInheritedMethods(cls)) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            String verb = resolveHttpMethod(m);
            Path sub = m.getAnnotation(Path.class);
            if (verb == null) {
                // Sub-locator at level N+1: §3.4.1 recursion
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

    /** §3.4.1: emits 2 catch-all routes (exact path + sub-path) with
     *  {@code dynamicLocator=true} to signal to the {@link Invoker} that it
     *  must invoke the locator chain then scan the effective class of the
     *  returned instance. The final method is resolved at runtime. */
    private static void emitDynamicLocatorRoute(String basePath,
                                                Set<String> inheritedProduces, Set<String> inheritedConsumes,
                                                Class<?> rootBeanClass, java.util.List<Method> locatorChain,
                                                int rootClassLiterals, List<ResourceMethod> out) {
        if (locatorChain == null || locatorChain.isEmpty() || rootBeanClass == null) return;
        Method last = locatorChain.get(locatorChain.size() - 1);
        java.util.List<Method> chain = java.util.List.copyOf(locatorChain);
        // Variant 1: exact path (e.g. GET /resource/l2locator → MainResourceLocator.get())
        out.add(new ResourceMethod(Object.class, last, "*",
                UriTemplate.compile(basePath),
                inheritedProduces, inheritedConsumes,
                rootBeanClass, chain, rootClassLiterals, true));
        // Variant 2: path + sub-segments (e.g. DELETE /resource/l2locator/l2locator)
        String wildcardPath = combine(basePath, "/{__rest:.*}");
        out.add(new ResourceMethod(Object.class, last, "*",
                UriTemplate.compile(wildcardPath),
                inheritedProduces, inheritedConsumes,
                rootBeanClass, chain, rootClassLiterals, true));
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
