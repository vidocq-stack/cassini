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

import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of {@link ExceptionMapper}s annotated with {@code @Provider}.
 *
 * <p>Mapper selection (§4.4): prefer the mapper whose parameterized exception type
 * is the most specific (closest to the concrete class) among those that match.</p>
 */
public final class ExceptionMapperRegistry {

    private final List<Registration<?>> mappers = new ArrayList<>();

    public record Registration<T extends Throwable>(Class<T> exceptionType, ExceptionMapper<T> mapper) {}

    public <T extends Throwable> void register(Class<T> exceptionType, ExceptionMapper<T> mapper) {
        mappers.add(new Registration<>(exceptionType, mapper));
    }

    /** Registers a {@code @Provider} instance implementing {@link ExceptionMapper} if applicable.
     *  Resolves the exception type via generic interfaces. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public void register(Object instance) {
        if (!(instance instanceof ExceptionMapper)) return;
        Class<? extends Throwable> excType = resolveExceptionType(instance.getClass());
        if (excType == null) return;
        mappers.add(new Registration(excType, (ExceptionMapper) instance));
    }

    /** §4.4: if an ExceptionMapper itself throws an exception during
     *  its own execution, that exception must not be mapped again —
     *  it must propagate as a 500. Lexically-scoped reentrancy flag
     *  (M2h: ScopedValue rebinding — works on any thread, nothing to reset). */
    private static final ScopedValue<Boolean> MAPPING = ScopedValue.newInstance();

    public Optional<Response> map(Throwable t) {
        if (MAPPING.orElse(false)) return Optional.empty();
        Registration<?> best = null;
        for (Registration<?> r : mappers) {
            if (r.exceptionType().isInstance(t)) {
                if (best == null) {
                    best = r;
                } else if (best.exceptionType() == r.exceptionType()) {
                    // §4.4 / §4.1.4: same exception type → lower priority wins.
                    if (priorityOf(r.mapper()) < priorityOf(best.mapper())) best = r;
                } else if (best.exceptionType().isAssignableFrom(r.exceptionType())) {
                    best = r;
                }
            }
        }
        if (best == null) return Optional.empty();
        final Registration<?> chosen = best;
        return ScopedValue.where(MAPPING, true).call(() -> {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Response r = ((ExceptionMapper) chosen.mapper()).toResponse(t);
            return Optional.ofNullable(r);
        });
    }

    /** §10.2: returns the most specific mapper for {@code type} without executing it. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends Throwable> ExceptionMapper<T> findMapper(Class<T> type) {
        Registration<?> best = null;
        for (Registration<?> r : mappers) {
            if (r.exceptionType().isAssignableFrom(type)) {
                if (best == null || best.exceptionType().isAssignableFrom(r.exceptionType())) {
                    best = r;
                }
            }
        }
        return best == null ? null : (ExceptionMapper<T>) best.mapper();
    }

    public int size() { return mappers.size(); }

    private static int priorityOf(Object o) {
        jakarta.annotation.Priority p = o.getClass().getAnnotation(jakarta.annotation.Priority.class);
        return p == null ? jakarta.ws.rs.Priorities.USER : p.value();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable> resolveExceptionType(Class<?> mapperClass) {
        for (Type iface : mapperClass.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt
                    && pt.getRawType() == ExceptionMapper.class
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c
                    && Throwable.class.isAssignableFrom(c)) {
                return (Class<? extends Throwable>) c;
            }
        }
        Class<?> sup = mapperClass.getSuperclass();
        return sup == null || sup == Object.class ? null : resolveExceptionType(sup);
    }
}
