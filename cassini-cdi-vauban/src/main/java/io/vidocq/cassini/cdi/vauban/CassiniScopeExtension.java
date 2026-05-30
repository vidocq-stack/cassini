package io.vidocq.cassini.cdi.vauban;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.ext.Provider;

/**
 * {@link BuildCompatibleExtension} that aligns Vauban with JAX-RS 4.0 spec §11.2.5:
 * classes annotated with {@code @Path} or {@code @Provider} must be discoverable
 * as managed CDI beans even without an explicit scope. Without this BCE, a generic
 * CDI container like Vauban ignores these classes during bean discovery (no
 * bean-defining annotation) and Cassini cannot find them via
 * {@link VaubanBeanProvider#getResourceClasses()}.
 *
 * <ul>
 *   <li>{@code @Path} without a scope → {@code @RequestScoped} (standard JAX-RS
 *       semantics: a fresh resource instance per request)</li>
 *   <li>{@code @Provider} without a scope → {@code @Dependent} (singleton-equivalent
 *       on the JAX-RS side: providers default to per-class singletons —
 *       {@code @Dependent} enables on-demand instantiation without the overhead of
 *       a normal-scope proxy)</li>
 * </ul>
 *
 * <p>This BCE is the Cassini counterpart of the standard CDI "bean-defining
 * annotation" rule. It spares Vauban (a generic CDI container) from knowing about
 * {@code jakarta.ws.rs} and keeps the separation of concerns clean.</p>
 */
public class CassiniScopeExtension implements BuildCompatibleExtension {

    private static final System.Logger LOG = System.getLogger(CassiniScopeExtension.class.getName());

    /** {@code @Path} without a scope → {@code @RequestScoped}. */
    @SuppressWarnings("unused")
    @Enhancement(types = Object.class, withAnnotations = Path.class)
    public void addPathDefaultScope(ClassConfig clazz) {
        if (!hasAnyScope(clazz)) {
            clazz.addAnnotation(RequestScoped.class);
            LOG.log(System.Logger.Level.INFO,
                    "  @Path class {0} has no CDI scope, defaulting to @RequestScoped",
                    clazz.info().name());
        }
    }

    /** {@code @Provider} without a scope → {@code @Dependent}. */
    @SuppressWarnings("unused")
    @Enhancement(types = Object.class, withAnnotations = Provider.class)
    public void addProviderDefaultScope(ClassConfig clazz) {
        // Skip classes that are also @Path (already handled by addPathDefaultScope)
        if (clazz.info().hasAnnotation(Path.class)) return;
        if (!hasAnyScope(clazz)) {
            clazz.addAnnotation(Dependent.class);
            LOG.log(System.Logger.Level.INFO,
                    "  @Provider class {0} has no CDI scope, defaulting to @Dependent",
                    clazz.info().name());
        }
    }

    private static boolean hasAnyScope(ClassConfig clazz) {
        var info = clazz.info();
        return info.hasAnnotation(RequestScoped.class)
                || info.hasAnnotation(ApplicationScoped.class)
                || info.hasAnnotation(SessionScoped.class)
                || info.hasAnnotation(Dependent.class)
                || info.hasAnnotation(Singleton.class);
    }
}
