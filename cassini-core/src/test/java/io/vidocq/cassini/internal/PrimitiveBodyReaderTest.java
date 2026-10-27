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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** §4.2.3 / §4.2.4: the text/plain reader of primitives, wrappers and big numbers; a body it cannot parse is a 400. */
class PrimitiveBodyReaderTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object read(Class<?> type, String body) throws IOException {
        return new MessageBodyRegistry.PrimitiveReader().readFrom((Class) type, type, new Annotation[0],
                MediaType.TEXT_PLAIN_TYPE, null, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    static Stream<Arguments> bodies() {
        return Stream.of(
                Arguments.of(boolean.class, "TRUE", true), Arguments.of(Boolean.class, "false", false),
                Arguments.of(char.class, "xy", 'x'), Arguments.of(Character.class, "z", 'z'),
                Arguments.of(byte.class, "7", (byte) 7), Arguments.of(Short.class, "8", (short) 8),
                Arguments.of(int.class, "9", 9), Arguments.of(Long.class, "10", 10L),
                Arguments.of(float.class, "1.5", 1.5f), Arguments.of(Double.class, "2.5", 2.5d),
                Arguments.of(BigDecimal.class, "3.25", new BigDecimal("3.25")),
                Arguments.of(BigInteger.class, "12345678901234567890", new BigInteger("12345678901234567890")),
                Arguments.of(Number.class, "4.5", new BigDecimal("4.5")),
                Arguments.of(String.class, "text", "text"));
    }

    @ParameterizedTest
    @MethodSource("bodies")
    void a_valid_body_is_parsed_to_its_type(Class<?> type, String body, Object expected) throws IOException {
        assertEquals(expected, read(type, body));
    }

    @Test
    void a_body_that_is_not_a_boolean_is_a_bad_request() {
        assertThrows(BadRequestException.class, () -> read(boolean.class, "yes"));
    }

    @Test
    void an_empty_character_body_is_a_bad_request() {
        assertThrows(BadRequestException.class, () -> read(char.class, ""));
    }

    @Test
    void a_body_that_is_not_a_number_is_a_bad_request() {
        assertThrows(BadRequestException.class, () -> read(int.class, "nine"));
        assertThrows(BadRequestException.class, () -> read(BigInteger.class, "1.5"));
    }
}
