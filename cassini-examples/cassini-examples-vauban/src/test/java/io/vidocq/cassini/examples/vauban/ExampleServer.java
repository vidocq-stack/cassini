package io.vidocq.cassini.examples.vauban;

import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.net.ServerSocket;

/**
 * Test server — starts Vauban CDI + Chappe composite handler (static content
 * on {@code /} + Cassini on {@code /api/*}) on a random port.
 */
public class ExampleServer implements AutoCloseable {

    private final VaubanContainer container;
    private final Server server;
    private final int port;

    public ExampleServer() throws Exception {
        try (var ss = new ServerSocket(0)) {
            this.port = ss.getLocalPort();
        }

        this.container = VaubanContainer.builder()
                .scanClasspath()
                .build();

        this.server = Server.builder()
                .host("127.0.0.1").port(this.port)
                .handler(VaubanApp.composeHandler())
                .build();
        this.server.start();
    }

    public int port()                  { return port; }
    public String baseUrl()            { return "http://127.0.0.1:" + port; }
    public String apiUrl()             { return baseUrl() + VaubanApp.API_PREFIX; }
    public VaubanContainer container() { return container; }

    @Override
    public void close() {
        try { server.stop(); }
        finally { container.close(); }
    }
}
