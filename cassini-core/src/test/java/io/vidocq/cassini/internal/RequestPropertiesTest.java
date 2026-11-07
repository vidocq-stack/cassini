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
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The properties of a request are one store for the whole request: a property set by a pre-matching or a
 * post-matching request filter is read by the response filters, whichever context object each one receives
 * (CASSINI-007, cassini#116).
 */
class RequestPropertiesTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/props")
    public static class Resource {
        @GET
        public String get() {
            return "ok";
        }
    }

    @Provider
    @PreMatching
    public static class Early implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            request.setProperty("early", "e");
            request.setProperty("gone", "g");
        }
    }

    @Provider
    public static class Late implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            request.setProperty("late", "l");
            request.removeProperty("gone");
        }
    }

    @Provider
    public static class Reader implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle("X-Props", request.getProperty("early") + "|" + request.getProperty("late")
                    + "|" + request.getProperty("gone") + "|" + request.getPropertyNames().stream().sorted().toList());
        }
    }

    static final AtomicReference<String> SEEN_BY_READER = new AtomicReference<>();

    /** A reader interceptor of the request sees what the request filters set, and adds a property of its own. */
    @Provider
    public static class ReadsProperties implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            SEEN_BY_READER.set(context.getProperty("early") + "|" + context.getProperty("nullName"));
            context.setProperty("fromReader", "r");
            return context.proceed();
        }
    }

    /** A writer interceptor sees what the filters and the reader interceptor set, and adds a property of its own. */
    @Provider
    public static class WritesProperties implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
            // A null name asks for nothing and removes nothing: no NullPointerException.
            Object none = context.getProperty(null);
            context.removeProperty(null);
            context.getHeaders().putSingle("X-Writer", context.getProperty("early") + "|" + context.getProperty("fromReader")
                    + "|" + context.getProperty("fromResponseFilter") + "|" + none);
            context.setProperty("fromWriter", "w");
            context.proceed();
        }
    }

    @Path("/echo")
    public static class Echo {
        @POST
        public String post(String body) {
            return body;
        }
    }

    /** Sets a property in a response filter: the writer interceptors run after the response filters. */
    @Provider
    public static class SetsInResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            request.setProperty("fromResponseFilter", "f");
        }
    }

    private static InMemoryExchange get() {
        CassiniStack stack = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(Resource.class, Early.class, Late.class, Reader.class);
            }
        }).build();
        InMemoryExchange exchange = InMemoryExchange.request("GET", "/props", null, null);
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    private static InMemoryExchange post(Class<?>... providers) {
        Set<Class<?>> classes = new java.util.HashSet<>(Set.of(providers));
        classes.add(Echo.class);
        CassiniStack stack = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return classes;
            }
        }).build();
        InMemoryExchange exchange = InMemoryExchange.request("POST", "/echo", "text/plain", "hello");
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    @Test
    void theInterceptorsShareThePropertiesOfTheRequestWithTheFilters() {
        SEEN_BY_READER.set(null);
        InMemoryExchange exchange = post(Early.class, ReadsProperties.class, WritesProperties.class);
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals("e|null", SEEN_BY_READER.get(), "the reader interceptor sees the request filters' property");
        assertEquals("e|r|null|null", exchange.responseHeader("X-Writer"),
                "the writer interceptor sees the filters' and the reader interceptor's properties");
    }

    @Test
    void theWriterInterceptorSeesThePropertiesTheResponseFiltersSet() {
        InMemoryExchange exchange = post(Early.class, SetsInResponseFilter.class, WritesProperties.class);
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals("e|null|f|null", exchange.responseHeader("X-Writer"));
    }

    @Test
    void aNullNameAsksForNothingAndRemovesNothing() {
        InMemoryExchange exchange = post(Early.class, WritesProperties.class);
        assertEquals(200, exchange.status(), exchange::responseText);
    }

    @Test
    void theResponseFiltersSeeThePropertiesOfTheRequestFilters() {
        InMemoryExchange exchange = get();
        assertEquals(200, exchange.status(), exchange::responseText);
        assertEquals("e|l|null|[early, late]", exchange.responseHeader("X-Props"));
    }
}
