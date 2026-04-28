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

/**
 * Coercion d'une chaîne (ou d'une liste de chaînes) vers le type Java cible,
 * selon JAX-RS 4.0 §3.2.
 *
 * <p>Ordre de résolution pour une valeur simple :</p>
 * <ol>
 *   <li>{@link String} — passage direct</li>
 *   <li>primitive / wrapper — {@code Integer.parseInt}, etc.</li>
 *   <li>{@link Enum} — {@link Enum#valueOf}</li>
 *   <li>méthode statique publique {@code valueOf(String)}</li>
 *   <li>méthode statique publique {@code fromString(String)}</li>
 *   <li>constructeur public à un argument {@link String}</li>
 * </ol>
 *
 * <p>Les types collection {@link List}, {@link Set}, {@link SortedSet}
 * paramétrés sont dépliés sur leur type d'élément.</p>
 */
public final class ParamValueConverter {

    private ParamValueConverter() {}

    /** Convertit une liste de valeurs brutes vers le type cible (potentiellement collection). */
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

        if (type == boolean.class || type == Boolean.class) return Boolean.parseBoolean(raw);
        if (type == byte.class    || type == Byte.class)    return Byte.parseByte(raw);
        if (type == short.class   || type == Short.class)   return Short.parseShort(raw);
        if (type == int.class     || type == Integer.class) return Integer.parseInt(raw);
        if (type == long.class    || type == Long.class)    return Long.parseLong(raw);
        if (type == float.class   || type == Float.class)   return Float.parseFloat(raw);
        if (type == double.class  || type == Double.class)  return Double.parseDouble(raw);
        if (type == char.class    || type == Character.class) {
            if (raw.isEmpty()) throw badRequest("Empty value for char");
            return raw.charAt(0);
        }

        if (type.isEnum()) {
            // §3.2 : pour un enum avec fromString(String), celui-ci a priorité
            // sur Enum.valueOf (sinon un enum built-in valueOf chope toujours).
            try {
                Method fs = type.getDeclaredMethod("fromString", String.class);
                if (Modifier.isStatic(fs.getModifiers())) {
                    return invokeOrPropagate(fs, null, raw);
                }
            } catch (NoSuchMethodException ignored) {}
            try {
                @SuppressWarnings({"unchecked", "rawtypes"})
                Enum<?> e = Enum.valueOf((Class<Enum>) type.asSubclass(Enum.class), raw);
                return e;
            } catch (IllegalArgumentException iae) {
                return null;
            }
        }

        try {
            Method valueOf = type.getDeclaredMethod("valueOf", String.class);
            if (Modifier.isStatic(valueOf.getModifiers())) {
                return invokeOrPropagate(valueOf, null, raw);
            }
        } catch (NoSuchMethodException ignored) {}
        try {
            Method fromString = type.getDeclaredMethod("fromString", String.class);
            if (Modifier.isStatic(fromString.getModifiers())) {
                return invokeOrPropagate(fromString, null, raw);
            }
        } catch (NoSuchMethodException ignored) {}
        try {
            Constructor<?> c = type.getDeclaredConstructor(String.class);
            return newInstanceOrPropagate(c, raw);
        } catch (NoSuchMethodException ignored) {}
        throw badRequest("No converter for type " + type.getName());
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

    /** §3.2 : PathSegment depuis un segment brut "path;k1=v1;k2=v2".
     *  L'ordre de déclaration des matrix params est préservé (LinkedHashMap),
     *  exigé par les TCK qui comparent les sérialisations littérales. */
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

    /** MultivaluedMap qui préserve l'ordre d'insertion des clés. */
    private static final class OrderedMultivaluedMap extends AbstractMultivaluedMap<String, String> {
        OrderedMultivaluedMap() { super(new java.util.LinkedHashMap<>()); }
    }

    private static WebApplicationException badRequest(String msg) {
        return new WebApplicationException(msg, Response.Status.BAD_REQUEST);
    }

    /** Appelle une méthode statique. Si elle lève une WebApplicationException
     *  (ou l'emballe via InvocationTargetException), on la propage
     *  telle quelle — JAX-RS §3.2 impose de respecter son status. Les
     *  autres exceptions deviennent un 400 par défaut. */
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
