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

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.bean.BeanProvider;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cassini injects the {@code @Context}, {@code @BeanParam} and {@code @*Param} fields of a
 * resource on every request (§3.2, §9.2), into the contextual instance behind a CDI client proxy:
 * finding it costs the bean provider a second resolution of the bean. A resource without such a
 * field needs neither, and the classes of most applications declare none.
 */
class ResourceFieldInjectionTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/plain")
    public static class Plain {
        private final String greeting = "hello";
        /** Never injected: Cassini injects instance fields only. */
        @Context static UriInfo staticField;

        @GET
        public String get() {
            return greeting;
        }
    }

    @Path("/contextual")
    public static class Contextual {
        @Context UriInfo uri;

        @GET
        public String get() {
            return uri.getPath();
        }
    }

    public static class WithQueryParam {
        @QueryParam("q") String q;
    }

    @Path("/inherited")
    public static class Inherited extends WithQueryParam {
        @GET
        public String get() {
            return String.valueOf(q);
        }
    }

    /** A bean provider standing for a CDI container: counts the de-proxying lookups. */
    static final class CountingProvider implements BeanProvider {
        final AtomicInteger contextualLookups = new AtomicInteger();

        @Override
        public <T> T getBean(Class<T> type) {
            try {
                return type.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalArgumentException(e);
            }
        }

        @Override
        public Set<Class<?>> getResourceClasses() {
            return Set.of(Plain.class, Contextual.class, Inherited.class);
        }

        @Override
        public Object contextualInstance(Class<?> type, Object bean) {
            contextualLookups.incrementAndGet();
            return bean;
        }
    }

    private static InMemoryExchange get(CountingProvider provider, String path) {
        InMemoryExchange exchange = InMemoryExchange.get(path);
        new CassiniStackBuilderImpl().beanProvider(provider).build().adapter().dispatch(exchange)
                .toCompletableFuture().join();
        return exchange;
    }

    /** No injectable field: no lookup. A {@code @Context} field, or a {@code @QueryParam} field
     *  inherited from a superclass: still looked up and injected. */
    @ParameterizedTest
    @CsvSource({"/plain, hello, 0", "/contextual, contextual, 1", "/inherited?q=x, x, 1"})
    void aResourceIsLookedUpAgainOnlyWhenItHasFieldsToInject(String path, String body, int lookups) {
        CountingProvider provider = new CountingProvider();
        InMemoryExchange exchange = get(provider, path);
        assertEquals(200, exchange.status());
        assertEquals(body, exchange.responseText());
        assertEquals(lookups, provider.contextualLookups.get());
    }

    @Test
    void theInjectableFieldsOfAClassAreKnownOnce() {
        assertFalse(InjectableFields.declaredBy(Plain.class), "a static field is never injected");
        assertTrue(InjectableFields.declaredBy(Contextual.class));
        assertTrue(InjectableFields.declaredBy(Inherited.class));
        assertFalse(InjectableFields.declaredBy(Object.class));
    }
}
