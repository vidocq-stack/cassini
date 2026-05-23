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

import io.vidocq.cassini.client.support.FakeHttpServer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BasicGetTest {

    @Test
    void get_returns_status_200_and_body() throws Exception {
        AtomicReference<String> seenMethod = new AtomicReference<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            seenMethod.set(exchange.getRequestMethod());
            FakeHttpServer.respond(exchange, 200, "hello-cassini");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                Response r = client.target(server.baseUrl())
                        .path("/anything")
                        .request()
                        .get();
                assertEquals(200, r.getStatus());
                assertEquals("hello-cassini", r.readEntity(String.class));
            }
            assertEquals("GET", seenMethod.get());
        }
    }

    @Test
    void get_with_query_param_includes_query_string() throws Exception {
        AtomicReference<String> seenUri = new AtomicReference<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            seenUri.set(exchange.getRequestURI().toString());
            FakeHttpServer.respond(exchange, 200, "ok");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                client.target(server.baseUrl())
                        .path("/users")
                        .queryParam("page", 2)
                        .queryParam("size", 10)
                        .request()
                        .get();
            }
            assertNotNull(seenUri.get());
            assertEquals("/users?page=2&size=10", seenUri.get());
        }
    }

    @Test
    void get_with_resolve_template() throws Exception {
        AtomicReference<String> seenUri = new AtomicReference<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            seenUri.set(exchange.getRequestURI().toString());
            FakeHttpServer.respond(exchange, 200, "ok");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                client.target(server.baseUrl())
                        .path("/items/{id}")
                        .resolveTemplate("id", 42)
                        .request()
                        .get();
            }
            assertEquals("/items/42", seenUri.get());
        }
    }

    @Test
    void get_with_custom_header_propagates_to_server() throws Exception {
        AtomicReference<String> seenHeader = new AtomicReference<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            seenHeader.set(exchange.getRequestHeaders().getFirst("X-Trace-Id"));
            FakeHttpServer.respond(exchange, 200, "ok");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                client.target(server.baseUrl())
                        .path("/")
                        .request()
                        .header("X-Trace-Id", "abc-123")
                        .get();
            }
            assertEquals("abc-123", seenHeader.get());
        }
    }
}
