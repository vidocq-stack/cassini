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

import io.vidocq.cassini.spi.gen.RouteDescriptor;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A generated route names its method's parameter types and the class whose declaration carries its annotations,
 * so that nothing is resolved by name nor scanned at startup (CASSINI-042); a route without them keeps the
 * reflective resolution.
 */
class RouteMethodResolutionTest {

    public interface Api {
        @GET
        @Path("item")
        String get(@QueryParam("q") String q, int page);
    }

    @Path("/resolve")
    public static class Impl implements Api {
        @Override
        public String get(String q, int page) {
            return q;
        }
    }

    private static RouteDescriptor route(Class<?>[] paramTypes, Class<?> annotatedClass) {
        return new RouteDescriptor(Impl.class, "get", new String[] {"java.lang.String", "int"}, "GET",
                "/resolve/item", new String[0], new String[0], 8, paramTypes, annotatedClass);
    }

    @Test
    void theClassLiteralsOfARouteNameItsAnnotatedMethod() throws ReflectiveOperationException {
        Method m = RouteRegistry.resolveMethod(route(new Class<?>[] {String.class, int.class}, Api.class));

        assertSame(Api.class, m.getDeclaringClass());
        assertEquals(RouteRegistry.resolveMethod(route(null, null)), m, "the same method the scan finds");
    }

    @Test
    void theAnnotatedClassOfARouteIsTakenAsGivenWithoutScanning() throws ReflectiveOperationException {
        Method m = RouteRegistry.resolveMethod(route(new Class<?>[] {String.class, int.class}, Impl.class));

        assertSame(Impl.class, m.getDeclaringClass(), "a scan would have moved to Api, which carries the annotations");
    }

    @Test
    void aRouteWithoutClassLiteralsIsResolvedByNameAndScanned() throws ReflectiveOperationException {
        Method m = RouteRegistry.resolveMethod(new RouteDescriptor(Impl.class, "get",
                new String[] {"java.lang.String", "int"}, "GET", "/resolve/item", new String[0], new String[0], 8));

        assertSame(Api.class, m.getDeclaringClass());
    }
}
