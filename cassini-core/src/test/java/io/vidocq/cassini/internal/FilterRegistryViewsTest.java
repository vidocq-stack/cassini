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
package io.vidocq.cassini.internal;

import io.vidocq.cassini.internal.filter.FilterRegistry;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The pre-matching and post-matching request filters are read several times per request. Each
 * read filtered the registered filters through a stream into a new list; the registry now keeps
 * both views, rebuilt when a filter is registered.
 */
class FilterRegistryViewsTest {

    @PreMatching
    @Priority(20)
    static final class Early implements ContainerRequestFilter {
        @Override public void filter(ContainerRequestContext ctx) {
            // Registration only: the tests never run it.
        }
    }

    @Priority(10)
    static final class Late implements ContainerRequestFilter {
        @Override public void filter(ContainerRequestContext ctx) {
            // Registration only: the tests never run it.
        }
    }

    @PreMatching
    @Priority(5)
    static final class Earliest implements ContainerRequestFilter {
        @Override public void filter(ContainerRequestContext ctx) {
            // Registration only: the tests never run it.
        }
    }

    @Test
    void theViewsFollowTheRegistrationsInPriorityOrder() {
        FilterRegistry registry = new FilterRegistry();
        assertTrue(registry.preMatching().isEmpty());
        registry.register(new Early());
        registry.register(new Late());
        assertEquals(List.of(Early.class), registry.preMatching().stream().map(e -> e.instance().getClass()).toList());
        assertEquals(List.of(Late.class), registry.postMatching().stream().map(e -> e.instance().getClass()).toList());
        registry.register(new Earliest());
        assertEquals(List.of(Earliest.class, Early.class),
                registry.preMatching().stream().map(e -> e.instance().getClass()).toList());
    }

    @Test
    void readingTheViewsBuildsNothing() {
        FilterRegistry registry = new FilterRegistry();
        registry.register(new Early());
        registry.register(new Late());
        long perCall = Allocations.bytesPerCall(() -> {
            registry.preMatching();
            registry.postMatching();
        }, 1_000, 10_000);
        assertTrue(perCall < 16, "reading the pre- and post-matching filters allocates " + perCall + " bytes");
    }
}
