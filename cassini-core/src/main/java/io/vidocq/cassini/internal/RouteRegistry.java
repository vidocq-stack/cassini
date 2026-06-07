package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.gen.RouteDescriptor;
import io.vidocq.cassini.spi.gen.RouteProvider;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Resolves the route table for a set of {@code @Path} resource classes.
 *
 * <h2>Lookup sequence per class</h2>
 * <ol>
 *   <li>A {@link ServiceLoader}-registered {@link RouteProvider} (module-path {@code provides
 *       io.vidocq.cassini.spi.gen.RouteProvider with <Class>$$CassiniRoutes} or a classpath
 *       {@code META-INF/services} entry), keyed by {@link RouteProvider#resourceClass()}. Lets a
 *       strict JPMS app keep its resource package <em>closed</em> — the module system instantiates
 *       the provider, so no {@code Class.forName} into the package.</li>
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

    /**
     * Lazily built map of {@code resourceClass → ServiceLoader-registered RouteProvider}. Built once
     * from {@code ServiceLoader.load(RouteProvider.class)}; {@code null} until the first scan. Empty
     * on the classpath / TCK (no {@code provides}), so the lookup falls through transparently.
     */
    private static volatile Map<Class<?>, RouteProvider> serviceProviders;

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
        // 1. ServiceLoader-registered provider (module-path `provides` / META-INF/services).
        //    The module system instantiates it even from a CLOSED package — no `opens`/`exports`.
        RouteProvider svc = serviceProviders().get(cls);
        if (svc != null) {
            LOG.fine("RouteRegistry: " + cls.getName() + " used ServiceLoader-registered routes");
            return fromProvider(svc, cls);
        }

        // 2. Try pre-generated $$CassiniRoutes by name
        String providerName = cls.getName() + ROUTES_SUFFIX;
        try {
            Class<?> providerClass = Class.forName(providerName, false, cls.getClassLoader());
            RouteProvider provider = (RouteProvider) providerClass.getDeclaredConstructor().newInstance();
            return fromProvider(provider, cls);
        } catch (ClassNotFoundException ignored) {
            // No pre-generated provider — fall through to scanner
        } catch (Exception e) {
            LOG.log(Level.WARNING, "RouteRegistry: failed to use generated routes for "
                    + cls.getName() + " — falling back to ResourceScanner: " + e.getMessage(), e);
        }

        // 3. Fallback: ResourceScanner
        SCANNER_FALLBACK_HITS.incrementAndGet();
        LOG.fine("RouteRegistry: " + cls.getName() + " used ResourceScanner (no generated provider)");
        return ResourceScanner.discover(cls);
    }

    /**
     * Converts a {@link RouteProvider}'s descriptors to {@link ResourceMethod}s, or falls back to
     * {@link ResourceScanner} when the class declares sub-resource locators. Shared by the
     * ServiceLoader and {@code Class.forName} branches.
     */
    private static List<ResourceMethod> fromProvider(RouteProvider provider, Class<?> cls) {
        if (provider.hasLocators()) {
            // Class has locators — fall back to full scanner for correctness
            LOG.fine("RouteRegistry: " + cls.getName() + " has locators, falling back to ResourceScanner");
            SCANNER_FALLBACK_HITS.incrementAndGet();
            return ResourceScanner.discover(cls);
        }
        List<ResourceMethod> routes = convertDescriptors(provider.routes(), cls);
        PRE_GENERATED_HITS.incrementAndGet();
        LOG.fine("RouteRegistry: " + cls.getName() + " used generated routes (" + routes.size() + " methods)");
        return routes;
    }

    /**
     * Returns the {@code resourceClass → RouteProvider} map of {@link ServiceLoader}-registered
     * providers, building it once on first use (double-checked locking). Empty when none is
     * registered (classpath / TCK), so the lookup falls through to {@code Class.forName} / scanner.
     */
    private static Map<Class<?>, RouteProvider> serviceProviders() {
        Map<Class<?>, RouteProvider> m = serviceProviders;
        if (m == null) {
            synchronized (RouteRegistry.class) {
                m = serviceProviders;
                if (m == null) {
                    m = loadServiceProviders();
                    serviceProviders = m;
                }
            }
        }
        return m;
    }

    private static Map<Class<?>, RouteProvider> loadServiceProviders() {
        Map<Class<?>, RouteProvider> map = new HashMap<>();
        Iterator<RouteProvider> it = ServiceLoader.load(RouteProvider.class).iterator();
        while (true) {
            RouteProvider p;
            try {
                if (!it.hasNext()) break;
                p = it.next();
            } catch (java.util.ServiceConfigurationError e) {
                LOG.log(Level.FINE, "RouteProvider ServiceLoader scan stopped early: " + e.getMessage(), e);
                break;
            }
            Class<?> rc;
            try {
                rc = p.resourceClass();
            } catch (Throwable t) {
                LOG.log(Level.FINE, "Skipping RouteProvider " + p.getClass().getName()
                        + " (resourceClass() failed): " + t.getMessage(), t);
                continue;
            }
            if (rc != null) {
                map.putIfAbsent(rc, p);
            }
        }
        if (!map.isEmpty()) {
            LOG.fine("RouteRegistry: " + map.size() + " ServiceLoader-registered route provider(s)");
        }
        return map;
    }

    /** Discards the cached ServiceLoader provider map so the next discover rebuilds it (tests). */
    static void resetServiceProviders() {
        serviceProviders = null;
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
                // Best-effort: a CLOSED resource package (zero-export JPMS app) rejects setAccessible,
                // but the generated adapter's invoke() handles dispatch, so reflective access is not
                // required. Only the reflective-fallback path (methodId == -1) would need it.
                try {
                    m.setAccessible(true);
                } catch (RuntimeException ignored) {
                    // InaccessibleObjectException — encapsulated package; adapter.invoke() covers it.
                }
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
