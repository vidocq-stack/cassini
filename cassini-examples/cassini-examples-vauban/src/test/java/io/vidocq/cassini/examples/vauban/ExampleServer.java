package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.net.ServerSocket;
import java.util.Set;

/**
 * Serveur de test — démarre Vauban CDI + Cassini/Chappe sur un port aléatoire.
 *
 * <p>Utilise {@code SeBootstrap} avec des singletons CDI : les instances
 * ressources sont créées par Vauban ({@code @Inject} résolu) puis passées
 * à l'Application JAX-RS. Aucun accès aux packages internes de Cassini.</p>
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

        var greeting = container.select(GreetingResource.class);
        var todos    = container.select(TodoResource.class);

        this.instance = SeBootstrap.start(
                new Application() {
                    @Override public Set<Object> getSingletons() {
                        return Set.of(greeting, todos);
                    }
                },
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
