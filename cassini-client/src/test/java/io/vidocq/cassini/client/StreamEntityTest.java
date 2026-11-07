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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.StreamingOutput;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * CASSINI-024 item 9: the entity types a JAX-RS runtime must write whatever the media type
 * (Jakarta REST 4.0 §4.2.4, standard entity providers: {@code InputStream}, {@code Reader},
 * {@code File}, {@code StreamingOutput}) reach the server as their content, never through
 * {@code toString()} or a JSON-B serialisation of the stream object.
 */
class StreamEntityTest {

    private static final String PAYLOAD = "{\"name\":\"café\",\"count\":3}";

    private static RecordingServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    private static String post(Object entity, String mediaType) {
        try (Client client = ClientBuilder.newClient()) {
            assertEquals(200, client.target(server.baseUrl()).request()
                    .post(Entity.entity(entity, mediaType)).getStatus());
            return server.last().body();
        }
    }

    private static ByteArrayInputStream stream() {
        return new ByteArrayInputStream(PAYLOAD.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void anInputStreamWithAJsonMediaTypeIsSentAsItsBytes() {
        assertEquals(PAYLOAD, post(stream(), "application/json"));
    }

    @Test
    void anInputStreamWithATextMediaTypeIsSentAsItsBytes() {
        assertEquals(PAYLOAD, post(stream(), "text/plain"));
    }

    @Test
    void anInputStreamWithAnOctetStreamMediaTypeIsSentAsItsBytes() {
        assertEquals(PAYLOAD, post(stream(), "application/octet-stream"));
    }

    @Test
    void aReaderIsSentAsUtf8() {
        assertEquals(PAYLOAD, post(new StringReader(PAYLOAD), "application/json"));
    }

    @Test
    void aFileIsSentAsItsContent(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("entity.json");
        Files.writeString(file, PAYLOAD, StandardCharsets.UTF_8);
        assertEquals(PAYLOAD, post(file.toFile(), "application/json"));
    }

    @Test
    void aStreamingOutputIsSentAsWhatItWrites() {
        StreamingOutput output = out -> out.write(PAYLOAD.getBytes(StandardCharsets.UTF_8));
        assertEquals(PAYLOAD, post(output, "application/json"));
    }
}
