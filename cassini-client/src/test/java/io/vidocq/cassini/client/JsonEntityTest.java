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
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.json.bind.JsonbConfig;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON entities sent and read through JSON-B, honouring a registered {@code ContextResolver<Jsonb>}. */
class JsonEntityTest {

    private static final String NULL_NAME = "\"name\":null";
    private static final Jsonb WITH_NULLS = JsonbBuilder.create(new JsonbConfig().withNullValues(true));

    private static RecordingServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
    }

    @AfterAll
    static void stop() throws Exception {
        server.close();
        WITH_NULLS.close();
    }

    /** Posts {@code item} as {@code mediaType} with the given providers; returns the body the server got. */
    private static String post(Item item, String mediaType, Object... providers) {
        try (Client client = ClientBuilder.newClient()) {
            for (Object provider : providers) client.register(provider);
            assertEquals(200, client.target(server.baseUrl()).request()
                    .post(Entity.entity(item, mediaType)).getStatus());
            return server.last().body();
        }
    }

    private static Item unnamed() {
        Item item = new Item();
        item.count = 3;
        return item;
    }

    @Test
    void pojo_is_sent_as_json() {
        Item item = unnamed();
        item.name = "n";
        String body = post(item, MediaType.APPLICATION_JSON);
        assertTrue(body.contains("\"name\":\"n\""), body);
        assertTrue(body.contains("\"count\":3"), body);
    }

    @Test
    void structured_json_suffix_is_json() {
        String body = post(unnamed(), "application/vnd.test+json");
        assertTrue(body.contains("\"count\":3"), body);
    }

    @Test
    void wildcard_subtype_is_not_json() {
        assertEquals(Item.TO_STRING, post(unnamed(), "application/*"));
    }

    @Test
    void context_resolver_matching_the_media_type_is_used() {
        assertTrue(post(unnamed(), MediaType.APPLICATION_JSON, new JsonOnlyResolver()).contains(NULL_NAME));
        assertTrue(post(unnamed(), MediaType.APPLICATION_JSON, new AnyTypeResolver()).contains(NULL_NAME));
        assertTrue(post(unnamed(), MediaType.APPLICATION_JSON, new InheritedResolver()).contains(NULL_NAME));
    }

    @Test
    void other_resolvers_fall_back_to_the_default_jsonb() {
        assertFalse(post(unnamed(), MediaType.APPLICATION_JSON, new XmlOnlyResolver()).contains(NULL_NAME));
        assertFalse(post(unnamed(), MediaType.APPLICATION_JSON, new NullResolver()).contains(NULL_NAME));
        assertFalse(post(unnamed(), MediaType.APPLICATION_JSON, new StringResolver()).contains(NULL_NAME));
    }

    @Test
    void json_body_is_read_into_a_pojo() throws Exception {
        try (RecordingServer json = RecordingServer.fixed(200, Map.of("Content-Type", "application/json"),
                "{\"name\":\"read\",\"count\":7}");
             Client client = ClientBuilder.newClient()) {
            client.register(new JsonOnlyResolver());
            Item item = client.target(json.baseUrl()).request().get(Item.class);
            assertEquals("read", item.name);
            assertEquals(7, item.count);
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    public static class Item {
        static final String TO_STRING = "an item";
        public String name;
        public int count;

        @Override public String toString() { return TO_STRING; }
    }

    @Produces(MediaType.APPLICATION_JSON)
    public static class JsonOnlyResolver implements ContextResolver<Jsonb> {
        @Override public Jsonb getContext(Class<?> type) { return WITH_NULLS; }
    }

    public static class AnyTypeResolver implements ContextResolver<Jsonb> {
        @Override public Jsonb getContext(Class<?> type) { return WITH_NULLS; }
    }

    public static class InheritedResolver extends AnyTypeResolver {
    }

    @Produces(MediaType.APPLICATION_XML)
    public static class XmlOnlyResolver implements ContextResolver<Jsonb> {
        @Override public Jsonb getContext(Class<?> type) { return WITH_NULLS; }
    }

    public static class NullResolver implements ContextResolver<Jsonb> {
        @Override public Jsonb getContext(Class<?> type) { return null; }
    }

    public static class StringResolver implements ContextResolver<String> {
        @Override public String getContext(Class<?> type) { return "unused"; }
    }
}
