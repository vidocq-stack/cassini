package io.vidocq.cassini.cdi;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.context.SessionScoped;
import jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension;
import jakarta.enterprise.inject.build.compatible.spi.ClassConfig;
import jakarta.enterprise.inject.build.compatible.spi.Enhancement;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Path;

/**
 * Ajoute {@code @RequestScoped} par défaut aux classes {@code @Path}
 * dépourvues de scope CDI explicite (Mode B uniquement).
 */
public class CassiniScopeExtension implements BuildCompatibleExtension {

    private static final System.Logger LOG = System.getLogger(CassiniScopeExtension.class.getName());

    @SuppressWarnings("unused")
    @Enhancement(types = Object.class, withAnnotations = Path.class)
    public void addDefaultScope(ClassConfig clazz) {
        var info = clazz.info();

        boolean hasScope = info.hasAnnotation(RequestScoped.class)
                || info.hasAnnotation(ApplicationScoped.class)
                || info.hasAnnotation(SessionScoped.class)
                || info.hasAnnotation(Dependent.class)
                || info.hasAnnotation(Singleton.class);

        if (!hasScope) {
            clazz.addAnnotation(RequestScoped.class);
            LOG.log(System.Logger.Level.INFO,
                    "  @Path class {0} has no CDI scope, defaulting to @RequestScoped",
                    info.name());
        }
    }
}
