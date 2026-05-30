package io.vidocq.cassini.examples.vauban;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration test: @ConfigProperty injected into a Cassini JAX-RS resource
 * via Ravel + CDI Vauban.
 *
 * <p>Validates that {@code ConfigDemoResource} receives its properties from
 * {@code META-INF/microprofile-config.properties} via {@code ravel-cdi-vauban}.</p>
 */
@DisplayName("Cassini + Ravel @ConfigProperty — end-to-end integration")
class ConfigDemoResourceTest {

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
    @DisplayName("GET /config returns the greeting configured in microprofile-config.properties")
    void getConfigInfo_returnsConfiguredGreeting() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.apiUrl() + "/config")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        // The greeting comes from microprofile-config.properties
        assertTrue(resp.body().contains("Ravel") || resp.body().contains("Hello"),
                "Expected greeting from config, got: " + resp.body());
        // The version is also injected
        assertTrue(resp.body().contains("v"), "Expected version in response: " + resp.body());
    }

    @Test
    @DisplayName("GET /config/details returns the JSON configuration details")
    void getConfigDetails_returnsJsonWithConfiguredValues() throws Exception {
        var resp = http.send(
                HttpRequest.newBuilder(URI.create(server.apiUrl() + "/config/details")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        var body = resp.body();
        assertTrue(body.contains("greeting"), "Expected 'greeting' field in JSON: " + body);
        assertTrue(body.contains("version"), "Expected 'version' field in JSON: " + body);
        assertTrue(body.contains("environment"), "Expected 'environment' field in JSON: " + body);
    }
}

