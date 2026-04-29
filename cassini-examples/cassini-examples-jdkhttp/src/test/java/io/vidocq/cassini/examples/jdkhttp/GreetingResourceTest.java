package io.vidocq.cassini.examples.jdkhttp;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GreetingResourceTest {

    private static ExampleServer server;
    private static HttpClient http;

    @BeforeAll
    static void start() throws Exception {
        server = new ExampleServer();
        http = HttpClient.newHttpClient();
    }

    @AfterAll
    static void close() throws Exception {
        server.close();
    }

    @Test
    void helloWorld() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/greetings")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("Hello, World!", resp.body());
    }

    @Test
    void helloName() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/greetings/Alice")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("Hello, Alice!", resp.body());
    }

    @Test
    void contentTypeIsTextPlain() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/greetings")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertTrue(resp.headers().firstValue("content-type")
                .map(ct -> ct.startsWith("text/plain")).orElse(false),
                "Expected text/plain content-type");
    }
}
