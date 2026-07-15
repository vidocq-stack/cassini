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

import java.util.List;

/**
 * SPI implemented by generated {@code <ResourceClass>$$CassiniRoutes} classes.
 *
 * <p>One implementation is emitted per {@code @Path} resource class that has only
 * direct resource methods (no sub-resource locators / {@code dynamicLocator} routes).
 * When a class has locators, the generator emits <em>no</em> implementation (or sets
 * {@link #hasLocators()} to {@code true}), causing {@code RouteRegistry} to fall back
 * to {@code ResourceScanner.discover} for that class.</p>
 *
 * <p>Implementations are discovered by {@code RouteRegistry} via
 * {@code Class.forName("<ResourceClass>$$CassiniRoutes")} — the same lookup-by-name
 * pattern used by {@code AdapterRegistry} for {@code $$CassiniAdapter}.</p>
 */
public interface RouteProvider {

    /**
     * Returns the list of route descriptors for the resource class, encoded as
     * string/class literals — no reflection, no annotation access.
     *
     * @return immutable list of route descriptors
     */
    List<RouteDescriptor> routes();

    /**
     * Returns {@code true} if the resource class has sub-resource locators or
     * {@code dynamicLocator} catch-all routes that cannot be faithfully pre-generated.
     *
     * <p>When {@code true}, {@code RouteRegistry} discards this provider's {@link #routes()}
     * result (which will be empty) and falls back to {@code ResourceScanner.discover} for
     * the entire class.</p>
     *
     * <p>Default: {@code false} — generated implementations override only when needed.</p>
     */
    default boolean hasLocators() {
        return false;
    }

    /**
     * Returns the {@code @Path} resource class this provider was generated for, or {@code null}
     * when it does not advertise it.
     *
     * <p><b>ServiceLoader keying:</b> when a route provider is registered as a {@code ServiceLoader}
     * provider (module-path {@code provides RouteProvider with <Class>$$CassiniRoutes}, or a
     * {@code META-INF/services} entry), {@code RouteRegistry} builds a {@code Class → provider} map
     * keyed by this method, so a strict Java Modules application can keep its resource package <em>closed</em>
     * (no {@code opens}, no {@code exports}) — the module system instantiates the provider and
     * cassini-core never calls {@code Class.forName} into the package.</p>
     *
     * <p>APT-generated providers override this with {@code return <Class>.class;}. The default
     * returns {@code null}, which simply excludes the provider from the {@code ServiceLoader} map;
     * resolution then falls back to {@code Class.forName} / {@code ResourceScanner}.</p>
     *
     * @return the resource class, or {@code null} if not advertised
     */
    default Class<?> resourceClass() {
        return null;
    }
}
