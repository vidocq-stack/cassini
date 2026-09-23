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
package io.vidocq.cassini.spi.http;

import java.util.Objects;
import java.util.Set;

/**
 * One route of a {@link CassiniStack}, as {@link CassiniStack#routes()} lists it.
 *
 * <p>Every component is a plain value: reading a route needs no Cassini class, and
 * the record holds no {@code Method} or {@code Class} reference that would keep the
 * application's class loader alive after a host has discarded it.
 *
 * @param httpMethod    the HTTP method, {@code "GET"} for instance; empty for a
 *                      sub-resource locator whose target class is only known when a
 *                      request reaches it
 * @param path          the full path template, class and method {@code @Path}
 *                      combined, {@code "/tasks/{id}"} for instance
 * @param resourceClass the binary name of the class declaring the method that
 *                      handles the route — for a route reached through a locator,
 *                      the sub-resource class
 * @param methodName    the name of that method
 * @param produces      the media types the method declares it produces, empty when
 *                      it declares none
 * @param consumes      the media types the method declares it consumes, empty when
 *                      it declares none
 */
public record RouteDescription(
        String httpMethod,
        String path,
        String resourceClass,
        String methodName,
        Set<String> produces,
        Set<String> consumes) {

    public RouteDescription {
        Objects.requireNonNull(httpMethod, "httpMethod");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(resourceClass, "resourceClass");
        Objects.requireNonNull(methodName, "methodName");
        produces = Set.copyOf(produces);
        consumes = Set.copyOf(consumes);
    }

    /** True for a sub-resource locator, whose HTTP method is resolved per request. */
    public boolean isLocator() {
        return httpMethod.isEmpty();
    }
}
