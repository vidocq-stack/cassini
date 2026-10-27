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
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §6.1.4: an application ParamConverterProvider converts a parameter before Cassini's own
 * conversion (constructors, valueOf), for the parameter type or, for a collection, for its
 * element type; a provider that fails on a type is skipped.
 */
class ParamConvertersTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    /** Converted by {@link Converters}; "bad" does not convert. */
    public record Color(String name) {
    }

    /** The provider throws when asked for it; Cassini's own valueOf conversion takes over. */
    public record Fragile(String value) {
        public static Fragile valueOf(String s) {
            return new Fragile("own " + s);
        }
    }

    @Provider
    public static class Converters implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            if (rawType == Fragile.class) throw new IllegalStateException("not this one");
            if (rawType != Color.class) return null;
            return (ParamConverter<T>) new ParamConverter<Color>() {
                @Override
                public Color fromString(String value) {
                    if ("bad".equals(value)) throw new IllegalArgumentException("not a color");
                    return new Color(value);
                }

                @Override
                public String toString(Color value) {
                    return value.name();
                }
            };
        }
    }

    @Path("/colors")
    public static class Colors {
        @GET
        public String get(@QueryParam("c") Color c, @QueryParam("l") List<Color> l, @QueryParam("s") Set<Color> s,
                          @QueryParam("f") Fragile f, @HeaderParam("X-C") Color header) {
            return c + "|" + l + "|" + s + "|" + f + "|" + header;
        }
    }

    private static final CassiniStack STACK = new CassiniStackBuilderImpl().application(new Application() {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(Colors.class, Converters.class);
        }
    }).build();

    private static InMemoryExchange get(String path, String colorHeader) {
        InMemoryExchange exchange = InMemoryExchange.request("GET", path, null, null);
        if (colorHeader != null) exchange.header("X-C", colorHeader);
        STACK.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    @Test
    void theApplicationConvertsAParameterAndTheElementsOfACollection() {
        InMemoryExchange exchange = get("/colors?c=red&l=a&l=b&s=x&s=x&f=v", "blue");
        assertEquals(200, exchange.status());
        assertEquals("Color[name=red]|[Color[name=a], Color[name=b]]|[Color[name=x]]|Fragile[value=own v]"
                + "|Color[name=blue]", exchange.responseText());
    }

    @Test
    void aValueTheApplicationCannotConvertIsABadRequest() {
        assertEquals(400, get("/colors", "bad").status());
        assertEquals(400, get("/colors?l=bad", null).status());
    }

    @Test
    void resolvedArgumentsCompareByContent() {
        var a = new ParamExtractor.ResolvedArgs(new Object[] {"x", 1}, 0);
        var b = new ParamExtractor.ResolvedArgs(new Object[] {"x", 1}, 0);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, new ParamExtractor.ResolvedArgs(new Object[] {"x", 1}, 1));
        assertNotEquals(a, new ParamExtractor.ResolvedArgs(new Object[] {"y", 1}, 0));
        assertNotEquals(a, (Object) "x");
        assertTrue(a.toString().contains("[x, 1]"));
    }
}
