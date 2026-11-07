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

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CASSINI-024 item 6: {@code register(component, int)} binds the priority to every contract
 * the component implements, the ones inherited from a superclass or a super-interface included
 * (Jakarta REST 4.0 {@code Configurable.register(Class, int)}).
 */
class RegisterContractsTest {

    private static final int PRIORITY = 1234;

    /** Direct implementation, the case that always worked. */
    public static class Direct implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) throws IOException { /* no-op */ }
    }

    /** Contract inherited from the superclass. */
    public static class FromSuperclass extends Direct { }

    /** Contract inherited from a super-interface. */
    public interface Both extends ClientRequestFilter, ClientResponseFilter { }

    public static class FromSuperInterface implements Both {
        @Override public void filter(ClientRequestContext ctx) throws IOException { /* no-op */ }
        @Override public void filter(ClientRequestContext req, ClientResponseContext res) throws IOException { /* no-op */ }
    }

    @Test
    void a_contract_inherited_from_a_superclass_gets_the_priority_on_the_client() {
        try (Client client = ClientBuilder.newClient()) {
            client.register(FromSuperclass.class, PRIORITY);
            Map<Class<?>, Integer> contracts = client.getConfiguration().getContracts(FromSuperclass.class);
            assertEquals(PRIORITY, contracts.get(ClientRequestFilter.class));
        }
    }

    @Test
    void a_contract_inherited_from_a_superclass_gets_the_priority_on_an_instance() {
        try (Client client = ClientBuilder.newClient()) {
            client.register(new FromSuperclass(), PRIORITY);
            Map<Class<?>, Integer> contracts = client.getConfiguration().getContracts(FromSuperclass.class);
            assertEquals(PRIORITY, contracts.get(ClientRequestFilter.class));
        }
    }

    @Test
    void contracts_inherited_from_a_super_interface_get_the_priority() {
        try (Client client = ClientBuilder.newClient()) {
            client.register(FromSuperInterface.class, PRIORITY);
            Map<Class<?>, Integer> contracts = client.getConfiguration().getContracts(FromSuperInterface.class);
            assertEquals(PRIORITY, contracts.get(ClientRequestFilter.class));
            assertEquals(PRIORITY, contracts.get(ClientResponseFilter.class));
            assertEquals(PRIORITY, contracts.get(Both.class));
        }
    }

    @Test
    void the_builder_binds_inherited_contracts_too() {
        try (Client client = ClientBuilder.newBuilder().register(FromSuperclass.class, PRIORITY).build()) {
            assertEquals(PRIORITY,
                    client.getConfiguration().getContracts(FromSuperclass.class).get(ClientRequestFilter.class));
        }
    }

    @Test
    void a_direct_contract_still_gets_the_priority() {
        try (Client client = ClientBuilder.newClient()) {
            client.register(Direct.class, PRIORITY);
            assertEquals(PRIORITY, client.getConfiguration().getContracts(Direct.class).get(ClientRequestFilter.class));
        }
    }
}
