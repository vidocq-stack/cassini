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
package io.vidocq.cassini.it.weld;

import io.vidocq.cassini.cdi.CdiBeanProvider;
import jakarta.enterprise.inject.spi.CDI;
import java.lang.management.ManagementFactory;
import org.jboss.weld.environment.se.Weld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/**
 * CASSINI-061: what one {@link CdiBeanProvider#getBean(Class)} costs on Weld, for the plain {@code @Dependent}
 * {@link PlainResource} and for {@link NumberResource}, a bounded generic class the provider finds by its bean class.
 * Both have a {@code @PreDestroy}, so Weld keeps every instance until it shuts down (CASSINI-062). Per class: 50 000
 * unmeasured lookups, the bytes the thread allocates over 10 000, then three rounds of 200 000 timed lookups.
 *
 * <p>Opt-in: {@code ./mvnw -ntp -pl cassini-it-other-containers/cassini-it-weld -am test -Dtest=WeldBeanLookupBenchIT
 * -Dsurefire.failIfNoSpecifiedTests=false -Dcassini.bench=true}. The figures go to {@code BENCH.md}.</p>
 */
@EnabledIfSystemProperty(named = "cassini.bench", matches = "true")
class WeldBeanLookupBenchIT {

    private static final int WARM_UP = 50_000;
    private static final int ALLOCATION_CALLS = 10_000;
    private static final int TIMED_CALLS = 200_000;

    /** Where each lookup goes, so that the JIT keeps it. */
    static volatile Object sink;

    @Test
    void oneLookupOfAPlainAndOfABoundedGenericDependentResource() {
        try (var _ = new Weld().initialize()) {
            var provider = new CdiBeanProvider(CDI.current());
            provider.getResourceClasses();
            for (Class<?> resource : new Class<?>[] {PlainResource.class, NumberResource.class}) {
                measure(provider, resource);
            }
            assertInstanceOf(NumberResource.class, provider.getBean(NumberResource.class));
        }
    }

    private static void measure(CdiBeanProvider provider, Class<?> resource) {
        Runnable lookup = () -> sink = provider.getBean(resource);
        for (int i = 0; i < WARM_UP; i++) lookup.run();
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        for (int i = 0; i < ALLOCATION_CALLS; i++) lookup.run();
        long bytes = (threads.getCurrentThreadAllocatedBytes() - before) / ALLOCATION_CALLS;
        StringBuilder nanos = new StringBuilder();
        for (int round = 0; round < 3; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < TIMED_CALLS; i++) lookup.run();
            nanos.append(round == 0 ? "" : " / ").append((System.nanoTime() - start) / TIMED_CALLS);
        }
        System.out.println("BENCH " + resource.getSimpleName() + " bytes/call=" + bytes + " ns/call=" + nanos);
    }
}
