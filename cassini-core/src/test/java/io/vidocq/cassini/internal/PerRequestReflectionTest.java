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
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the request path needs from a resource method is read once, not at every request (BUG-20261102-01): the
 * {@code @Encoded} of the method and of its class, its annotations and its generic return type; and a parameter
 * converted through its {@code valueOf(String)} is not converted by {@code Method.invoke}.
 */
class PerRequestReflectionTest {

    @Encoded
    public static class EncodedResource {
        @GET
        public String get() {
            return "";
        }
    }

    public static class PlainResource {
        @GET
        @Encoded
        @Produces("text/plain")
        public List<String> list() {
            return List.of();
        }

        @GET
        public String plain() {
            return "";
        }
    }

    /** A type converted through its static valueOf(String), which records how it was called. */
    public static final class Token {
        static volatile boolean calledThroughMethodInvoke;

        final String value;

        private Token(String value) {
            this.value = value;
        }

        public static Token valueOf(String raw) {
            // only the frames between this call and the test method: JUnit calls the test by reflection
            calledThroughMethodInvoke = Arrays.stream(Thread.currentThread().getStackTrace())
                    .takeWhile(f -> !f.getClassName().equals(PerRequestReflectionTest.class.getName()))
                    .anyMatch(f -> f.getClassName().equals("java.lang.reflect.Method") && f.getMethodName().equals("invoke"));
            return new Token(raw);
        }
    }

    @BeforeAll
    static void runtimeDelegate() {
        // cassini-core registers no RuntimeDelegate, the transports do; a 400 response needs one.
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Test
    void theEncodedAnnotationsAreReadOnce() throws NoSuchMethodException {
        Method list = PlainResource.class.getMethod("list");
        Method plain = PlainResource.class.getMethod("plain");

        assertTrue(MethodParameters.of(list).encoded());
        assertFalse(MethodParameters.of(plain).encoded());
        assertTrue(ParamExtractor.classEncoded(EncodedResource.class));
        assertFalse(ParamExtractor.classEncoded(PlainResource.class));
    }

    @Test
    void theAnnotationsAndGenericReturnTypeOfAMethodAreReadOnce() throws NoSuchMethodException {
        Method list = PlainResource.class.getMethod("list");
        MethodParameters parameters = MethodParameters.of(list);

        assertSame(parameters.annotations(), MethodParameters.of(list).annotations());
        assertEquals(3, parameters.annotations().length);
        assertEquals(list.getGenericReturnType(), parameters.genericReturnType());
    }

    @Test
    void aValueOfConverterIsNotCalledThroughMethodInvoke() {
        Token.calledThroughMethodInvoke = true;

        Object converted = ParamValueConverter.coerce(Token.class, Token.class, List.of("abc"));

        assertEquals("abc", ((Token) converted).value);
        assertFalse(Token.calledThroughMethodInvoke, "valueOf(String) was called through Method.invoke");
    }

    /** Converted through its public constructor(String). */
    public static final class Wrapped {
        final String value;

        public Wrapped(String value) {
            this.value = value;
        }
    }

    /** A valueOf(String) that fails, with a checked exception or with a runtime one. */
    public static final class Failing {
        public static Failing valueOf(String raw) throws Exception {
            if (raw.equals("runtime")) throw new IllegalStateException("runtime failure");
            throw new Exception("checked failure");
        }
    }

    /** A private valueOf(String): no handle resolves, the reflective fallback runs as before. */
    public static final class Hidden {
        private static Hidden valueOf(String raw) {
            return new Hidden();
        }
    }

    @Test
    void aStringConstructorConvertsThroughItsHandle() {
        assertEquals("abc", ((Wrapped) ParamValueConverter.coerceSingle(Wrapped.class, "abc")).value);
    }

    @Test
    void aFailingValueOfStillAnswersAsBefore() {
        IllegalStateException runtime = assertThrows(IllegalStateException.class,
                () -> ParamValueConverter.coerceSingle(Failing.class, "runtime"));
        assertEquals("runtime failure", runtime.getMessage());

        WebApplicationException checked = assertThrows(WebApplicationException.class,
                () -> ParamValueConverter.coerceSingle(Failing.class, "checked"));
        assertEquals(400, checked.getResponse().getStatus());
        assertEquals("valueOf failed: checked failure", checked.getMessage());
    }

    @Test
    void aValueOfWithoutHandleKeepsTheReflectiveFallback() {
        WebApplicationException e = assertThrows(WebApplicationException.class,
                () -> ParamValueConverter.coerceSingle(Hidden.class, "abc"));
        assertEquals(400, e.getResponse().getStatus());
        assertTrue(e.getMessage().startsWith("valueOf inaccessible"), e.getMessage());
    }
}
