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
 * Scans classes annotated with {@code @Path} to produce the list of
 * addressable {@link ResourceMethod}s.
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
            if (root != null) scanRootClass(cls, normalize(root.value()), out);
        }
        return out;
    }

    /** Media types a class, a locator or a method declares, falling back to the enclosing ones
     *  (§3.5: method {@code @Produces}/{@code @Consumes} override the class ones). */
    private record MediaRanges(Set<String> produces, Set<String> consumes) {
        static final MediaRanges NONE = new MediaRanges(Set.of(), Set.of());

        MediaRanges overriddenBy(java.lang.reflect.AnnotatedElement element) {
            Set<String> p = ResourceScanner.produces(element.getAnnotation(Produces.class));
            Set<String> c = ResourceScanner.consumes(element.getAnnotation(Consumes.class));
            return new MediaRanges(p.isEmpty() ? produces : p, c.isEmpty() ? consumes : c);
        }
    }

    /** The sub-resource locators leading to a class: the root resource class, the locator methods
     *  invoked from it, and the literal characters of the root {@code @Path}. */
    private record Locators(Class<?> rootBeanClass, java.util.List<Method> chain, int rootClassLiterals) {
        Locators then(Method locator) {
            java.util.List<Method> extended = new java.util.ArrayList<>(chain);
            extended.add(locator);
            return new Locators(rootBeanClass, extended, rootClassLiterals);
        }
    }

    private static void scanRootClass(Class<?> cls, String basePath, List<ResourceMethod> out) {
        int classLits = countLiterals(basePath);
        MediaRanges classMedia = MediaRanges.NONE.overriddenBy(cls);
        for (Method m : collectInheritedMethods(cls)) {
            // §3.3.1: a resource method must be public.
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            String verb = resolveHttpMethod(m);
            Path sub = m.getAnnotation(Path.class);
            if (verb != null) {
                String full = (sub == null) ? basePath : combine(basePath, normalize(sub.value()));
                MediaRanges eff = classMedia.overriddenBy(m);
                out.add(new ResourceMethod(cls, accessible(m), verb, UriTemplate.compile(full),
                        eff.produces(), eff.consumes(), null, null, classLits));
            } else if (sub != null) {
                // Sub-resource locator §3.4.1: @Path on a method WITHOUT an HTTP verb
                // → the method returns an instance whose routes we scan
                //   by prefixing with the current path + @Path(method).
                scanRootLocator(cls, m, combine(basePath, normalize(sub.value())), classMedia, classLits, out);
            }
        }
    }

    private static void scanRootLocator(Class<?> cls, Method m, String locatorPath, MediaRanges classMedia,
                                        int classLits, List<ResourceMethod> out) {
        Class<?> returnCls = m.getReturnType();
        if (returnCls == void.class || returnCls == null) return;
        // §3.4.2: a locator may return Class<T> — we unwrap
        // T through the generic type to scan its @Path entries.
        if (returnCls == Class.class
                && m.getGenericReturnType() instanceof java.lang.reflect.ParameterizedType pt
                && pt.getActualTypeArguments().length == 1
                && pt.getActualTypeArguments()[0] instanceof Class<?> argCls) {
            returnCls = argCls;
        }
        // @Produces/@Consumes inheritance: locator method > class.
        MediaRanges inherited = classMedia.overriddenBy(m);
        accessible(m);
        // §3.4.1: if the return type is Response, the locator IS the
        // terminal handler — create a direct "*" route (all HTTP methods).
        if (jakarta.ws.rs.core.Response.class.isAssignableFrom(returnCls)) {
            out.add(new ResourceMethod(cls, m, "*", UriTemplate.compile(locatorPath),
                    inherited.produces(), inherited.consumes(), null, null, classLits));
            return;
        }
        scanLocatorType(returnCls, locatorPath, inherited,
                new Locators(cls, java.util.List.of(m), classLits), new java.util.HashSet<>(), out);
    }

    /** Opens a resource or locator method to the reflective invocation fallback of the Invoker. */
    private static Method accessible(Method m) {
        m.setAccessible(true);
        return m;
    }

    /**
     * §3.6: JAX-RS annotations carried by a superclass or interface are
     * inherited. We collect methods and replace each one with its most-derived
     * version carrying at least one recognized JAX-RS annotation.
     */
    private static java.util.List<Method> collectInheritedMethods(Class<?> cls) {
        java.util.LinkedHashMap<String, Method> merged = new java.util.LinkedHashMap<>();
        // 1. methods declared directly
        for (Method m : cls.getDeclaredMethods()) {
            merged.put(signature(m), effectiveMethod(m, cls));
        }
        // 2. inherited hierarchy methods (superclasses + interfaces)
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

    /** Returns the most-derived method in the hierarchy of {@code cls}
     *  matching the same signature as {@code m}, prioritizing the one
     *  carrying a JAX-RS annotation (§3.6 inheritance). */
    private static Method effectiveMethod(Method m, Class<?> cls) {
        if (hasJaxrsAnnotation(m)) return m;
        // Walk up super-classes
        Class<?> sup = cls.getSuperclass();
        while (sup != null && sup != Object.class) {
            try {
                Method parent = sup.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (hasJaxrsAnnotation(parent)) return parent;
            } catch (NoSuchMethodException _) { /* no such method: the next candidate is tried */ }
            sup = sup.getSuperclass();
        }
        // Walk up interfaces
        for (Class<?> iface : cls.getInterfaces()) {
            try {
                Method parent = iface.getDeclaredMethod(m.getName(), m.getParameterTypes());
                if (hasJaxrsAnnotation(parent)) return parent;
            } catch (NoSuchMethodException _) { /* not declared by this interface: the next one is tried */ }
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
        int n = 0;
        int depth = 0;
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

    /** §3.4.1: maximum depth for recursive sub-resource locators.
     *  The TCK recursiveResourceLocatorTest sends 10 nested segments —
     *  we keep a comfortable margin. */
    private static final int MAX_RECURSIVE_DEPTH = 12;

    private static void scanLocatorType(Class<?> cls, String basePath, MediaRanges inherited,
                                        Locators locators, java.util.Set<Class<?>> visited,
                                        List<ResourceMethod> out) {
        if (cls == null) return;
        // §3.4.1: a sub-resource locator may return Object — its effective
        // type is only known at runtime. We emit a pair of catch-all routes
        // that trigger dynamic dispatch on the Invoker side:
        //   - basePath           → exact match (sub-resource without sub-segments)
        //   - basePath/{__rest:.*} → match with propagated sub-segments
        if (cls == Object.class) {
            emitDynamicLocatorRoute(basePath, inherited, locators, out);
            return;
        }
        if (!visited.add(cls) && !mayRevisit(cls, locators.chain())) return;
        MediaRanges clsMedia = inherited.overriddenBy(cls);
        for (Method m : collectInheritedMethods(cls)) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            String verb = resolveHttpMethod(m);
            Path sub = m.getAnnotation(Path.class);
            if (verb != null) {
                String full = (sub == null) ? basePath : combine(basePath, normalize(sub.value()));
                MediaRanges eff = clsMedia.overriddenBy(m);
                out.add(new ResourceMethod(cls, accessible(m), verb, UriTemplate.compile(full),
                        eff.produces(), eff.consumes(), locators.rootBeanClass(),
                        java.util.List.copyOf(locators.chain()), locators.rootClassLiterals()));
            } else if (sub != null) {
                // Level N+1 sub-locator: §3.4.1 recursion
                scanNestedLocator(cls, m, combine(basePath, normalize(sub.value())), clsMedia,
                        locators, visited, out);
            }
        }
    }

    /** §3.4.1: a recursive locator (cls returns the same class) must
     *  be able to match a nested URI. We allow up to
     *  MAX_RECURSIVE_DEPTH levels only if the last invoked locator
     *  declares the same type as cls (true self-recursion),
     *  not a simple revisit through another branch. */
    private static boolean mayRevisit(Class<?> cls, java.util.List<Method> locatorChain) {
        int depth = 0;
        for (Method m : locatorChain) if (m.getDeclaringClass() == cls) depth++;
        if (depth >= MAX_RECURSIVE_DEPTH || locatorChain.isEmpty()) return false;
        Method last = locatorChain.get(locatorChain.size() - 1);
        return last.getDeclaringClass() == cls || last.getReturnType() == cls;
    }

    private static void scanNestedLocator(Class<?> cls, Method m, String nestedPath, MediaRanges clsMedia,
                                          Locators locators, java.util.Set<Class<?>> visited,
                                          List<ResourceMethod> out) {
        Class<?> nestedReturn = m.getReturnType();
        if (nestedReturn == void.class || nestedReturn == null) return;
        MediaRanges inherited = clsMedia.overriddenBy(m);
        accessible(m);
        if (jakarta.ws.rs.core.Response.class.isAssignableFrom(nestedReturn)) {
            out.add(new ResourceMethod(cls, m, "*", UriTemplate.compile(nestedPath),
                    inherited.produces(), inherited.consumes(), locators.rootBeanClass(),
                    java.util.List.copyOf(locators.chain()), locators.rootClassLiterals()));
            return;
        }
        scanLocatorType(nestedReturn, nestedPath, inherited, locators.then(m),
                new java.util.HashSet<>(visited), out);
    }

    /** §3.4.1: emits 2 catch-all routes (exact path + sub-path) with
     *  {@code dynamicLocator=true} to signal to the {@link Invoker} that it
     *  must invoke the locator chain and then scan the effective class of the
     *  returned instance. The final method is resolved at runtime. */
    private static void emitDynamicLocatorRoute(String basePath, MediaRanges inherited, Locators locators,
                                                List<ResourceMethod> out) {
        java.util.List<Method> locatorChain = locators.chain();
        if (locatorChain == null || locatorChain.isEmpty() || locators.rootBeanClass() == null) return;
        Method last = locatorChain.get(locatorChain.size() - 1);
        java.util.List<Method> chain = java.util.List.copyOf(locatorChain);
        // Variant 1: exact path (e.g. GET /resource/l2locator → MainResourceLocator.get())
        out.add(new ResourceMethod(Object.class, last, "*",
                UriTemplate.compile(basePath),
                inherited.produces(), inherited.consumes(),
                locators.rootBeanClass(), chain, locators.rootClassLiterals(), true));
        // Variant 2: path + sub-segments (e.g. DELETE /resource/l2locator/l2locator)
        String wildcardPath = combine(basePath, "/{__rest:.*}");
        out.add(new ResourceMethod(Object.class, last, "*",
                UriTemplate.compile(wildcardPath),
                inherited.produces(), inherited.consumes(),
                locators.rootBeanClass(), chain, locators.rootClassLiterals(), true));
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
