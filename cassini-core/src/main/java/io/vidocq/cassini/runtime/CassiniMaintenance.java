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
import io.vidocq.cassini.internal.json.CassiniJsonbReaderWriter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Maintenance entry points for runtimes that host Cassini across application reloads.
 *
 * <p>The route and adapter discovery caches ({@link RouteRegistry},
 * {@link AdapterRegistry}) and the default JSON-B instance of the built-in reader/writer are static and
 * keyed by, or holding, application {@code Class} objects.
 * A host that hot-reloads the application <em>in the same JVM</em> — the Vidocq dev mode
 * re-creates the application module layer with a fresh class loader — must discard them
 * between two deployments, otherwise lookups miss (old-layer keys) and dispatch falls
 * back to reflection against encapsulated packages.
 */
public final class CassiniMaintenance {

    /** What the modules built on this one asked to be dropped with the rest, see {@link #onReset(Runnable)}. */
    private static final List<Runnable> HOOKS = new CopyOnWriteArrayList<>();

    private CassiniMaintenance() {}

    /** Discards every discovery cache and shared JSON-B instance; the next deployment rebuilds them from scratch. */
    public static void resetDiscoveryCaches() {
        RouteRegistry.resetAll();
        AdapterRegistry.resetAll();
        CassiniJsonbReaderWriter.resetDefaultJsonb();
        HOOKS.forEach(Runnable::run);
    }

    /**
     * Adds {@code hook} to what {@link #resetDiscoveryCaches()} runs: a module that keeps something shared for the
     * life of the JVM and that depends on the application layer (the client's default JSON-B instance, say) drops it
     * there, once, when it first needs it. The hook belongs to the library, never to the application.
     */
    public static void onReset(Runnable hook) {
        HOOKS.add(hook);
    }
}
