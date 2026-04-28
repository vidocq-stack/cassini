package io.vidocq.cassini.tck;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Smoke test du {@link CassiniTestHarness} — vérifie qu'il démarre
 * Chappe + Cassini et répond à une requête GET avant de s'arrêter.
 */
class CassiniHarnessSmokeTest {

    @Path("/ping")
    public static class PingResource {
        @GET @Produces(MediaType.TEXT_PLAIN)
        public String pong() { return "pong"; }
    }

    @Test
    void harnessServesPing() throws Exception {
        try (CassiniTestHarness h = CassiniTestHarness.builder()
                .resourceClass(PingResource.class)
                .contextPath("/")
                .start()) {
            HttpResponse<String> r = HttpClient.newHttpClient().send(
                    HttpRequest.newBuilder(URI.create(h.baseUrl() + "/ping"))
                            .timeout(Duration.ofSeconds(2)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, r.statusCode());
            assertEquals("pong", r.body());
        }
    }
}
