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
import jakarta.ws.rs.ext.Provider;

import java.lang.annotation.Annotation;

/**
 * {@link BuildCompatibleExtension} that aligns a CDI container with JAX-RS 4.0 spec §11.2.5:
 * classes annotated with {@code @Path} or {@code @Provider} must be discoverable
 * as managed CDI beans even without an explicit scope. Without this BCE, a generic
 * CDI container (Vauban, Weld) leaves these classes out of the beans when they have
 * no bean-defining annotation, so Cassini cannot find them through
 * {@link io.vidocq.cassini.spi.bean.BeanProvider#getResourceClasses()}.
 *
 * <p>The extension only visits classes the container discovers: in {@code annotated}
 * discovery mode, a {@code @Path} class with no bean-defining annotation is not one,
 * so give it a scope or list it in a {@code beans.xml} with {@code bean-discovery-mode="all"}.</p>
 *
 * <ul>
 *   <li>{@code Application} subclass without a scope → {@code @ApplicationScoped}; a generic one
 *       gets {@code @Dependent}, as below</li>
 *   <li>{@code @Path} without a scope → {@code @RequestScoped} (standard JAX-RS semantics:
 *       a new resource instance per request); interfaces, such as Rest Client contracts, are
 *       left alone. A generic class gets {@code @Dependent}, the only scope CDI 4.1 §3.1 allows a
 *       generic managed bean: Cassini still asks for one instance per request (CASSINI-065)</li>
 *   <li>{@code @Provider} without a scope → {@code @Dependent} (JAX-RS-side singleton equivalent:
 *       providers are singleton per class by default — {@code @Dependent}
 *       allows on-demand instantiation without the overhead of a normal-scope proxy)</li>
 * </ul>
 *
 * <p>This BCE is Cassini's counterpart to the standard CDI "bean-defining
 * annotation" rule. It avoids forcing a generic CDI container to know
 * about {@code jakarta.ws.rs} and keeps the separation of concerns clean. It moved from
 * {@code cassini-cdi-vauban} to this container-neutral module (cassini#53).</p>
 */
public class CassiniScopeExtension implements BuildCompatibleExtension {

    private static final System.Logger LOG = System.getLogger(CassiniScopeExtension.class.getName());

    /**
     * {@code Application} subclass without a scope → {@code @ApplicationScoped}
     * (JAX-RS §2.3: the Application is a singleton component the container
     * instantiates; the runtime reads its {@code @ApplicationPath} to mount).
     */
    @SuppressWarnings("unused")
    @Enhancement(types = jakarta.ws.rs.core.Application.class, withSubtypes = true)
    public void addApplicationDefaultScope(ClassConfig clazz) {
        if (clazz.info().isAbstract()) {
            return;
        }
        addDefaultScope(clazz, "Application subclass", ApplicationScoped.class);
    }

    /**
     * {@code @Path} class without a scope → {@code @RequestScoped}. An interface is skipped: it is a
     * client contract (MicroProfile Rest Client), and a scope would make the container generate a
     * client proxy for it (cassini#59).
     */
    @SuppressWarnings("unused")
    @Enhancement(types = Object.class, withAnnotations = Path.class)
    public void addPathDefaultScope(ClassConfig clazz) {
        if (clazz.info().isInterface()) return;
        addDefaultScope(clazz, "@Path class", RequestScoped.class);
    }

    /** {@code @Provider} class without a scope → {@code @Dependent}; an interface is skipped. */
    @SuppressWarnings("unused")
    @Enhancement(types = Object.class, withAnnotations = Provider.class)
    public void addProviderDefaultScope(ClassConfig clazz) {
        if (clazz.info().isInterface()) return;
        // Exclude classes that are also @Path (already handled by addPathDefaultScope)
        if (clazz.info().hasAnnotation(Path.class)) return;
        addDefaultScope(clazz, "@Provider class", Dependent.class);
    }

    /**
     * Gives {@code clazz}, when it has no scope, {@code scope}, or {@code @Dependent} when it is generic: a generic
     * managed bean must be {@code @Dependent} (CDI 4.1 §3.1), and any other scope fails the deployment (CASSINI-065).
     */
    private static void addDefaultScope(ClassConfig clazz, String kind, Class<? extends Annotation> scope) {
        if (hasAnyScope(clazz)) return;
        Class<? extends Annotation> given = clazz.info().typeParameters().isEmpty() ? scope : Dependent.class;
        clazz.addAnnotation(given);
        LOG.log(System.Logger.Level.DEBUG, "  {0} {1} has no CDI scope, defaulting to @{2}",
                kind, clazz.info().name(), given.getSimpleName());
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
