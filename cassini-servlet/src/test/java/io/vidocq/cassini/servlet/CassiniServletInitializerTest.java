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
package io.vidocq.cassini.servlet;

import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CassiniServletInitializerTest {

    @ApplicationPath("/api")
    public static class Api extends Application { }

    @ApplicationPath("v1/")
    public static class Versioned extends Application { }

    public static class NoPath extends Application { }

    @ApplicationPath("/abstract")
    public abstract static class Abstract extends Application { }

    @Test
    void theMappingOfAnApplicationPath() {
        assertEquals("/api/*", CassiniServletInitializer.mapping("/api"));
        assertEquals("/api/*", CassiniServletInitializer.mapping("api"));
        assertEquals("/api/*", CassiniServletInitializer.mapping("/api/"));
        assertEquals("/api/*", CassiniServletInitializer.mapping("/api/*"));
        assertEquals("/*", CassiniServletInitializer.mapping("/"));
        assertEquals("/*", CassiniServletInitializer.mapping(""));
        assertEquals("/*", CassiniServletInitializer.mapping(null));
    }

    @Test
    void anApplicationWithAnApplicationPathGetsAServlet() {
        Fakes.Ctx ctx = new Fakes.Ctx();
        new CassiniServletInitializer().onStartup(Set.of(Api.class, Versioned.class), ctx.proxy());
        assertEquals(Set.of(Api.class.getName(), Versioned.class.getName()), ctx.servlets.keySet());
        assertEquals(List.of("/api/*"), ctx.mappings.get(Api.class.getName()));
        assertEquals(List.of("/v1/*"), ctx.mappings.get(Versioned.class.getName()));
        assertTrue(ctx.asyncSupported.contains(Api.class.getName()));
    }

    @Test
    void anApplicationWithoutAPathOrAbstractGetsNothing() {
        Fakes.Ctx ctx = new Fakes.Ctx();
        new CassiniServletInitializer().onStartup(Set.of(NoPath.class, Abstract.class, Application.class), ctx.proxy());
        assertTrue(ctx.servlets.isEmpty());
    }

    @Test
    void aServletTheApplicationAlreadyDeclaredIsLeftAlone() {
        Fakes.Ctx ctx = new Fakes.Ctx();
        ctx.servlets.put(Api.class.getName(), "declared in web.xml");
        new CassiniServletInitializer().onStartup(Set.of(Api.class), ctx.proxy());
        assertEquals("declared in web.xml", ctx.servlets.get(Api.class.getName()));
        assertTrue(ctx.mappings.isEmpty());
    }

    @Test
    void noClassesIsNoWork() {
        Fakes.Ctx ctx = new Fakes.Ctx();
        new CassiniServletInitializer().onStartup(null, ctx.proxy());
        assertTrue(ctx.servlets.isEmpty());
    }
}
