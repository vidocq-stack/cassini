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
package io.vidocq.cassini.cdi.vauban;

import io.vidocq.cassini.cdi.CassiniScopeExtension;
import io.vidocq.cassini.cdi.CdiBeanProvider;
import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A bounded generic {@code @Path} class without a scope gets {@code @Dependent} from {@code CassiniScopeExtension}:
 * CDI 4.1 §3.1 makes a generic managed bean {@code @Dependent}, and Vauban treats any other scope as a definition
 * error (Vauban BUG-20261117-35). The extension gave it {@code @RequestScoped}, and both providers looked behind its
 * client proxy (CASSINI-061); the container no longer starts with that scope (CASSINI-065). Its raw class resolves no
 * bean through the {@code BeanManager}, and the bean is found by its bean class: both providers hand out a new
 * instance per lookup, the instance itself, as for any {@code @Dependent} bean.
 */
class ScopelessBoundedGenericBeanTest {

    /** Declares no scope: the extension gives it {@code @Dependent}. */
    @Path("/scopeless")
    public static class ScopelessResource<T extends Number> {
        @GET
        public String get() {
            return "scopeless";
        }
    }

    private VaubanContainer container;
    private RequestContextController requestContext;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder()
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(ScopelessResource.class)
                .build();
        requestContext = CDI.current().select(RequestContextController.class).get();
        requestContext.activate();
    }

    @AfterEach
    void stop() {
        requestContext.deactivate();
        container.close();
    }

    @Test
    void theExtensionGivesItDependent() {
        var beans = container.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE).stream()
                .filter(bean -> bean.getBeanClass() == ScopelessResource.class)
                .toList();

        assertEquals(1, beans.size(), "the beans of the class");
        assertEquals(Dependent.class, beans.getFirst().getScope(), "the scope the extension gave it");
    }

    @Test
    void vaubanBeanProviderHandsOutTheInstance() {
        assertHandsOutTheInstance(new VaubanBeanProvider(container));
    }

    @Test
    void cdiBeanProviderHandsOutTheInstance() {
        assertHandsOutTheInstance(new CdiBeanProvider(CDI.current()));
    }

    private static void assertHandsOutTheInstance(BeanProvider provider) {
        provider.getResourceClasses();
        Object instance = provider.getBean(ScopelessResource.class);
        assertSame(ScopelessResource.class, instance.getClass(), "the instance, no client proxy");
        assertSame(instance, provider.contextualInstance(ScopelessResource.class, instance), "nothing to look behind");
        assertNotSame(instance, provider.getBean(ScopelessResource.class), "a new instance on each lookup");
    }
}
