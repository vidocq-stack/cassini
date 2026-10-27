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
package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Server;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The SE bootstrap instance of the Chappe transport: it starts, unwraps to its server and stops. */
class ChappeSeBootstrapTest {

    @Test
    void a_started_instance_unwraps_to_its_chappe_server_and_stops() throws Exception {
        SeBootstrap.Configuration config = SeBootstrap.Configuration.builder().port(0).build();
        SeBootstrap.Instance instance = new ChappeRuntimeDelegate()
                .bootstrap(new Application(), config).toCompletableFuture().get(30, TimeUnit.SECONDS);
        try {
            assertNotNull(instance.unwrap(Server.class));
            int port = (Integer) instance.configuration().property(SeBootstrap.Configuration.PORT);
            assertTrue(port > 0, "the effective configuration carries the port picked: " + port);
            assertThrows(IllegalArgumentException.class, () -> instance.unwrap(String.class));
        } finally {
            assertNotNull(instance.stop().toCompletableFuture().get(30, TimeUnit.SECONDS));
        }
    }
}
