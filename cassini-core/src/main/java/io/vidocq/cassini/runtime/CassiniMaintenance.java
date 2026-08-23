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
package io.vidocq.cassini.runtime;

import io.vidocq.cassini.internal.RouteRegistry;
import io.vidocq.cassini.internal.gen.AdapterRegistry;

/**
 * Maintenance entry points for runtimes that host Cassini across application reloads.
 *
 * <p>The route and adapter discovery caches ({@link RouteRegistry},
 * {@link AdapterRegistry}) are static and keyed by application {@code Class} objects.
 * A host that hot-reloads the application <em>in the same JVM</em> — the Vidocq dev mode
 * re-creates the application module layer with a fresh class loader — must discard them
 * between two deployments, otherwise lookups miss (old-layer keys) and dispatch falls
 * back to reflection against encapsulated packages.
 */
public final class CassiniMaintenance {

    private CassiniMaintenance() {}

    /** Discards every discovery cache; the next deployment rebuilds them from scratch. */
    public static void resetDiscoveryCaches() {
        RouteRegistry.resetAll();
        AdapterRegistry.resetAll();
    }
}
