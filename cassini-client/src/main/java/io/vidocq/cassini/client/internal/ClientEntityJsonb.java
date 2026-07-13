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
package io.vidocq.cassini.client.internal;

import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;

import java.lang.reflect.ParameterizedType;
import java.util.Collection;
import java.util.Locale;

/**
 * Client-side JSON-B entity (de)serialisation, honouring a registered
 * {@link ContextResolver}{@code <Jsonb>} exactly as the server does (JAX-RS
 * §4.3 / §9.2): if the client registers a {@code ContextResolver<Jsonb>} whose
 * {@code @Produces} matches the media type and whose {@code getContext(type)}
 * returns a {@link Jsonb}, that instance is used; otherwise a default
 * {@code JsonbBuilder.create()} instance is used.
 *
 * <p>This is the client counterpart of the built-in JSON-B message body
 * reader/writer — it lets {@code Entity.entity(pojo, APPLICATION_JSON)} and
 * {@code readEntity(Pojo.class)} round-trip real objects instead of falling back
 * to {@code toString()}.</p>
 */
final class ClientEntityJsonb {

    private static final Jsonb DEFAULT = JsonbBuilder.create();

    private ClientEntityJsonb() {
    }

    /** True for JSON media types ({@code application/json}, {@code *+json}). */
    static boolean isJson(MediaType mt) {
        if (mt == null) {
            return false;
        }
        if (mt.isWildcardType() || mt.isWildcardSubtype()) {
            return false;
        }
        if (mt.isCompatible(MediaType.APPLICATION_JSON_TYPE)) {
            return true;
        }
        String sub = mt.getSubtype();
        return sub != null && sub.toLowerCase(Locale.ROOT).endsWith("+json");
    }

    /** Types already handled by the built-in String/byte[]/InputStream paths. */
    static boolean isSimple(Class<?> type) {
        return type == String.class
                || type == byte[].class
                || java.io.InputStream.class.isAssignableFrom(type)
                || type == Void.class
                || type == void.class;
    }

    /**
     * Resolves the {@link Jsonb} to use for {@code type} at {@code mt}, preferring a
     * registered {@code ContextResolver<Jsonb>}. Never returns {@code null}.
     */
    @SuppressWarnings("unchecked")
    static Jsonb resolve(Collection<Object> registered, Class<?> type, MediaType mt) {
        if (registered != null) {
            for (Object inst : registered) {
                if (!(inst instanceof ContextResolver<?> cr)) {
                    continue;
                }
                if (resolveContextType(cr.getClass()) != Jsonb.class) {
                    continue;
                }
                if (!producesMatches(cr.getClass(), mt)) {
                    continue;
                }
                Jsonb j = ((ContextResolver<Jsonb>) cr).getContext(type);
                if (j != null) {
                    return j;
                }
            }
        }
        return DEFAULT;
    }

    private static boolean producesMatches(Class<?> crClass, MediaType mt) {
        Produces p = crClass.getAnnotation(Produces.class);
        if (p == null || p.value().length == 0) {
            return true; // no @Produces → wildcard
        }
        if (mt == null) {
            return true;
        }
        for (String v : p.value()) {
            if (MediaType.valueOf(v).isCompatible(mt)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> resolveContextType(Class<?> cls) {
        for (java.lang.reflect.Type iface : cls.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt
                    && pt.getRawType() == ContextResolver.class
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
        }
        Class<?> sup = cls.getSuperclass();
        return sup == null || sup == Object.class ? null : resolveContextType(sup);
    }
}
