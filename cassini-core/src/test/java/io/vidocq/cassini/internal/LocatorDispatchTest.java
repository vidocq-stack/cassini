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
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * A sub-resource locator runs through the adapter of the class that declares it, not through
 * {@code Method.invoke} on every request, and a {@code @BeanParam} parameter through the adapter the build
 * generated for its class (wave 4 of the reflection audit).
 */
class LocatorDispatchTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    static volatile boolean locatorReachedThroughMethodInvoke;

    private static void noteCaller() {
        // only the frames up to the request dispatch: JUnit calls the test by reflection
        locatorReachedThroughMethodInvoke |= Arrays.stream(Thread.currentThread().getStackTrace())
                .skip(3) // getStackTrace, noteCaller, the locator
                .takeWhile(f -> !f.getClassName().equals(LocatorDispatchTest.class.getName()))
                .anyMatch(f -> f.getClassName().equals("java.lang.reflect.Method") && f.getMethodName().equals("invoke"));
    }

    public static class Leaf {
        @GET
        @Produces("text/plain")
        public String get() {
            return "leaf";
        }
    }

    /** A {@code @BeanParam} class without a build-time adapter. */
    public static class PlainBean {
        @QueryParam("q")
        public String q;
    }

    @Path("/locate")
    public static class Root {
        @Path("/typed/{id}")
        public Leaf typed(@PathParam("id") String id) {
            noteCaller();
            return new Leaf();
        }

        @Path("/dynamic")
        public Object dynamic() {
            noteCaller();
            return new Leaf();
        }

        @GET
        @Path("/bean")
        public String bean(@BeanParam BeanArg arg) {
            return arg.q;
        }

        @GET
        @Path("/plain-bean")
        public String plainBean(@BeanParam PlainBean arg) {
            return arg.q;
        }

        @Path("/boom")
        public Leaf boom() {
            throw new WebApplicationException(409);
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Root.class);
        }
    }).build();

    private static InMemoryExchange get(String path) {
        InMemoryExchange exchange = InMemoryExchange.request("GET", path, null, null);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    @Test
    void aLocatorIsNotCalledThroughMethodInvoke() {
        locatorReachedThroughMethodInvoke = false;

        assertEquals("leaf", get("/locate/typed/1").responseText());
        assertEquals("leaf", get("/locate/dynamic").responseText());

        assertFalse(locatorReachedThroughMethodInvoke, "a sub-resource locator was called through Method.invoke");
    }

    @Test
    void aLocatorThatThrowsStillAnswersItsStatus() {
        assertEquals(409, get("/locate/boom").status());
    }

    @Test
    void aBeanParameterGoesThroughItsBuildTimeAdapter() {
        assertEquals("adapter", get("/locate/bean?q=query").responseText());
    }

    @Test
    void aBeanParameterWithoutBuildTimeAdapterIsStillFilledByReflection() {
        assertEquals("query", get("/locate/plain-bean?q=query").responseText());
    }
}
