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
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * CASSINI-018: run without any JSON-B provider (see the {@code without-jsonb} surefire execution).
 * Entities that are not JSON must still work; JSON ones fail with a {@link ProcessingException}.
 */
@Tag("without-jsonb")
class WithoutJsonbProviderTest {

    private static RecordingServer server;
    private static Client client;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    private static Invocation.Builder request() {
        return client.target(server.baseUrl()).path("/echo").request();
    }

    @Test
    void non_json_entity_is_sent_through_its_string_form() {
        assertEquals("POST:42", request().post(Entity.entity(42, MediaType.TEXT_PLAIN_TYPE), String.class));
    }

    @Test
    void entity_with_a_wildcard_media_type_is_sent_through_its_string_form() {
        assertEquals("POST:true", request().post(Entity.entity(Boolean.TRUE, MediaType.WILDCARD_TYPE), String.class));
    }

    @Test
    void json_entity_without_a_provider_fails_with_a_processing_exception() {
        Entity<Map<String, String>> entity = Entity.json(Map.of("k", "v"));
        Invocation.Builder builder = request();
        assertThrows(ProcessingException.class, () -> builder.post(entity));
    }

    @Test
    void reading_json_without_a_provider_fails_with_a_processing_exception() throws Exception {
        try (RecordingServer json = RecordingServer.fixed(200, Map.of("Content-Type", "application/json"), "{\"k\":\"v\"}")) {
            Response response = client.target(json.baseUrl()).path("/j").request().get();
            assertThrows(ProcessingException.class, () -> response.readEntity(Map.class));
        }
    }
}
