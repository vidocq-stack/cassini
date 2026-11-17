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
package io.vidocq.cassini.cdi.internal;

import jakarta.enterprise.context.spi.CreationalContext;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Default;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The beans of a container by bean class, read from the {@link BeanManager} once (CASSINI-061).
 *
 * <p>A resource or provider of a bounded generic class, {@code class NumberWriter<T extends Number>}, has the bean
 * type {@code NumberWriter<T extends Number>}, and CDI 4.1 §5.2.4 does not let the raw class {@code NumberWriter}
 * resolve it. Cassini, which asks for a class, then finds the bean by its bean class here.</p>
 */
public final class BeansByClass {

    /**
     * A bean found by its bean class, with the bean type that names the class: the class itself, or the class with
     * its type parameters, {@code NumberWriter<T>}.
     *
     * @param bean the bean
     * @param type the bean type of {@code bean} whose raw type is the bean class
     */
    public record BeanOfClass(Bean<?> bean, Type type) {

        /**
         * A reference of the bean typed as its own class, {@link BeanManager#getReference} with {@link #type()}: the
         * instance itself for a {@code @Dependent} bean.
         */
        public Object reference(BeanManager beanManager, CreationalContext<?> context) {
            return beanManager.getReference(bean, type, context);
        }
    }

    private final Map<Class<?>, List<Bean<?>>> beans;

    /** Walks the beans of the container once, whatever their qualifiers. */
    public BeansByClass(BeanManager beanManager) {
        Map<Class<?>, List<Bean<?>>> byClass = new LinkedHashMap<>();
        for (Bean<?> bean : beanManager.getBeans(Object.class, Any.Literal.INSTANCE)) {
            Class<?> beanClass = bean.getBeanClass();
            if (beanClass != null) {
                byClass.computeIfAbsent(beanClass, _ -> new ArrayList<>(1)).add(bean);
            }
        }
        this.beans = Collections.unmodifiableMap(byClass);
    }

    /** The bean classes, in the order the container lists its beans, whatever their qualifiers. */
    public Set<Class<?>> beanClasses() {
        return beans.keySet();
    }

    /**
     * The bean of {@code beanClass} that typesafe resolution of the raw class would give, were the class not generic:
     * among the beans of that bean class, those that have the {@code @Default} qualifier, which a lookup by class
     * requires, and the class itself among their bean types, raw or parameterized (a producer the class declares has
     * not), resolved as typesafe resolution would, an enabled alternative first.
     *
     * @return the bean and its bean type that names the class, or {@code null} when the class has none
     * @throws jakarta.enterprise.inject.AmbiguousResolutionException when it has more than one and no alternative
     *         decides
     */
    public BeanOfClass beanOf(Class<?> beanClass, BeanManager beanManager) {
        Map<Bean<?>, Type> own = new HashMap<>();
        for (Bean<?> bean : beans.getOrDefault(beanClass, List.of())) {
            Type type = typeNaming(bean, beanClass);
            if (type != null && isDefault(bean)) {
                own.put(bean, type);
            }
        }
        if (own.isEmpty()) {
            return null;
        }
        Bean<?> resolved = beanManager.resolve(own.keySet());
        return resolved == null ? null : new BeanOfClass(resolved, own.get(resolved));
    }

    /** The bean type of {@code bean} whose raw type is {@code beanClass}, or {@code null}. */
    private static Type typeNaming(Bean<?> bean, Class<?> beanClass) {
        for (Type type : bean.getTypes()) {
            Type raw = type instanceof ParameterizedType parameterized ? parameterized.getRawType() : type;
            if (raw == beanClass) {
                return type;
            }
        }
        return null;
    }

    /** Whether the bean has {@code @Default}, the qualifier a lookup by class requires. */
    private static boolean isDefault(Bean<?> bean) {
        for (Annotation qualifier : bean.getQualifiers()) {
            if (qualifier.annotationType() == Default.class) {
                return true;
            }
        }
        return false;
    }
}
