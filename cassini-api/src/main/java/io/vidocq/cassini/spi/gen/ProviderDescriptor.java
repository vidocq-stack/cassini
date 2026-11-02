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
package io.vidocq.cassini.spi.gen;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Map;

/**
 * What Cassini reads on a provider class ({@code @Provider}: message body readers and writers, exception mappers,
 * filters, context resolvers…), written at build time by {@code cassini-processor} so that the runtime does not
 * read it by reflection. One descriptor per {@code @Provider} class, published as a {@link java.util.ServiceLoader}
 * provider of this interface. A class without a descriptor keeps the reflective reading.
 */
public interface ProviderDescriptor {

    /** @return the {@code @Provider} class this descriptor was generated for */
    Class<?> providerClass();

    /** @return the value of its {@code @Priority}, or {@code null} when it has none */
    Integer priority();

    /** @return the value of its {@code @Produces}, or {@code null} when it has none */
    String[] produces();

    /** @return the value of its {@code @Consumes}, or {@code null} when it has none */
    String[] consumes();

    /** @return whether it is annotated {@code @PreMatching} */
    boolean preMatching();

    /** @return the value of its {@code @ConstrainedTo} ({@code "CLIENT"} or {@code "SERVER"}), or {@code null} */
    String constrainedTo();

    /** @return the name-binding annotations it carries ({@code @NameBinding} meta-annotated), possibly empty */
    List<Class<? extends Annotation>> nameBindings();

    /**
     * @return for each generic provider interface it implements ({@code MessageBodyReader}, {@code MessageBodyWriter},
     *         {@code ExceptionMapper}, {@code ContextResolver}), the class its type argument stands for
     *         ({@code Object} for a type variable or a wildcard)
     */
    Map<Class<?>, Class<?>> typeArguments();
}
