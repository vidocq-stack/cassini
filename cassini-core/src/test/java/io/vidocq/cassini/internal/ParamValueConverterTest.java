package io.vidocq.cassini.internal;

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

    enum Kind { SMALL, LARGE }

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
}
