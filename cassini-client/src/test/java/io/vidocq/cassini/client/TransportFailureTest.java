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
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.client.Invocation;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failures of the transport and of the filters surface as {@link ProcessingException}. */
class TransportFailureTest {

    @Test
    void refused_connection_fails_with_a_processing_exception() throws Exception {
        String deadUrl;
        try (RecordingServer dead = RecordingServer.echo()) {
            deadUrl = dead.baseUrl();
        }
        try (Client client = ClientBuilder.newClient()) {
            Invocation.Builder builder = client.target(deadUrl).request();
            ProcessingException e = assertThrows(ProcessingException.class, builder::get);
            assertInstanceOf(IOException.class, e.getCause());
        }
    }

    @Test
    void interrupted_caller_fails_with_a_processing_exception_and_stays_interrupted() throws Exception {
        try (RecordingServer server = RecordingServer.echo(); Client client = ClientBuilder.newClient()) {
            Invocation.Builder builder = client.target(server.baseUrl()).request();
            Thread.currentThread().interrupt();
            try {
                ProcessingException e = assertThrows(ProcessingException.class, builder::get);
                assertInstanceOf(InterruptedException.class, e.getCause());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void failing_request_filter_fails_with_a_processing_exception() throws Exception {
        try (RecordingServer server = RecordingServer.echo(); Client client = ClientBuilder.newClient()) {
            client.register((ClientRequestFilter) ctx -> { throw new IOException("request filter"); });
            Invocation.Builder builder = client.target(server.baseUrl()).request();
            ProcessingException e = assertThrows(ProcessingException.class, builder::get);
            assertInstanceOf(IOException.class, e.getCause());
        }
    }

    @Test
    void failing_response_filter_fails_with_a_processing_exception() throws Exception {
        try (RecordingServer server = RecordingServer.echo(); Client client = ClientBuilder.newClient()) {
            client.register((ClientResponseFilter) (req, resp) -> { throw new IOException("response filter"); });
            Invocation.Builder builder = client.target(server.baseUrl()).request();
            ProcessingException e = assertThrows(ProcessingException.class, builder::get);
            assertInstanceOf(IOException.class, e.getCause());
        }
    }
}
