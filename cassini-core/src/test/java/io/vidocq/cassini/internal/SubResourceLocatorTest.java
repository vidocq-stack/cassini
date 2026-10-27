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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Sub-resource locators (§3.4.1): a locator that returns a typed sub-resource is routed with the
 * static routes, one that returns {@code Object} is resolved at run time on the class of what it
 * returned. Both walk the chain, inject the fields of each object, and answer 404 for a locator
 * that returns null.
 */
class SubResourceLocatorTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    public static class Item {
        @Context UriInfo uri;

        @GET
        @Produces("text/plain")
        public String get() {
            return "item " + uri.getPath();
        }

        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        public String post(String body) {
            return "posted " + body;
        }

        @GET
        @Path("/stage")
        @Produces("text/plain")
        public CompletionStage<String> stage() {
            return CompletableFuture.completedFuture("staged");
        }

        @GET
        @Path("/stage-wae")
        public CompletionStage<String> stageWae() {
            return CompletableFuture.failedFuture(new WebApplicationException(410));
        }

        @GET
        @Path("/throws")
        public String throwsWae() {
            throw new WebApplicationException(409);
        }

        @GET
        @Path("/json")
        @Produces("application/json")
        public String json() {
            return "\"j\"";
        }

        /** A locator of the sub-resource itself, resolved at run time again. */
        @Path("/deeper")
        public Object deeper() {
            return new Item();
        }

        @Path("/deeper-null")
        public Object deeperNull() {
            return null;
        }
    }

    @Path("/loc")
    public static class Root {
        @Path("/typed/{id}")
        public Item typed(@PathParam("id") String id) {
            return new Item();
        }

        @Path("/null")
        public Item none() {
            return null;
        }

        @Path("/class")
        public Class<Item> type() {
            return Item.class;
        }

        @Path("/boom")
        public Item boom() {
            throw new WebApplicationException(409);
        }

        @Path("/dyn")
        public Object dynamic() {
            return new Item();
        }

        @Path("/dyn-null")
        public Object dynamicNull() {
            return null;
        }

        @Path("/dyn-boom")
        public Object dynamicBoom() {
            throw new WebApplicationException(423);
        }

        @Path("/dyn-class")
        public Object dynamicClass() {
            return Item.class;
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Root.class);
        }
    }).build();

    private static InMemoryExchange send(String method, String path, String contentType, String body) {
        InMemoryExchange exchange = InMemoryExchange.request(method, path, contentType, body);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    private static InMemoryExchange get(String path) {
        return send("GET", path, null, null);
    }

    @Test
    void aTypedLocatorLeadsToItsSubResource() {
        InMemoryExchange item = get("/loc/typed/7");
        assertEquals(200, item.status());
        assertEquals("item loc/typed/7", item.responseText());
        assertEquals("posted x", send("POST", "/loc/typed/7", "text/plain", "x").responseText());
        assertEquals("item loc/class", get("/loc/class").responseText());
        assertEquals(404, get("/loc/null").status());
        assertEquals(409, get("/loc/boom").status());
    }

    @Test
    void anObjectLocatorIsResolvedOnWhatItReturns() {
        assertEquals("item loc/dyn", get("/loc/dyn").responseText());
        assertEquals("posted y", send("POST", "/loc/dyn", "text/plain", "y").responseText());
        assertEquals("staged", get("/loc/dyn/stage").responseText());
        assertEquals(410, get("/loc/dyn/stage-wae").status());
        assertEquals(409, get("/loc/dyn/throws").status());
        assertEquals("item loc/dyn/deeper", get("/loc/dyn/deeper").responseText());
        assertEquals("item loc/dyn-class", get("/loc/dyn-class").responseText());
    }

    @Test
    void anObjectLocatorAnswersNotFoundNotAllowedAndNegotiationErrors() {
        assertEquals(404, get("/loc/dyn-null").status());
        assertEquals(423, get("/loc/dyn-boom").status());
        assertEquals(404, get("/loc/dyn/missing").status());
        assertEquals(404, get("/loc/dyn/deeper-null").status());
        assertEquals(405, send("DELETE", "/loc/dyn", null, null).status());
        assertEquals(415, send("POST", "/loc/dyn", "application/xml", "<x/>").status());
        InMemoryExchange refused = InMemoryExchange.request("GET", "/loc/dyn/json", null, null)
                .header("Accept", "text/html");
        STACK.adapter().dispatch(refused).toCompletableFuture().join();
        assertEquals(406, refused.status());
    }
}
