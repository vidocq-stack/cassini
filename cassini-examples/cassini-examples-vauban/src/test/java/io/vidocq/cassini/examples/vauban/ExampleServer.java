package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.net.ServerSocket;

/**
 * Serveur de test — démarre Vauban CDI + handler composite Chappe (statique
 * sur {@code /} + Cassini sur {@code /api/*}) sur un port aléatoire.
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
                .addBeanClass(TodoService.class)
                .addBeanClass(GreetingResource.class)
                .addBeanClass(TodoResource.class)
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
