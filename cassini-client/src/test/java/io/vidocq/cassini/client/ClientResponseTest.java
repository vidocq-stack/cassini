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
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The client-side {@link Response}: status, headers and entity reading. */
class ClientResponseTest {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String TEXT = "hello";
    private static final Annotation[] NO_ANNOTATIONS = new Annotation[0];

    private static Client client;

    @BeforeAll
    static void start() {
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void stop() {
        client.close();
    }

    private static Response get(int status, Map<String, String> headers, String body) throws Exception {
        try (RecordingServer server = RecordingServer.fixed(status, headers, body)) {
            return client.target(server.baseUrl()).path("/r").request().get();
        }
    }

    private static Response text() throws Exception {
        return get(200, Map.of(CONTENT_TYPE, "text/plain"), TEXT);
    }

    @Test
    void status_info_of_a_standard_code() throws Exception {
        Response response = get(201, Map.of(), TEXT);
        assertEquals(201, response.getStatus());
        assertEquals(Response.Status.CREATED, response.getStatusInfo());
    }

    @Test
    void status_info_of_a_non_standard_code_keeps_its_family() throws Exception {
        Response.StatusType info = get(299, Map.of(), TEXT).getStatusInfo();
        assertEquals(299, info.getStatusCode());
        assertEquals(Response.Status.Family.SUCCESSFUL, info.getFamily());
        assertNotNull(info.getReasonPhrase());
    }

    @Test
    void entity_is_readable_as_string_bytes_and_stream() throws Exception {
        Response response = text();
        assertTrue(response.hasEntity());
        assertTrue(response.bufferEntity());
        assertEquals(TEXT.length(), response.getLength());
        assertEquals(TEXT, response.readEntity(String.class));
        assertArrayEquals(TEXT.getBytes(StandardCharsets.UTF_8), response.readEntity(byte[].class));
        try (InputStream in = response.readEntity(InputStream.class)) {
            assertEquals(TEXT, new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertNull(response.readEntity(Void.class));
        assertNull(response.readEntity(void.class));
    }

    @Test
    void entity_reading_overloads_agree() throws Exception {
        Response response = text();
        GenericType<String> type = new GenericType<>(String.class);
        assertEquals(TEXT, response.readEntity(type));
        assertEquals(TEXT, response.readEntity(String.class, NO_ANNOTATIONS));
        assertEquals(TEXT, response.readEntity(type, NO_ANNOTATIONS));
        assertNotNull(response.getEntity());
    }

    @Test
    void empty_body_has_no_entity() throws Exception {
        Response response = get(204, Map.of(), "");
        assertFalse(response.hasEntity());
        assertEquals("", response.readEntity(String.class));
    }

    @Test
    void response_headers_are_exposed() throws Exception {
        Response response = get(200, Map.of(CONTENT_TYPE, "text/plain;charset=UTF-8", "Location", "http://h/x",
                "Content-Language", "fr-FR", "X-Multi", "a"), TEXT);
        assertEquals(MediaType.TEXT_PLAIN_TYPE.withCharset("UTF-8"), response.getMediaType());
        assertEquals(URI.create("http://h/x"), response.getLocation());
        assertEquals(Locale.FRANCE, response.getLanguage());
        assertEquals("a", response.getHeaderString("X-Multi"));
        assertNull(response.getHeaderString("X-Absent"));
        assertEquals(response.getStringHeaders().keySet(), response.getMetadata().keySet());
    }

    @Test
    void headers_absent_from_the_response_are_null() throws Exception {
        Response response = get(200, Map.of(), TEXT);
        assertNull(response.getMediaType());
        assertNull(response.getLocation());
        assertNull(response.getLanguage());
    }

    @Test
    void string_entity_is_decoded_with_the_charset_of_the_content_type() throws Exception {
        try (RecordingServer server = new RecordingServer((exchange, request) -> {
            byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
            exchange.getResponseHeaders().add(CONTENT_TYPE, "text/plain;charset=ISO-8859-1");
            exchange.sendResponseHeaders(200, latin1.length);
            try (var os = exchange.getResponseBody()) { os.write(latin1); }
        })) {
            assertEquals("café", client.target(server.baseUrl()).path("/l").request().get(String.class));
        }
    }

    @Test
    void unknown_charset_falls_back_to_utf8() throws Exception {
        assertEquals("é", get(200, Map.of(CONTENT_TYPE, "text/plain;charset=x-unknown"), "é").readEntity(String.class));
    }

    @Test
    void unreadable_entity_type_fails_with_a_processing_exception() throws Exception {
        Response response = text();
        assertThrows(ProcessingException.class, () -> response.readEntity(Locale.class));
    }

    @Test
    void closed_response_cannot_be_read() throws Exception {
        Response response = text();
        response.close();
        assertThrows(IllegalStateException.class, () -> response.readEntity(String.class));
        assertThrows(IllegalStateException.class, response::hasEntity);
        assertThrows(IllegalStateException.class, response::bufferEntity);
    }

    @Test
    void response_without_metadata_has_empty_collections() throws Exception {
        Response response = text();
        assertTrue(response.getAllowedMethods().isEmpty());
        assertTrue(response.getCookies().isEmpty());
        assertTrue(response.getLinks().isEmpty());
        assertFalse(response.hasLink("next"));
        assertNull(response.getLink("next"));
        assertNull(response.getLinkBuilder("next"));
        assertNull(response.getEntityTag());
        assertNull(response.getDate());
        assertNull(response.getLastModified());
    }
}
