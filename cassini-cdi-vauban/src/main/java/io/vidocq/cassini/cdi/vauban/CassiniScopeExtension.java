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
 * {@link BuildCompatibleExtension} qui aligne Vauban sur la spec JAX-RS 4.0 §11.2.5 :
 * les classes annotées {@code @Path} ou {@code @Provider} doivent être discoverable
 * comme beans CDI managed même sans scope explicite. Sans cette BCE, un container
 * CDI générique comme Vauban ignore ces classes lors du bean discovery
 * (pas d'annotation bean-defining) et Cassini ne peut donc pas les retrouver
 * via {@link VaubanBeanProvider#getResourceClasses()}.
 *
 * <ul>
 *   <li>{@code @Path} sans scope → {@code @RequestScoped} (sémantique JAX-RS standard :
 *       une nouvelle instance de resource par requête)</li>
 *   <li>{@code @Provider} sans scope → {@code @Dependent} (singleton-équivalent côté
 *       JAX-RS : les providers sont par défaut singletons par classe — {@code @Dependent}
 *       permet l'instanciation à la demande sans surcharge de proxy normal-scope)</li>
 * </ul>
 *
 * <p>Cette BCE est le pendant Cassini de la règle CDI standard "bean-defining
 * annotation". Elle évite à Vauban (container CDI générique) de connaître
 * {@code jakarta.ws.rs} et garde la séparation des préoccupations propre.</p>
 */
public class CassiniScopeExtension implements BuildCompatibleExtension {

    private static final System.Logger LOG = System.getLogger(CassiniScopeExtension.class.getName());

    /** {@code @Path} sans scope → {@code @RequestScoped}. */
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

    /** {@code @Provider} sans scope → {@code @Dependent}. */
    @SuppressWarnings("unused")
    @Enhancement(types = Object.class, withAnnotations = Provider.class)
    public void addProviderDefaultScope(ClassConfig clazz) {
        // Exclure les classes qui sont aussi @Path (déjà gérées par addPathDefaultScope)
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
