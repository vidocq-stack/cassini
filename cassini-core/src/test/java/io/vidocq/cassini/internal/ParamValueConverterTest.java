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
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParamValueConverterTest {

    @BeforeAll
    static void runtimeDelegate() {
        // A WebApplicationException builds its Response: run alone, the class needs a delegate of its own.
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    enum Kind { SMALL, LARGE }

    /** An enum whose fromString wins over valueOf (§3.2). */
    public enum Size {
        S, M;

        public static Size fromString(String raw) {
            return "small".equals(raw) ? S : M;
        }
    }

    /** A type built through its String constructor. */
    public static final class Label {
        final String text;

        public Label(String text) {
            this.text = text;
        }
    }

    @Test
    void floatingPointCharactersEnumsAndFactories() {
        assertEquals(1.5f, ParamValueConverter.coerceSingle(Float.class, "1.5"));
        assertEquals(2.5d, ParamValueConverter.coerceSingle(double.class, "2.5"));
        assertEquals('c', ParamValueConverter.coerceSingle(char.class, "cat"));
        assertEquals((byte) 3, ParamValueConverter.coerceSingle(Byte.class, "3"));
        assertEquals((short) 4, ParamValueConverter.coerceSingle(short.class, "4"));
        assertEquals(Kind.LARGE, ParamValueConverter.coerceSingle(Kind.class, "LARGE"));
        assertNull(ParamValueConverter.coerceSingle(Kind.class, "HUGE"));
        assertEquals(Size.S, ParamValueConverter.coerceSingle(Size.class, "small"));
        assertEquals("tag", ((Label) ParamValueConverter.coerceSingle(Label.class, "tag")).text);
        assertEquals(UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                ParamValueConverter.coerceSingle(UUID.class, "123e4567-e89b-12d3-a456-426614174000"));
    }

    @Test
    void anEmptyCharOrATypeWithoutConverterIsABadRequest() {
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> ParamValueConverter.coerceSingle(char.class, ""));
        org.junit.jupiter.api.Assertions.assertThrows(jakarta.ws.rs.WebApplicationException.class,
                () -> ParamValueConverter.coerceSingle(Object.class, "x"));
    }

    @Test
    void primitivesAndWrappers() {
        assertEquals(42, ParamValueConverter.coerceSingle(int.class, "42"));
        assertEquals(42L, ParamValueConverter.coerceSingle(long.class, "42"));
        assertEquals(true, ParamValueConverter.coerceSingle(boolean.class, "true"));
        assertEquals(3.5, ParamValueConverter.coerceSingle(double.class, "3.5"));
        assertEquals('a', ParamValueConverter.coerceSingle(char.class, "abc"));
    }

    @Test
    void enumByName() {
        assertEquals(Kind.LARGE, ParamValueConverter.coerceSingle(Kind.class, "LARGE"));
    }

    @Test
    void staticValueOfIsUsed() {
        Object v = ParamValueConverter.coerceSingle(Integer.class, "7");
        assertEquals(7, v);
    }

    @Test
    void staticFromStringIsUsed() {
        UUID id = UUID.randomUUID();
        Object v = ParamValueConverter.coerceSingle(UUID.class, id.toString());
        assertEquals(id, v);
    }

    @Test
    void singleArgStringConstructor() {
        Object v = ParamValueConverter.coerceSingle(StringBuilder.class, "hello");
        assertInstanceOf(StringBuilder.class, v);
        assertEquals("hello", v.toString());
    }

    @Test
    void listOfIntegers() {
        Object v = ParamValueConverter.coerce(List.class, Integer.class, List.of("1", "2", "3"));
        assertEquals(List.of(1, 2, 3), v);
    }

    @Test
    void setOfStrings() {
        Object v = ParamValueConverter.coerce(Set.class, String.class, List.of("a", "b", "a"));
        assertInstanceOf(Set.class, v);
        assertTrue(((Set<?>) v).contains("a"));
    }

    @Test
    void sortedSetOfIntegers() {
        Object v = ParamValueConverter.coerce(SortedSet.class, Integer.class, List.of("3", "1", "2"));
        assertInstanceOf(SortedSet.class, v);
        assertEquals(1, ((SortedSet<?>) v).first());
    }

    @Test
    void defaultForPrimitiveIsZeroAndBoxedIsNull() {
        assertEquals(0, ParamValueConverter.defaultForType(int.class));
        assertFalse((boolean) ParamValueConverter.defaultForType(boolean.class));
        assertNull(ParamValueConverter.defaultForType(Integer.class));
    }

    @Test
    void aFactoryIsLookedUpOncePerType() {
        // Each value used to look the factory up again: a NoSuchMethodException, stack trace included, for every
        // candidate a type does not have (valueOf for UUID, valueOf and fromString for Label).
        long uuid = Allocations.bytesPerCall(
                () -> ParamValueConverter.coerceSingle(UUID.class, "6f1c9a2e-7b1d-4c3a-9e2f-1a2b3c4d5e6f"), 2_000, 10_000);
        long label = Allocations.bytesPerCall(() -> ParamValueConverter.coerceSingle(Label.class, "x"), 2_000, 10_000);
        long size = Allocations.bytesPerCall(() -> ParamValueConverter.coerceSingle(Size.class, "small"), 2_000, 10_000);

        assertTrue(uuid < 256, () -> "UUID: " + uuid + " bytes per value");
        assertTrue(label < 256, () -> "Label: " + label + " bytes per value");
        assertTrue(size < 256, () -> "enum with fromString: " + size + " bytes per value");
    }
}
