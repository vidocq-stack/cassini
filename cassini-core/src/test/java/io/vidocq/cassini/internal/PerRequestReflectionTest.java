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

import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Produces;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
}
