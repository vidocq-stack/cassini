package io.vidocq.cassini.examples.jdkhttp;

import io.vidocq.cassini.examples.jdkhttp.resource.TodoResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TodoResourceTest {

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

    @BeforeEach
    void resetStore() {
        TodoResource.reset();
    }

    @Test
    @Order(1)
    void listEmpty() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos"))
                        .header("Accept", "application/json")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertEquals("[]", resp.body().strip());
    }

    @Test
    @Order(2)
    void createTodo() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"id\":0,\"title\":\"Buy milk\",\"done\":false}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(201, resp.statusCode());
        assertTrue(resp.body().contains("\"title\":\"Buy milk\""),
                "Response should contain title. Got: " + resp.body());
        assertTrue(resp.body().contains("\"id\":1"),
                "Response should assign id=1. Got: " + resp.body());
    }

    @Test
    @Order(3)
    void getTodo() throws Exception {
        // Create first
        http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"id\":0,\"title\":\"Buy milk\",\"done\":false}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos/1"))
                        .header("Accept", "application/json")
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"title\":\"Buy milk\""));
    }

    @Test
    @Order(4)
    void getTodoNotFound() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos/999"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }

    @Test
    @Order(5)
    void updateTodo() throws Exception {
        // Create
        http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"id\":0,\"title\":\"Buy milk\",\"done\":false}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Update
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos/1"))
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(
                                "{\"id\":1,\"title\":\"Buy oat milk\",\"done\":true}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"title\":\"Buy oat milk\""));
        assertTrue(resp.body().contains("\"done\":true"));
    }

    @Test
    @Order(6)
    void deleteTodo() throws Exception {
        // Create
        http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"id\":0,\"title\":\"Buy milk\",\"done\":false}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        // Delete
        var del = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos/1"))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(204, del.statusCode());

        // Verify it no longer exists
        var get = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos/1"))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, get.statusCode());
    }

    @Test
    @Order(7)
    void deleteNotFound() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.baseUrl() + "/todos/999"))
                        .DELETE().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(404, resp.statusCode());
    }
}
