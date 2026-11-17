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
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Vauban BUG-20261117-35-aware, not BUG-35-proof. A bounded generic {@code @Path} class without a scope gets
 * {@code @RequestScoped} from {@code CassiniScopeExtension}, a definition error under CDI 4.1 §3.1 that Vauban does
 * not report. Both providers serve it as they serve a request-scoped bean its raw class resolves: its client proxy,
 * behind which {@code contextualInstance} finds the instance of the request, where Cassini writes the {@code @Context}
 * fields. The raw class resolves no bean through the {@code BeanManager}, on Vauban main as on vauban#335, and the
 * bean is found by its bean class (CASSINI-061).
 *
 * <p>When Vauban reports BUG-35's definition error, this container no longer starts: the Cassini companion of BUG-35
 * gives a generic {@code @Path} class {@code @Dependent} and adapts or drops this test.</p>
 */
@Tag("vauban-BUG-20261117-35")
class RequestScopedBoundedGenericBeanTest {

    /** Declares no scope: the extension gives it {@code @RequestScoped}. */
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
    void vaubanBeanProviderReachesItThroughItsClientProxy() {
        assertReachedThroughItsClientProxy(new VaubanBeanProvider(container));
    }

    @Test
    void cdiBeanProviderReachesItThroughItsClientProxy() {
        assertReachedThroughItsClientProxy(new CdiBeanProvider(CDI.current()));
    }

    private static void assertReachedThroughItsClientProxy(BeanProvider provider) {
        provider.getResourceClasses();
        Object proxy = provider.getBean(ScopelessResource.class);
        Object instance = provider.contextualInstance(ScopelessResource.class, proxy);
        assertNotSame(proxy, instance, "the client proxy, looked behind");
        assertSame(ScopelessResource.class, instance.getClass());
        assertSame(proxy, provider.getBean(ScopelessResource.class), "the same client proxy on each lookup");
        assertSame(instance, provider.contextualInstance(ScopelessResource.class, proxy),
                "the instance of the request");
    }
}
