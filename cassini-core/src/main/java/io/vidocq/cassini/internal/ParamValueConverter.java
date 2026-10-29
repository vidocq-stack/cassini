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

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.AbstractMultivaluedMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.core.Response;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * Coercion of a string (or list of strings) to the target Java type,
 * per JAX-RS 4.0 §3.2.
 *
 * <p>Resolution order for a single value:</p>
 * <ol>
 *   <li>{@link String} — direct pass-through</li>
 *   <li>primitive / wrapper — {@code Integer.parseInt}, etc.</li>
 *   <li>{@link Enum} — {@link Enum#valueOf}</li>
 *   <li>public static method {@code valueOf(String)}</li>
 *   <li>public static method {@code fromString(String)}</li>
 *   <li>public single-argument constructor {@link String}</li>
 * </ol>
 *
 * <p>Collection types {@link List}, {@link Set}, {@link SortedSet}
 * parameterized over their element type are unwrapped.</p>
 */
public final class ParamValueConverter {

    private ParamValueConverter() {}

    /** Converts a list of raw values to the target type (potentially a collection). */
    public static Object coerce(Class<?> raw, Class<?> elementType, List<String> values) {
        if (isListLike(raw)) {
            List<Object> items = new ArrayList<>(values.size());
            for (String v : values) items.add(coerceSingle(elementType, v));
            if (Set.class == raw) return new LinkedHashSet<>(items);
            if (SortedSet.class == raw) return new TreeSet<>(items);
            return items;
        }
        if (values.isEmpty()) return defaultForType(raw);
        return coerceSingle(raw, values.get(0));
    }

    public static boolean isListLike(Class<?> raw) {
        return raw == List.class || raw == Set.class || raw == SortedSet.class
                || raw == Collection.class;
    }

    public static Object coerceSingle(Class<?> type, String raw) {
        if (raw == null) return defaultForType(type);
        if (type == String.class || type == CharSequence.class) return raw;
        if (PathSegment.class.isAssignableFrom(type)) return parsePathSegment(raw);
        return coerceIntegralOrBoolean(type, raw);
    }

    /** Boolean and integral types; any other type goes on to {@link #coerceFloatingOrChar}. */
    private static Object coerceIntegralOrBoolean(Class<?> type, String raw) {
        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
        if (type == byte.class    || type == Byte.class)    return Byte.parseByte(raw);
        if (type == short.class   || type == Short.class)   return Short.parseShort(raw);
        if (type == int.class     || type == Integer.class) return Integer.parseInt(raw);
        if (type == long.class    || type == Long.class)    return Long.parseLong(raw);
        return coerceFloatingOrChar(type, raw);
    }

    /** Floating-point and char types; any other type goes on to {@link #coerceByFactory}. */
    private static Object coerceFloatingOrChar(Class<?> type, String raw) {
        if (type == float.class   || type == Float.class)   return Float.parseFloat(raw);
        if (type == double.class  || type == Double.class)  return Double.parseDouble(raw);
        if (type == char.class    || type == Character.class) {
            if (raw.isEmpty()) throw badRequest("Empty value for char");
            return raw.charAt(0);
        }
        return coerceByFactory(type, raw);
    }

    /**
     * §3.2: an enum, then a static valueOf(String), a static fromString(String) or a String constructor. The
     * factory of a type is looked up once: looking it up for every value threw a NoSuchMethodException for each
     * candidate the type does not have.
     */
    private static Object coerceByFactory(Class<?> type, String raw) {
        return FACTORIES.get(type).apply(raw);
    }

    private static final ClassValue<Function<String, Object>> FACTORIES = new ClassValue<>() {
        @Override
        protected Function<String, Object> computeValue(Class<?> type) {
            return factoryOf(type);
        }
    };

    private static Function<String, Object> factoryOf(Class<?> type) {
        if (type.isEnum()) return enumFactoryOf(type);
        Method valueOf = staticMethod(type, "valueOf");
        if (valueOf != null) return raw -> invokeOrPropagate(valueOf, null, raw);
        Method fromString = staticMethod(type, "fromString");
        if (fromString != null) return raw -> invokeOrPropagate(fromString, null, raw);
        try {
            Constructor<?> c = type.getDeclaredConstructor(String.class);
            return raw -> newInstanceOrPropagate(c, raw);
        } catch (NoSuchMethodException _) {
            return raw -> {
                throw badRequest("No converter for type " + type.getName());
            };
        }
    }

    /**
     * §3.2: for an enum with fromString(String), it has priority over Enum.valueOf (otherwise a
     * built-in enum valueOf always wins); an unknown constant gives null.
     */
    private static Function<String, Object> enumFactoryOf(Class<?> type) {
        Method fromString = staticMethod(type, "fromString");
        if (fromString != null) return raw -> invokeOrPropagate(fromString, null, raw);
        @SuppressWarnings({"unchecked", "rawtypes"})
        Class<Enum> enumType = (Class<Enum>) type.asSubclass(Enum.class);
        return raw -> {
            try {
                return Enum.valueOf(enumType, raw);
            } catch (IllegalArgumentException _) {
                return null;
            }
        };
    }

    /** The static {@code name(String)} method {@code type} declares, or {@code null}. */
    private static Method staticMethod(Class<?> type, String name) {
        try {
            Method m = type.getDeclaredMethod(name, String.class);
            return Modifier.isStatic(m.getModifiers()) ? m : null;
        } catch (NoSuchMethodException _) {
            return null;
        }
    }

    public static Object defaultForType(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == char.class)    return Character.valueOf('\0');
        if (type == byte.class)    return Byte.valueOf((byte) 0);
        if (type == short.class)   return Short.valueOf((short) 0);
        if (type == int.class)     return Integer.valueOf(0);
        if (type == long.class)    return Long.valueOf(0L);
        if (type == float.class)   return Float.valueOf(0f);
        if (type == double.class)  return Double.valueOf(0d);
        return 0;
    }

    /** §3.2: PathSegment from a raw segment "path;k1=v1;k2=v2".
     *  Declaration order of matrix params is preserved (LinkedHashMap),
     *  required by the TCK which compares literal serialisations. */
    public static PathSegment parsePathSegment(String raw) {
        if (raw == null) raw = "";
        String[] parts = raw.split(";", -1);
        String path = parts[0];
        MultivaluedMap<String, String> matrix = new OrderedMultivaluedMap();
        for (int i = 1; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq < 0) matrix.add(parts[i], "");
            else matrix.add(parts[i].substring(0, eq), parts[i].substring(eq + 1));
        }
        final String p = path;
        final MultivaluedMap<String, String> m = matrix;
        return new PathSegment() {
            @Override public String getPath() { return p; }
            @Override public MultivaluedMap<String, String> getMatrixParameters() { return m; }
        };
    }

    /** MultivaluedMap that preserves insertion order of keys. */
    private static final class OrderedMultivaluedMap extends AbstractMultivaluedMap<String, String> {
        OrderedMultivaluedMap() { super(new java.util.LinkedHashMap<>()); }
    }

    private static WebApplicationException badRequest(String msg) {
        return new WebApplicationException(msg, Response.Status.BAD_REQUEST);
    }

    /** Calls a static method. If it throws a WebApplicationException
     *  (or wraps one via InvocationTargetException), it is propagated
     *  as-is — JAX-RS §3.2 requires honouring its status. Other
     *  exceptions become a 400 by default. */
    private static Object invokeOrPropagate(Method m, Object instance, Object... args) {
        try {
            return m.invoke(instance, args);
        } catch (IllegalAccessException e) {
            throw badRequest(m.getName() + " inaccessible: " + e.getMessage());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof WebApplicationException wae) throw wae;
            if (cause instanceof RuntimeException re) throw re;
            throw badRequest(m.getName() + " failed: " + (cause == null ? "?" : cause.getMessage()));
        }
    }

    private static Object newInstanceOrPropagate(Constructor<?> c, Object... args) {
        try {
            return c.newInstance(args);
        } catch (IllegalAccessException | InstantiationException e) {
            throw badRequest("Constructor(String) inaccessible: " + e.getMessage());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause();
            if (cause instanceof WebApplicationException wae) throw wae;
            if (cause instanceof RuntimeException re) throw re;
            throw badRequest("Constructor(String) failed: " + (cause == null ? "?" : cause.getMessage()));
        }
    }
}
