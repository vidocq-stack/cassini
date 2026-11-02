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

import io.vidocq.cassini.runtime.CassiniMaintenance;
import jakarta.json.bind.Jsonb;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The client's default JSON-B instance is built under the context class loader of the layer that first used it, and
 * holds that layer's generated bindings: it is dropped with the other caches when the host stops the layer
 * (static audit, vidocq-workspace#40 item 25).
 */
class ClientEntityJsonbResetTest {

    private static Jsonb defaultJsonb() {
        return ClientEntityJsonb.resolve(List.of(), Object.class, MediaType.APPLICATION_JSON_TYPE);
    }

    @Test
    void theDefaultInstanceIsSharedUntilTheLayerStops() {
        assertSame(defaultJsonb(), defaultJsonb());
    }

    @Test
    void theMaintenanceResetDropsTheDefaultInstance() {
        Jsonb before = defaultJsonb();

        CassiniMaintenance.resetDiscoveryCaches();

        assertNotSame(before, defaultJsonb());
    }
}
