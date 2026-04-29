package io.vidocq.cassini.examples.vauban;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Vérifie que le handler composite sert l'UI statique sur {@code /}
 * en parallèle de l'API REST sur {@code /api/*}.
 */
class StaticUiTest {

    private static ExampleServer server;
    private static HttpClient http;

    @BeforeAll
    static void start() throws Exception {
        server = new ExampleServer();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void close() {
        server.close();
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rootServesIndexHtml() throws Exception {
        var resp = get("/");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
                "Should be text/html, got: " + resp.headers().firstValue("Content-Type"));
        assertTrue(resp.body().contains("<title>Cassini"), "Should contain title");
    }

    @Test
    void servesStyleCss() throws Exception {
        var resp = get("/style.css");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").startsWith("text/css"));
        assertTrue(resp.body().contains(":root"), "CSS should contain :root");
    }

    @Test
    void servesAppJs() throws Exception {
        var resp = get("/app.js");
        assertEquals(200, resp.statusCode());
        assertTrue(resp.headers().firstValue("Content-Type").orElse("").contains("javascript"));
        assertTrue(resp.body().contains("loadTodos"), "JS should contain loadTodos function");
    }

    @Test
    void unknownStaticReturns404() throws Exception {
        var resp = get("/does-not-exist.html");
        assertEquals(404, resp.statusCode());
    }

    @Test
    void apiAndStaticCoexist() throws Exception {
        // Le statique sur /
        var html = get("/");
        assertEquals(200, html.statusCode());
        // L'API sur /api/todos
        var api = get("/api/todos");
        assertEquals(200, api.statusCode());
        assertTrue(api.headers().firstValue("Content-Type").orElse("").contains("json"));
    }
}
