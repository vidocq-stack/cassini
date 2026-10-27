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

import io.vidocq.cassini.client.support.RecordingServer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Configuration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Properties ({@code Configurable.property}, {@code Invocation.Builder.property}, {@code Invocation.property}):
 * a {@code null} value removes the property, and a request property reaches {@code ClientRequestContext}.
 */
class PropertiesTest {

    private static final String KEY = "k";
    private static final String VALUE = "v";

    private static RecordingServer server;
    private static Client client;
    private static final AtomicReference<Object> SEEN = new AtomicReference<>();

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
        client = ClientBuilder.newClient();
        client.register((ClientRequestFilter) ctx -> SEEN.set(ctx.getProperty(KEY)));
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    @BeforeEach
    void reset() {
        SEEN.set("unset");
    }

    @Test
    void null_value_removes_a_configuration_property() {
        Configuration builderConfig = ClientBuilder.newBuilder().property(KEY, VALUE).property(KEY, null).getConfiguration();
        assertFalse(builderConfig.getPropertyNames().contains(KEY));

        try (Client other = ClientBuilder.newClient()) {
            other.property(KEY, VALUE).property(KEY, null);
            assertFalse(other.getConfiguration().getPropertyNames().contains(KEY));
            assertNull(other.getConfiguration().getProperty(KEY));

            WebTarget target = other.target(server.baseUrl());
            target.property(KEY, VALUE).property(KEY, null);
            assertFalse(target.getConfiguration().getPropertyNames().contains(KEY));
        }
    }

    @Test
    void invocation_builder_property_reaches_the_request_context_only() {
        client.target(server.baseUrl()).request().property(KEY, VALUE).get();
        assertEquals(VALUE, SEEN.get());
        assertNull(client.getConfiguration().getProperty(KEY));

        client.target(server.baseUrl()).request().get();
        assertNull(SEEN.get());
    }

    @Test
    void null_value_removes_an_invocation_builder_property() {
        client.target(server.baseUrl()).request().property(KEY, VALUE).property(KEY, null).get();
        assertNull(SEEN.get());
    }

    @Test
    void invocation_property_reaches_the_request_context() {
        client.target(server.baseUrl()).request().buildGet().property(KEY, VALUE).invoke();
        assertEquals(VALUE, SEEN.get());
    }
}
