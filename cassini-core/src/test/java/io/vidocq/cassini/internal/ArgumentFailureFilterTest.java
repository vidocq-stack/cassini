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
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Jakarta REST 4.0 §6.7.1: a resource method is matched before its arguments are resolved, so the response to a
 * failure resolving them (a parameter that does not convert, an entity that does not read) goes through every response
 * filter of that method, the global and the bound ones. None ran (CASSINI-057).
 */
class ArgumentFailureFilterTest {

    private static final String TEXT = MediaType.TEXT_PLAIN;
    private static final String GLOBAL = "X-Global";
    private static final String BOUND = "X-Bound";
    private static final String YES = "yes";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---------------------------------------------------------------- fixtures

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Bound { }

    @Provider
    public static final class GlobalResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().add(GLOBAL, YES);
        }
    }

    @Provider
    @Bound
    public static final class BoundResponseFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().add(BOUND, YES);
        }
    }

    /** An entity no reader can read. */
    public record Unreadable(String value) { }

    @Provider
    @Consumes(TEXT)
    public static final class FailingReader implements MessageBodyReader<Unreadable> {
        @Override
        public boolean isReadable(Class<?> type, Type generic, Annotation[] annotations, MediaType mediaType) {
            return type == Unreadable.class;
        }

        @Override
        public Unreadable readFrom(Class<Unreadable> type, Type generic, Annotation[] annotations, MediaType mediaType,
                                   MultivaluedMap<String, String> headers, InputStream entity) {
            throw new IllegalArgumentException("not readable");
        }
    }

    @Path("/args")
    @Bound
    public static class ArgsResource {
        @GET
        @Path("query")
        @Produces(TEXT)
        public String query(@QueryParam("n") int n) {
            return "n=" + n;
        }

        @GET
        @Path("header")
        @Produces(TEXT)
        public String header(@HeaderParam("X-N") int n) {
            return "n=" + n;
        }

        @POST
        @Path("entity")
        @Consumes(TEXT)
        @Produces(TEXT)
        public String entity(Unreadable entity) {
            return entity.value();
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(ArgsResource.class, GlobalResponseFilter.class, BoundResponseFilter.class,
                    FailingReader.class);
        }
    }).build();

    private static List<String> headerValues(InMemoryExchange ex, String name) {
        return ex.responseHeaders().entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase(name))
                .flatMap(e -> e.getValue().stream())
                .toList();
    }

    // ------------------------------------------------------------------- tests

    @ParameterizedTest(name = "{0} {1} -> {2}")
    @CsvSource({
            "GET,  /args/query?n=abc, 404",
            "GET,  /args/header,      400",
            "POST, /args/entity,      400"})
    void theResponseFiltersOfTheMethodRunWhenItsArgumentsFail(String method, String path, int status) {
        InMemoryExchange exchange = "POST".equals(method)
                ? InMemoryExchange.post(path, TEXT, "x")
                : InMemoryExchange.get(path).header("X-N", "abc");
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals(status, exchange.status(), exchange::responseText);
        assertEquals(List.of(YES), headerValues(exchange, GLOBAL), "the global response filter, once");
        assertEquals(List.of(YES), headerValues(exchange, BOUND), "the method is matched: its bound filter too");
    }
}
