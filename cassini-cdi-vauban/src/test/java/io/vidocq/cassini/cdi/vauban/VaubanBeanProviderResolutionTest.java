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
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Dependent;
import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cassini asks the bean provider for the resource on every request, then for the contextual
 * instance behind it when it has fields to inject. The container resolved the bean on each
 * call, by walking every bean it knows, twice for the second: the answer never changes once the
 * container runs, and for a normal-scoped bean the container hands out the same client proxy.
 */
class VaubanBeanProviderResolutionTest {

    @Path("/app")
    @ApplicationScoped
    public static class AppResource {
        @GET
        public String get() {
            return "app";
        }
    }

    @Path("/request")
    @RequestScoped
    public static class RequestResource {
        @GET
        public String get() {
            return "request";
        }
    }

    @Path("/dependent")
    @Dependent
    public static class DependentResource {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @GET
        public String get() {
            return "dependent";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    @Path("/singleton")
    @jakarta.inject.Singleton
    public static class SingletonResource {
        static final AtomicInteger DESTROYED = new AtomicInteger();

        @GET
        public String get() {
            return "singleton";
        }

        @PreDestroy
        void destroyed() {
            DESTROYED.incrementAndGet();
        }
    }

    private VaubanContainer container;
    private VaubanBeanProvider provider;

    @BeforeEach
    void start() {
        container = VaubanContainer.builder()
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(AppResource.class)
                .addBeanClass(RequestResource.class)
                .addBeanClass(DependentResource.class)
                .addBeanClass(SingletonResource.class)
                .build();
        provider = new VaubanBeanProvider(container);
    }

    @AfterEach
    void stop() {
        container.close();
    }

    @Test
    void aNormalScopedResourceIsResolvedOnce() {
        Object first = provider.getBean(AppResource.class);
        assertSame(first, provider.getBean(AppResource.class));
        long perCall = bytesPerCall(() -> provider.getBean(AppResource.class));
        assertTrue(perCall < 32, "looking up an @ApplicationScoped resource allocates " + perCall + " bytes per call");
    }

    @Test
    void aDependentResourceIsStillCreatedOnEachLookup() {
        assertNotSame(provider.getBean(DependentResource.class), provider.getBean(DependentResource.class));
    }

    @Test
    void theContextualInstanceOfARequestScopedResourceIsFoundWithoutResolvingItAgain() {
        container.requestContext().activate();
        try {
            Object proxy = provider.getBean(RequestResource.class);
            Object instance = provider.contextualInstance(RequestResource.class, proxy);
            assertNotSame(proxy, instance, "the instance behind the client proxy");
            assertSame(instance, provider.contextualInstance(RequestResource.class, proxy));
            long perCall = bytesPerCall(() -> provider.contextualInstance(RequestResource.class, proxy));
            assertTrue(perCall < 64, "finding the contextual instance allocates " + perCall + " bytes per call");
        } finally {
            container.requestContext().deactivate();
        }
    }

    /** A resource for one request ends with it when it is {@code @Dependent}, and only then (CASSINI-062). */
    @Test
    void aDependentResourceForARequestIsDestroyedByItsRelease() {
        DependentResource.DESTROYED.set(0);
        var dependent = provider.getRequestBean(DependentResource.class);
        assertNotNull(dependent.release(), "a @Dependent resource is released");
        dependent.release().run();
        assertEquals(1, DependentResource.DESTROYED.get(), "destroyed by its release");
        var app = provider.getRequestBean(AppResource.class);
        assertSame(provider.getBean(AppResource.class), app.instance(), "the client proxy");
        assertNull(app.release(), "an @ApplicationScoped resource is left to its scope");
        assertThrows(IllegalArgumentException.class, () -> provider.getRequestBean(String.class));
    }

    /**
     * A {@code @Singleton} resource has no client proxy, and lives as long as the container: no release ends it, and
     * every request gets the same instance. Its release destroyed it at the end of each request (CASSINI-062, review).
     */
    @Test
    void aSingletonResourceForARequestIsNeverReleased() {
        SingletonResource.DESTROYED.set(0);
        var first = provider.getRequestBean(SingletonResource.class);
        var second = provider.getRequestBean(SingletonResource.class);
        if (first.release() != null) first.release().run();
        if (second.release() != null) second.release().run();
        assertAll(() -> assertNull(first.release(), "a @Singleton resource is released"),
                () -> assertEquals(0, SingletonResource.DESTROYED.get(), "destroyed"),
                () -> assertSame(first.instance(), second.instance(), "one instance"));
    }

    @Test
    void aDependentResourceIsItsOwnContextualInstance() {
        Object bean = provider.getBean(DependentResource.class);
        assertSame(bean, provider.contextualInstance(DependentResource.class, bean));
    }

    /** Mean bytes the current thread allocates per call, after a warm-up. HotSpot accounts them
     *  exactly; {@code com.sun.management} is reached through reflection because the module
     *  under test reads neither {@code java.management} nor {@code jdk.management}. */
    private static long bytesPerCall(Runnable call) {
        try {
            Object threads = Class.forName("java.lang.management.ManagementFactory")
                    .getMethod("getThreadMXBean").invoke(null);
            Method allocated = Class.forName("com.sun.management.ThreadMXBean")
                    .getMethod("getCurrentThreadAllocatedBytes");
            for (int i = 0; i < 2_000; i++) call.run();
            long before = (Long) allocated.invoke(threads);
            for (int i = 0; i < 10_000; i++) call.run();
            return ((Long) allocated.invoke(threads) - before) / 10_000;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("com.sun.management.ThreadMXBean is not available", e);
        }
    }
}
