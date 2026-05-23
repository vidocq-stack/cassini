/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cassini.client.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Serveur HTTP éphémère basé sur le JDK ({@link com.sun.net.httpserver.HttpServer}) pour
 * les tests d'intégration du Client JAX-RS sans démarrer Chappe. Port aléatoire (0).
 */
public final class FakeHttpServer implements AutoCloseable {

    private final HttpServer server;

    public FakeHttpServer(HttpHandler handler) throws IOException {
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    public int port() { return server.getAddress().getPort(); }

    public String baseUrl() { return "http://127.0.0.1:" + port(); }

    @Override public void close() { server.stop(0); }

    public static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var os = exchange.getResponseBody()) { os.write(bytes); }
    }
}
