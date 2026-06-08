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
package io.vidocq.cassini.client;

import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientBuilderDiscoveryTest {

    @Test
    void newBuilder_resolves_cassini_provider_via_serviceloader() {
        ClientBuilder b = ClientBuilder.newBuilder();
        assertNotNull(b);
        assertTrue(b.getClass().getName().startsWith("io.vidocq.cassini.client"),
                "Expected Cassini provider but got " + b.getClass().getName());
    }

    @Test
    void newClient_returns_open_client_with_client_runtime_type() {
        try (Client c = ClientBuilder.newClient()) {
            assertNotNull(c);
            assertNotNull(c.getConfiguration());
            assertEquals(RuntimeType.CLIENT, c.getConfiguration().getRuntimeType());
        }
    }
}
