package io.vidocq.cassini.examples.chappe;

import jakarta.ws.rs.SeBootstrap;

import java.net.ServerSocket;

/**
 * AutoCloseable test server — starts Cassini Chappe on a random port.
 *
 * <p>Usage in JUnit 5:</p>
 * <pre>{@code
 * private static ExampleServer server;
 *
 * @BeforeAll
 * static void start() throws Exception { server = new ExampleServer(); }
 *
 * @AfterAll
 * static void close() throws Exception { server.close(); }
 * }</pre>
 */
public class ExampleServer implements AutoCloseable {

    private final SeBootstrap.Instance instance;
    private final int port;

    public ExampleServer() throws Exception {
        // Obtain a free random port
        try (var ss = new ServerSocket(0)) {
            this.port = ss.getLocalPort();
        }
        this.instance = SeBootstrap.start(new Main.ExamplesApp(),
                SeBootstrap.Configuration.builder()
                        .host("127.0.0.1")
                        .port(this.port)
                        .build())
                .toCompletableFuture()
                .get();
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    @Override
    public void close() throws Exception {
        instance.stop().toCompletableFuture().get();
    }
}
