package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.gen.RouteDescriptor;
import io.vidocq.cassini.spi.gen.RouteProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the route table for a set of {@code @Path} resource classes.
 *
 * <h2>Lookup sequence per class</h2>
 * <ol>
 *   <li>Try {@code Class.forName(cls.getName() + "$$CassiniRoutes")} — if present AND
 *       {@link RouteProvider#hasLocators()} is {@code false}, convert its
 *       {@link RouteDescriptor}s to {@link ResourceMethod}s and use them.</li>
 *   <li>Fallback: {@link ResourceScanner#discover(Class...)} for that class.</li>
 * </ol>
 *
 * <p>The conversion from {@link RouteDescriptor} to {@link ResourceMethod} performs a
 * targeted {@code beanClass.getDeclaredMethod(name, paramTypes)} call (not an annotation
 * scan) plus {@code UriTemplate.compile(pathTemplate)}. This is trivially
 * AOT-registerable (GraalVM {@code reflect-config.json}) unlike a full annotation scan.</p>
 *
 * <p>Thread safety: this class is used only at startup (single-threaded build phase).</p>
 */
public final class RouteRegistry {

    private static final Logger LOG = Logger.getLogger(RouteRegistry.class.getName());
    private static final String ROUTES_SUFFIX = "$$CassiniRoutes";

    /** Classes that used generated routes (pre-generated path). */
    private static final AtomicInteger PRE_GENERATED_HITS = new AtomicInteger();
    /** Classes that fell back to ResourceScanner. */
    private static final AtomicInteger SCANNER_FALLBACK_HITS = new AtomicInteger();

    public static int preGeneratedHits() { return PRE_GENERATED_HITS.get(); }
    public static int scannerFallbackHits() { return SCANNER_FALLBACK_HITS.get(); }
    public static void resetCounters() { PRE_GENERATED_HITS.set(0); SCANNER_FALLBACK_HITS.set(0); }

    private RouteRegistry() {}

    /**
     * Discovers routes for all given {@code @Path} classes, using generated providers
     * where available and falling back to {@link ResourceScanner} per class.
     *
     * @param pathClasses the set of {@code @Path} resource classes
     * @return the aggregated list of {@link ResourceMethod}s
     */
    public static List<ResourceMethod> discover(Class<?>... pathClasses) {
        List<ResourceMethod> out = new ArrayList<>();
        for (Class<?> cls : pathClasses) {
            List<ResourceMethod> classRoutes = discoverClass(cls);
            out.addAll(classRoutes);
        }
        return out;
    }

    private static List<ResourceMethod> discoverClass(Class<?> cls) {
        // 1. Try pre-generated $$CassiniRoutes
        String providerName = cls.getName() + ROUTES_SUFFIX;
        try {
            Class<?> providerClass = Class.forName(providerName, false, cls.getClassLoader());
            RouteProvider provider = (RouteProvider) providerClass.getDeclaredConstructor().newInstance();

            if (provider.hasLocators()) {
                // Class has locators — fall back to full scanner for correctness
                LOG.fine("RouteRegistry: " + cls.getName() + " has locators, falling back to ResourceScanner");
                SCANNER_FALLBACK_HITS.incrementAndGet();
                return ResourceScanner.discover(cls);
            }

            List<RouteDescriptor> descriptors = provider.routes();
            List<ResourceMethod> routes = convertDescriptors(descriptors, cls);
            PRE_GENERATED_HITS.incrementAndGet();
            LOG.fine("RouteRegistry: " + cls.getName() + " used generated routes (" + routes.size() + " methods)");
            return routes;

        } catch (ClassNotFoundException ignored) {
            // No pre-generated provider — fall through to scanner
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RouteRegistry: failed to use generated routes for "
                    + cls.getName() + " — falling back to ResourceScanner: " + e.getMessage(), e);
        }

        // 2. Fallback: ResourceScanner
        SCANNER_FALLBACK_HITS.incrementAndGet();
        LOG.fine("RouteRegistry: " + cls.getName() + " used ResourceScanner (no generated provider)");
        return ResourceScanner.discover(cls);
    }

    /**
     * Converts {@link RouteDescriptor}s to {@link ResourceMethod}s.
     * Uses targeted {@code getDeclaredMethod} (not a scan) and {@code UriTemplate.compile}.
     */
    private static List<ResourceMethod> convertDescriptors(List<RouteDescriptor> descriptors, Class<?> rootCls) {
        List<ResourceMethod> routes = new ArrayList<>(descriptors.size());
        for (RouteDescriptor d : descriptors) {
            try {
                Method m = resolveMethod(d.beanClass(), d.methodName(), d.paramTypeNames());
                m.setAccessible(true);
                Set<String> produces = toSet(d.produces());
                Set<String> consumes = toSet(d.consumes());
                routes.add(new ResourceMethod(
                        d.beanClass(), m, d.httpMethod(),
                        UriTemplate.compile(d.pathTemplate()),
                        produces, consumes,
                        null, null, d.classPathLiterals()));
            } catch (Exception e) {
                LOG.log(Level.WARNING, "RouteRegistry: failed to resolve method "
                        + d.beanClass().getName() + "." + d.methodName()
                        + " — falling back to ResourceScanner for class " + rootCls.getName(), e);
                // If any method fails, fall back to the scanner for the whole class
                SCANNER_FALLBACK_HITS.incrementAndGet();
                if (PRE_GENERATED_HITS.get() > 0) PRE_GENERATED_HITS.decrementAndGet();
                return ResourceScanner.discover(rootCls);
            }
        }
        return routes;
    }

    private static Method resolveMethod(Class<?> beanClass, String methodName, String[] paramTypeNames)
            throws Exception {
        Class<?>[] paramTypes = new Class<?>[paramTypeNames.length];
        for (int i = 0; i < paramTypeNames.length; i++) {
            paramTypes[i] = resolveType(paramTypeNames[i], beanClass.getClassLoader());
        }
        return beanClass.getDeclaredMethod(methodName, paramTypes);
    }

    private static Class<?> resolveType(String name, ClassLoader cl) throws ClassNotFoundException {
        return switch (name) {
            case "boolean" -> boolean.class;
            case "byte"    -> byte.class;
            case "short"   -> short.class;
            case "int"     -> int.class;
            case "long"    -> long.class;
            case "float"   -> float.class;
            case "double"  -> double.class;
            case "char"    -> char.class;
            case "void"    -> void.class;
            default        -> name.endsWith("[]")
                    ? resolveArrayType(name, cl)
                    : Class.forName(name, false, cl);
        };
    }

    private static Class<?> resolveArrayType(String name, ClassLoader cl) throws ClassNotFoundException {
        // Convert "int[]" → "[I", "java.lang.String[]" → "[Ljava.lang.String;"
        String component = name.substring(0, name.length() - 2);
        Class<?> comp = resolveType(component, cl);
        return java.lang.reflect.Array.newInstance(comp, 0).getClass();
    }

    private static Set<String> toSet(String[] arr) {
        if (arr == null || arr.length == 0) return Set.of();
        Set<String> set = new LinkedHashSet<>();
        for (String s : arr) set.add(s);
        return set;
    }
}
