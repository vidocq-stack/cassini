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
import io.vidocq.vauban.api.ClientProxyClass;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.ext.Provider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A normal-scoped provider or resource whose class has a final method has no client proxy typed by its class: CDI
 * 4.1 §3.10 makes the class unproxyable as a looked-up type, and Vauban refuses that lookup (BUG-20261118-19). The
 * bean provider threw, and Cassini made the provider or the resource with {@code new}, outside CDI: nothing injected
 * (CASSINI-066). The provider is now its client proxy, which implements its interfaces and states its class; the
 * resource of a request is its contextual instance.
 */
class FinalMethodProviderTest {

    /** What the filter reaches through injection. */
    @ApplicationScoped
    public static class Seen {
        final List<String> paths = new CopyOnWriteArrayList<>();
    }

    @Provider
    @ApplicationScoped
    public static class FinalFilter implements ContainerRequestFilter {
        @Inject
        Seen seen;

        @Override
        public void filter(ContainerRequestContext request) {
            seen.paths.add(name());
        }

        public final String name() {
            return "final filter";
        }
    }

    @Path("/final")
    @RequestScoped
    public static class FinalResource {
        @Inject
        Seen seen;

        @GET
        public String get() {
            return seen == null ? "not injected" : "injected";
        }

        public final String tag() {
            return "final resource";
        }
    }

    private VaubanContainer container;
    private VaubanBeanProvider provider;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder()
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(Seen.class)
                .addBeanClass(FinalFilter.class)
                .addBeanClass(FinalResource.class)
                .build();
        provider = new VaubanBeanProvider(container);
    }

    @AfterEach
    void stop() {
        container.close();
    }

    @Test
    void aProviderIsItsClientProxyAndStatesItsClass() throws Exception {
        Object filter = provider.getBean(FinalFilter.class);
        assertEquals(Optional.of(FinalFilter.class), ClientProxyClass.beanClassOf(filter.getClass()));
        assertInstanceOf(ContainerRequestFilter.class, filter).filter(null);
        assertEquals(List.of("final filter"), container.select(Seen.class).paths, "run on the injected instance");
        assertSame(filter, provider.getBean(FinalFilter.class), "one client proxy");
    }

    @Test
    void aResourceOfARequestIsItsContextualInstance() {
        container.requestContext().activate();
        try {
            Object resource = provider.getRequestBean(FinalResource.class).instance();
            assertEquals("injected", assertInstanceOf(FinalResource.class, resource).get());
            assertSame(resource, provider.getRequestBean(FinalResource.class).instance(), "the request's instance");
        } finally {
            container.requestContext().deactivate();
        }
    }
}
