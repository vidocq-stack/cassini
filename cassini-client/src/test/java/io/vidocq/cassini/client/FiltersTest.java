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
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseFilter;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FiltersTest {

    @Test
    void request_filter_adds_header_to_outgoing_request() throws Exception {
        AtomicReference<String> seenHeader = new AtomicReference<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            seenHeader.set(exchange.getRequestHeaders().getFirst("X-Trace"));
            FakeHttpServer.respond(exchange, 200, "ok");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                client.register((ClientRequestFilter) ctx -> ctx.getHeaders().add("X-Trace", "filter-injected"));
                Response r = client.target(server.baseUrl()).path("/").request().get();
                assertEquals(200, r.getStatus());
            }
            assertEquals("filter-injected", seenHeader.get());
        }
    }

    @Test
    void request_filter_can_mutate_uri_before_transport() throws Exception {
        AtomicReference<String> seenUri = new AtomicReference<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            seenUri.set(exchange.getRequestURI().toString());
            FakeHttpServer.respond(exchange, 200, "ok");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                client.register((ClientRequestFilter) ctx -> {
                    java.net.URI u = ctx.getUri();
                    ctx.setUri(java.net.URI.create(u.toString().replace("/original", "/rewritten")));
                });
                client.target(server.baseUrl()).path("/original").request().get();
            }
            assertEquals("/rewritten", seenUri.get());
        }
    }

    @Test
    void request_filter_abortWith_skips_transport() throws Exception {
        AtomicBoolean serverHit = new AtomicBoolean(false);
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            serverHit.set(true);
            FakeHttpServer.respond(exchange, 200, "should-not-be-reached");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                client.register((ClientRequestFilter) ctx -> ctx.abortWith(
                        Response.status(418).entity("teapot-from-filter").build()));
                Response r = client.target(server.baseUrl()).path("/").request().get();
                assertEquals(418, r.getStatus());
                assertEquals("teapot-from-filter", r.readEntity(String.class));
            }
            assertFalse(serverHit.get(), "abortWith must skip the actual HTTP call");
        }
    }

    @Test
    void response_filter_can_read_status_and_headers() throws Exception {
        AtomicReference<Integer> seenStatus = new AtomicReference<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> {
            exchange.getResponseHeaders().add("X-Custom", "from-server");
            FakeHttpServer.respond(exchange, 201, "created");
        })) {
            try (Client client = ClientBuilder.newClient()) {
                client.register((ClientResponseFilter) (req, resp) -> seenStatus.set(resp.getStatus()));
                Response r = client.target(server.baseUrl()).path("/").request().get();
                assertEquals(201, r.getStatus());
                // HttpServer JDK normalise la casse des headers — chercher case-insensitive.
                String found = null;
                for (var e : r.getStringHeaders().entrySet()) {
                    if (e.getKey().equalsIgnoreCase("X-Custom")) {
                        found = e.getValue().get(0);
                        break;
                    }
                }
                assertEquals("from-server", found);
            }
            assertEquals(Integer.valueOf(201), seenStatus.get());
        }
    }

    @Test
    void multiple_request_filters_execute_in_priority_ascending_order() throws Exception {
        List<String> order = new ArrayList<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> FakeHttpServer.respond(exchange, 200, "ok"))) {
            try (Client client = ClientBuilder.newClient()) {
                // Authentication (1000) < HEADER_DECORATOR (3000) < USER (5000)
                client.register(new HighPrioFilter(order));   // priority 1000
                client.register(new LowPrioFilter(order));    // priority 5000
                client.register(new MidPrioFilter(order));    // priority 3000
                client.target(server.baseUrl()).path("/").request().get();
            }
            assertEquals(List.of("auth", "deco", "user"), order);
        }
    }

    @Test
    void response_filter_priority_descending() throws Exception {
        List<String> order = new ArrayList<>();
        try (FakeHttpServer server = new FakeHttpServer(exchange -> FakeHttpServer.respond(exchange, 200, "ok"))) {
            try (Client client = ClientBuilder.newClient()) {
                client.register(new HighPrioRespFilter(order)); // priority 1000
                client.register(new LowPrioRespFilter(order));  // priority 5000
                client.target(server.baseUrl()).path("/").request().get();
            }
            // DESC : USER (5000) avant AUTH (1000)
            assertEquals(List.of("user", "auth"), order);
        }
    }

    @Test
    void abortWith_skips_remaining_request_filters() throws Exception {
        AtomicBoolean afterAbort = new AtomicBoolean(false);
        try (FakeHttpServer server = new FakeHttpServer(exchange -> FakeHttpServer.respond(exchange, 200, "ok"))) {
            try (Client client = ClientBuilder.newClient()) {
                client.register(new AbortingFilter()); // priority 1000
                client.register(new MarkerFilter(afterAbort)); // priority 5000
                Response r = client.target(server.baseUrl()).path("/").request().get();
                assertEquals(503, r.getStatus());
            }
            assertFalse(afterAbort.get(), "subsequent request filters must not run after abortWith");
        }
    }

    @Test
    void aborted_response_still_runs_response_filters() throws Exception {
        AtomicBoolean responseFilterRan = new AtomicBoolean(false);
        try (FakeHttpServer server = new FakeHttpServer(exchange -> FakeHttpServer.respond(exchange, 200, "live"))) {
            try (Client client = ClientBuilder.newClient()) {
                client.register((ClientRequestFilter) ctx -> ctx.abortWith(Response.status(429).entity("rate-limited").build()));
                client.register((ClientResponseFilter) (req, resp) -> responseFilterRan.set(true));
                Response r = client.target(server.baseUrl()).path("/").request().get();
                assertEquals(429, r.getStatus());
                assertEquals("rate-limited", r.readEntity(String.class));
            }
            assertTrue(responseFilterRan.get(), "response filters must run even on aborted invocations");
        }
    }

    // ---- Fixtures filtres avec @Priority -------------------------------------------------

    @Priority(Priorities.AUTHENTICATION) // 1000
    static class HighPrioFilter implements ClientRequestFilter {
        final List<String> order;
        HighPrioFilter(List<String> order) { this.order = order; }
        public void filter(jakarta.ws.rs.client.ClientRequestContext ctx) { order.add("auth"); }
    }
    @Priority(Priorities.HEADER_DECORATOR) // 3000
    static class MidPrioFilter implements ClientRequestFilter {
        final List<String> order;
        MidPrioFilter(List<String> order) { this.order = order; }
        public void filter(jakarta.ws.rs.client.ClientRequestContext ctx) { order.add("deco"); }
    }
    @Priority(Priorities.USER) // 5000
    static class LowPrioFilter implements ClientRequestFilter {
        final List<String> order;
        LowPrioFilter(List<String> order) { this.order = order; }
        public void filter(jakarta.ws.rs.client.ClientRequestContext ctx) { order.add("user"); }
    }
    @Priority(Priorities.AUTHENTICATION)
    static class HighPrioRespFilter implements ClientResponseFilter {
        final List<String> order;
        HighPrioRespFilter(List<String> order) { this.order = order; }
        public void filter(jakarta.ws.rs.client.ClientRequestContext rq, jakarta.ws.rs.client.ClientResponseContext rs) { order.add("auth"); }
    }
    @Priority(Priorities.USER)
    static class LowPrioRespFilter implements ClientResponseFilter {
        final List<String> order;
        LowPrioRespFilter(List<String> order) { this.order = order; }
        public void filter(jakarta.ws.rs.client.ClientRequestContext rq, jakarta.ws.rs.client.ClientResponseContext rs) { order.add("user"); }
    }
    @Priority(Priorities.AUTHENTICATION)
    static class AbortingFilter implements ClientRequestFilter {
        public void filter(jakarta.ws.rs.client.ClientRequestContext ctx) {
            ctx.abortWith(Response.status(503).entity("unavailable").build());
        }
    }
    @Priority(Priorities.USER)
    static class MarkerFilter implements ClientRequestFilter {
        final AtomicBoolean ran;
        MarkerFilter(AtomicBoolean ran) { this.ran = ran; }
        public void filter(jakarta.ws.rs.client.ClientRequestContext ctx) { ran.set(true); }
    }
}
