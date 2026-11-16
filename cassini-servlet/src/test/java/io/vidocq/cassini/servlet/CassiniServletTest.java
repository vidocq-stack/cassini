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

import io.vidocq.cassini.spi.bean.BeanProvider;
import jakarta.servlet.ServletException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.SecurityContext;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A REST application served by the servlet, in front of fake Servlet objects: the whole Cassini dispatch runs. */
class CassiniServletTest {

    @Path("hello")
    public static class HelloResource {
        @GET
        public String hello() {
            return "hello";
        }

        @GET
        @Path("who")
        public String who(@Context SecurityContext security) {
            return security.getUserPrincipal() == null ? "nobody" : security.getUserPrincipal().getName();
        }

        @GET
        @Path("admin")
        public String admin(@Context SecurityContext security) {
            return Boolean.toString(security.isUserInRole("admin"));
        }
    }

    public static class App extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(HelloResource.class);
        }
    }

    public static class Broken extends Application {
        public Broken() {
            throw new IllegalStateException("no");
        }
    }

    private final Fakes.Req req = new Fakes.Req();
    private final Fakes.Res res = new Fakes.Res();

    private CassiniServlet started(Class<? extends Application> app) throws ServletException {
        CassiniServlet servlet = new CassiniServlet(app);
        servlet.init(Fakes.config(new Fakes.Ctx().proxy(), "app"));
        return servlet;
    }

    private void get(CassiniServlet servlet, String path) throws Exception {
        req.contextPath = "/ctx";
        req.servletPath = "/api";
        req.requestUri = "/ctx/api" + path;
        servlet.service(req.proxy(), res.proxy());
    }

    @Test
    void aRequestIsServedByTheResource() throws Exception {
        get(started(App.class), "/hello");
        assertEquals(200, res.status);
        assertEquals("hello", res.body.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anUnknownPathIs404() throws Exception {
        get(started(App.class), "/nothing");
        assertEquals(404, res.status);
    }

    @Test
    void theCallerTheContainerAuthenticatedIsTheCallerOfTheResource() throws Exception {
        req.principal = () -> "alice";
        req.roles = Set.of("admin");
        CassiniServlet servlet = started(App.class);
        get(servlet, "/hello/who");
        assertEquals("alice", res.body.toString(StandardCharsets.UTF_8));
        res.body.reset();
        get(servlet, "/hello/admin");
        assertEquals("true", res.body.toString(StandardCharsets.UTF_8));
    }

    @Test
    void anApplicationThatDoesNotStartStopsTheServletInit() {
        ServletException e = assertThrows(ServletException.class, () -> started(Broken.class));
        assertEquals("The REST application of servlet app does not start", e.getMessage());
    }

    @Test
    void theBeanProviderOfTheContextServesTheResources() throws Exception {
        Fakes.Ctx ctx = new Fakes.Ctx();
        ctx.attributes.put(CassiniServlet.BEAN_PROVIDER_ATTRIBUTE, new BeanProvider() {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T getBean(Class<T> type) {
                return (T) new HelloResource() {
                    @Override
                    public String hello() {
                        return "from the beans";
                    }
                };
            }

            @Override
            public Set<Class<?>> getResourceClasses() {
                return Set.of(HelloResource.class);
            }

            @Override
            public Object contextualInstance(Class<?> type, Object bean) {
                return bean;
            }
        });
        CassiniServlet servlet = new CassiniServlet(null);
        servlet.init(Fakes.config(ctx.proxy(), "app"));
        get(servlet, "/hello");
        assertEquals("from the beans", res.body.toString(StandardCharsets.UTF_8));
    }
}
