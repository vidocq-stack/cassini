/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
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
