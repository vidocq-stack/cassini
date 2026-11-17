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
import jakarta.enterprise.context.Dependent;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * CASSINI-061: what one {@link VaubanBeanProvider#getBean(Class)} of a {@code @Dependent} resource costs, for a plain
 * class and for a bounded generic one, which vauban#335 makes the provider find by its bean class. Per class: 50 000
 * unmeasured lookups, the bytes the thread allocates over 10 000, then three rounds of 200 000 timed lookups.
 *
 * <p>Opt-in: {@code ./mvnw -ntp -pl cassini-cdi-vauban -am test -Dtest=BeanLookupBenchIT
 * -Dsurefire.failIfNoSpecifiedTests=false -Dcassini.bench=true}. The figures go to {@code BENCH.md}.</p>
 */
@EnabledIfSystemProperty(named = "cassini.bench", matches = "true")
class BeanLookupBenchIT {

    private static final int WARM_UP = 50_000;
    private static final int ALLOCATION_CALLS = 10_000;
    private static final int TIMED_CALLS = 200_000;

    /** Where each lookup goes, so that the JIT keeps it. */
    static volatile Object sink;

    @Path("/plain")
    @Dependent
    public static class PlainResource {
        @GET
        public String get() {
            return "plain";
        }
    }

    @Path("/numbers")
    @Dependent
    public static class NumberResource<T extends Number> {
        @GET
        public String get() {
            return "number";
        }
    }

    @Test
    void oneLookupOfAPlainAndOfABoundedGenericDependentResource() throws ReflectiveOperationException {
        try (var container = VaubanContainer.builder().addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(PlainResource.class).addBeanClass(NumberResource.class).build()) {
            var provider = new VaubanBeanProvider(container);
            provider.getResourceClasses();
            for (Class<?> resource : new Class<?>[] {PlainResource.class, NumberResource.class}) {
                try {
                    measure(provider, resource);
                } catch (IllegalArgumentException e) {
                    System.out.println("BENCH " + resource.getSimpleName() + " fails: " + e);
                }
            }
            assertInstanceOf(PlainResource.class, provider.getBean(PlainResource.class));
        }
    }

    /**
     * Prints the bytes and the nanoseconds per lookup. The allocation counter is read through reflection: the patched
     * test module does not read {@code jdk.management}, and a bench is no reason to make it.
     */
    private static void measure(VaubanBeanProvider provider, Class<?> resource) throws ReflectiveOperationException {
        Runnable lookup = () -> sink = provider.getBean(resource);
        for (int i = 0; i < WARM_UP; i++) lookup.run();
        Object threads = Class.forName("java.lang.management.ManagementFactory")
                .getMethod("getThreadMXBean").invoke(null);
        Method allocated = Class.forName("com.sun.management.ThreadMXBean")
                .getMethod("getCurrentThreadAllocatedBytes");
        long before = (long) allocated.invoke(threads);
        for (int i = 0; i < ALLOCATION_CALLS; i++) lookup.run();
        long bytes = ((long) allocated.invoke(threads) - before) / ALLOCATION_CALLS;
        StringBuilder nanos = new StringBuilder();
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < TIMED_CALLS; i++) lookup.run();
            nanos.append(round == 0 ? "" : " / ").append((System.nanoTime() - start) / TIMED_CALLS);
        }
        System.out.println("BENCH " + resource.getSimpleName() + " bytes/call=" + bytes + " ns/call=" + nanos);
    }
}
