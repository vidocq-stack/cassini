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

import jakarta.ws.rs.BeanParam;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.MatrixParam;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.PathSegment;
import jakarta.ws.rs.sse.SseEventSink;

import java.lang.annotation.Annotation;
import java.lang.reflect.Executable;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The parameters of a resource method, or of a sub-resource locator, as resolving them on a
 * request needs them (§3.2): where each value comes from, under which name, with which default.
 *
 * <p>Read once per method. The JDK takes a lock on every {@code Parameter.getAnnotation} and
 * parses the annotations of every parameter of the method again on every
 * {@code Parameter.getAnnotations()}: resolving the arguments from them cost a dozen of the
 * first and up to two of the second per parameter and per request.</p>
 *
 * <p>The cache hangs off the declaring class through a {@link ClassValue}: it goes away with
 * the class, when an application is undeployed.</p>
 */
final class MethodParameters {

    /** Where the value of a parameter comes from (§3.2, §8.2, §9.2), by order of precedence. */
    enum Source { CONTEXT, SUSPENDED, BEAN, PATH, QUERY, HEADER, COOKIE, FORM, MATRIX, ENTITY, NONE }

    private static final ClassValue<Map<Executable, MethodParameters>> BY_CLASS = new ClassValue<>() {
        @Override
        protected Map<Executable, MethodParameters> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private final Param[] params;
    private final int suspended;
    private final int sseSink;

    private MethodParameters(Executable executable) {
        Parameter[] declared = executable.getParameters();
        params = new Param[declared.length];
        int suspendedAt = -1;
        int sseSinkAt = -1;
        for (int i = 0; i < declared.length; i++) {
            Param p = new Param(declared[i]);
            params[i] = p;
            if (suspendedAt < 0 && p.source == Source.SUSPENDED && AsyncResponse.class.isAssignableFrom(p.type)) {
                suspendedAt = i;
            }
            if (sseSinkAt < 0 && p.type == SseEventSink.class) sseSinkAt = i;
        }
        suspended = suspendedAt;
        sseSink = sseSinkAt;
    }

    /** The parameters of {@code executable}, read on the first call and shared afterwards. */
    static MethodParameters of(Executable executable) {
        return BY_CLASS.get(executable.getDeclaringClass()).computeIfAbsent(executable, MethodParameters::new);
    }

    int size() {
        return params.length;
    }

    Param get(int index) {
        return params[index];
    }

    /** The index of the first {@code @Suspended AsyncResponse} parameter (§8.2), or -1. */
    int suspended() {
        return suspended;
    }

    /** The index of the first {@code SseEventSink} parameter (§9.5), or -1. */
    int sseSink() {
        return sseSink;
    }

    /** One parameter: its type, its annotations, and what they say about its value. */
    static final class Param {
        private final Parameter parameter;
        private final Class<?> type;
        private final Type genericType;
        private final Class<?> element;
        private final Annotation[] annotations;
        private final Source source;
        private final String name;
        private final String defaultValue;
        private final boolean encoded;

        private Param(Parameter parameter) {
            this.parameter = parameter;
            this.type = parameter.getType();
            this.genericType = parameter.getParameterizedType();
            this.element = ParamValueConverter.isListLike(type) ? elementOf(genericType) : type;
            this.annotations = parameter.getAnnotations();
            Annotation from = null;
            Source chosen = null;
            String defaulted = null;
            boolean encodedHere = false;
            boolean anyJaxRs = false;
            for (Annotation a : annotations) {
                Source s = sourceOf(a);
                if (s != null && (chosen == null || s.ordinal() < chosen.ordinal())) {
                    chosen = s;
                    from = a;
                }
                if (a instanceof DefaultValue d) defaulted = d.value();
                if (a instanceof Encoded) encodedHere = true;
                anyJaxRs |= a.annotationType().getName().startsWith("jakarta.ws.rs.");
            }
            if (chosen == null) chosen = anyJaxRs ? Source.NONE : Source.ENTITY;
            this.source = chosen;
            this.name = nameOf(from);
            this.defaultValue = defaulted;
            this.encoded = encodedHere;
        }

        /** The reflective parameter, for its name in error messages. */
        Parameter parameter() {
            return parameter;
        }

        Class<?> type() {
            return type;
        }

        Type genericType() {
            return genericType;
        }

        /** The element type of a {@code List}, {@code Set}, {@code SortedSet} or {@code Collection}
         *  parameter ({@code String} when it is not a class), the type itself otherwise. */
        Class<?> element() {
            return element;
        }

        /** The annotations of the parameter, shared: providers receive them read-only (§4.2.1). */
        Annotation[] annotations() {
            return annotations;
        }

        Source source() {
            return source;
        }

        /** The name given to the source annotation ({@code @PathParam("id")} → {@code id}), or null. */
        String name() {
            return name;
        }

        /** The value of {@code @DefaultValue}, or null. */
        String defaultValue() {
            return defaultValue;
        }

        /** True when the parameter itself carries {@code @Encoded}. */
        boolean encoded() {
            return encoded;
        }

        /** True when a {@code @PathParam} wants the raw segments, matrix parameters included. */
        boolean wantsPathSegments() {
            return PathSegment.class.isAssignableFrom(type);
        }

        /** §3.2: a value that cannot be converted is a 404 for a path, matrix or query parameter,
         *  a 400 otherwise. */
        boolean notFoundWhenInvalid() {
            return source == Source.PATH || source == Source.MATRIX || source == Source.QUERY;
        }

        private static Source sourceOf(Annotation a) {
            return switch (a) {
                case Context _ -> Source.CONTEXT;
                case Suspended _ -> Source.SUSPENDED;
                case BeanParam _ -> Source.BEAN;
                case PathParam _ -> Source.PATH;
                case QueryParam _ -> Source.QUERY;
                case HeaderParam _ -> Source.HEADER;
                case CookieParam _ -> Source.COOKIE;
                case FormParam _ -> Source.FORM;
                case MatrixParam _ -> Source.MATRIX;
                default -> null;
            };
        }

        private static String nameOf(Annotation a) {
            return switch (a) {
                case PathParam p -> p.value();
                case QueryParam q -> q.value();
                case HeaderParam h -> h.value();
                case CookieParam c -> c.value();
                case FormParam f -> f.value();
                case MatrixParam m -> m.value();
                case null, default -> null;
            };
        }

        private static Class<?> elementOf(Type t) {
            if (t instanceof ParameterizedType pt && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
            return String.class;
        }
    }
}
