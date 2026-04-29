package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.net.ServerSocket;

/**
 * Serveur de test — démarre Vauban CDI + Cassini/Chappe sur un port aléatoire.
 *
 * <p>L'application JAX-RS est volontairement vide : Cassini découvre la SPI
 * {@code BeanProvider} fournie par {@code cassini-cdi-vauban} via ServiceLoader
 * et scanne automatiquement les classes {@code @Path}/{@code @Provider}
 * connues du container Vauban.</p>
 */
public class ExampleServer implements AutoCloseable {

    private final VaubanContainer container;
    private final SeBootstrap.Instance instance;
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

        this.instance = SeBootstrap.start(
                new Application() {},
                SeBootstrap.Configuration.builder()
                        .host("127.0.0.1").port(this.port).build()
        ).toCompletableFuture().get();
    }

    public int port()                  { return port; }
    public String baseUrl()            { return "http://127.0.0.1:" + port; }
    public VaubanContainer container() { return container; }

    @Override
    public void close() throws Exception {
        try { instance.stop().toCompletableFuture().get(); }
        finally { container.close(); }
    }
}
