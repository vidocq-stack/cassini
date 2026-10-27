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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotAcceptableException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.NotSupportedException;
import jakarta.ws.rs.RedirectionException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Typed invocations on a response that is not successful throw a {@link WebApplicationException}
 * ({@link jakarta.ws.rs.client.SyncInvoker}, {@link jakarta.ws.rs.client.AsyncInvoker} contracts);
 * untyped ones and {@code Response.class} return the response.
 */
class ErrorStatusTest {

    private static final String STATUS_PARAM = "status";

    private static RecordingServer server;
    private static Client client;

    @BeforeAll
    static void start() throws Exception {
        server = new RecordingServer((exchange, request) -> {
            int status = Integer.parseInt(request.uri().substring(request.uri().indexOf('=') + 1));
            RecordingServer.send(exchange, status, Map.of("Content-Type", "text/plain"), "status " + status);
        });
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    private static Invocation.Builder request(int status) {
        return client.target(server.baseUrl()).path("/s").queryParam(STATUS_PARAM, status).request();
    }

    static Stream<Arguments> errors() {
        return Stream.of(
                Arguments.of(302, RedirectionException.class),
                Arguments.of(400, BadRequestException.class),
                Arguments.of(401, NotAuthorizedException.class),
                Arguments.of(403, ForbiddenException.class),
                Arguments.of(404, NotFoundException.class),
                Arguments.of(405, ClientErrorException.class),
                Arguments.of(406, NotAcceptableException.class),
                Arguments.of(415, NotSupportedException.class),
                Arguments.of(418, ClientErrorException.class),
                Arguments.of(500, InternalServerErrorException.class),
                Arguments.of(503, ServiceUnavailableException.class),
                Arguments.of(502, ServerErrorException.class),
                Arguments.of(600, WebApplicationException.class));
    }

    @ParameterizedTest
    @MethodSource("errors")
    void typed_sync_invocation_throws_the_matching_exception(int status, Class<? extends WebApplicationException> type) {
        Invocation.Builder builder = request(status);
        WebApplicationException e = assertThrows(WebApplicationException.class, () -> builder.get(String.class));
        assertInstanceOf(type, e);
        assertEquals(status, e.getResponse().getStatus());
    }

    @Test
    void generic_typed_sync_invocation_throws_too() {
        Invocation.Builder builder = request(404);
        GenericType<String> type = new GenericType<>(String.class);
        assertThrows(NotFoundException.class, () -> builder.get(type));
    }

    @Test
    void typed_invocation_from_an_invocation_throws_too() {
        Invocation invocation = request(404).buildGet();
        assertThrows(NotFoundException.class, () -> invocation.invoke(String.class));
    }

    @Test
    void untyped_invocation_returns_the_error_response() {
        assertEquals(404, request(404).get().getStatus());
    }

    @Test
    void response_type_returns_the_response_whatever_its_status() {
        assertEquals(500, request(500).get(Response.class).getStatus());
        assertEquals(200, request(200).get(new GenericType<Response>(Response.class)).getStatus());
    }

    @Test
    void typed_async_invocation_fails_with_the_matching_exception() {
        Future<String> future = request(404).async().get(String.class);
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(NotFoundException.class, e.getCause());
    }

    @Test
    void typed_async_invocation_fails_on_a_redirection_too() {
        Future<String> future = request(302).async().get(new GenericType<>(String.class));
        ExecutionException e = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(RedirectionException.class, e.getCause());
    }

    @Test
    void response_typed_async_invocation_returns_the_response() throws Exception {
        Response response = request(503).async().get(Response.class).get(5, TimeUnit.SECONDS);
        assertEquals(503, response.getStatus());
    }

    @Test
    void successful_typed_invocation_reads_the_entity() {
        assertSame(String.class, request(200).get(String.class).getClass());
    }
}
