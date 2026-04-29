package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.net.ServerSocket;

/**
 * Serveur de test AutoCloseable — démarre Vauban CDI + Cassini Chappe sur un port aléatoire.
 *
 * <p>Utilise le bootstrap direct (pas SeBootstrap) pour intégrer le resolver CDI Vauban.</p>
 *
 * <p>Usage dans JUnit 5 :</p>
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

    private final VaubanContainer container;
    private final Server server;
    private final int port;

    public ExampleServer() throws Exception {
        // Port aléatoire libre
        try (var ss = new ServerSocket(0)) {
            this.port = ss.getLocalPort();
        }

        // Démarrage du container CDI Vauban
        this.container = VaubanContainer.builder()
                .addBeanClass(TodoService.class)
                .addBeanClass(GreetingResource.class)
                .addBeanClass(TodoResource.class)
                .build();

        // Bootstrap Cassini avec le resolver CDI Vauban
        var methods = ResourceScanner.discover(GreetingResource.class, TodoResource.class);
        var router = new UriRouter(methods);
        var bodies = new MessageBodyRegistry();
        var filters = new FilterRegistry();
        var mappers = new ExceptionMapperRegistry();
        // Resolver CDI : délègue à VaubanContainer
        var invoker = new Invoker(
                clazz -> container.select(clazz),
                bodies, mappers);
        invoker.setFilters(filters);
        var adapter = new ChappeHttpAdapter(router, invoker);

        this.server = Server.builder()
                .host("127.0.0.1")
                .port(this.port)
                .handler(adapter)
                .build();
        this.server.start();
    }

    public int port() {
        return port;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    public VaubanContainer container() {
        return container;
    }

    @Override
    public void close() throws Exception {
        try {
            server.stop();
        } finally {
            container.close();
        }
    }
}
