package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.ws.rs.core.Application;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée Cassini + Chappe + Vauban CDI.
 *
 * <p>Séquence de démarrage :</p>
 * <ol>
 *   <li>Démarrer le container CDI Vauban avec les beans de l'application.</li>
 *   <li>Bootstrap direct Cassini/Chappe avec un resolver délégant au BeanManager Vauban.</li>
 * </ol>
 *
 * <p>Note : le bootstrap direct (sans SeBootstrap) est nécessaire pour que
 * l'injection CDI {@code @Inject} soit opérationnelle dans les ressources.</p>
 */
public class Main {

    public static void main(String[] args) throws Exception {
        // 1. Démarrage du container CDI Vauban
        var container = VaubanContainer.builder()
                .addBeanClass(TodoService.class)
                .addBeanClass(GreetingResource.class)
                .addBeanClass(TodoResource.class)
                .build();

        System.out.println("Vauban CDI container started.");

        // 2. Bootstrap Cassini avec le resolver CDI Vauban
        var methods = ResourceScanner.discover(GreetingResource.class, TodoResource.class);
        var router = new UriRouter(methods);
        var bodies = new MessageBodyRegistry();
        var filters = new FilterRegistry();
        var mappers = new ExceptionMapperRegistry();
        var invoker = new Invoker(
                clazz -> container.select(clazz),
                bodies, mappers);
        invoker.setFilters(filters);
        var adapter = new ChappeHttpAdapter(router, invoker);

        int port = 8080;
        var server = Server.builder()
                .host("0.0.0.0")
                .port(port)
                .handler(adapter)
                .build();
        server.start();

        System.out.println("Cassini Vauban example started on port " + port);
        System.out.println("  GET  http://localhost:8080/greetings");
        System.out.println("  GET  http://localhost:8080/greetings/{name}");
        System.out.println("  GET  http://localhost:8080/todos");
        System.out.println("  POST http://localhost:8080/todos");
        System.out.println("Press CTRL-C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            container.close();
        }));

        new CountDownLatch(1).await();
    }

    /**
     * Application JAX-RS déclarant les ressources de l'exemple (pour référence).
     */
    public static final class ExamplesApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(GreetingResource.class, TodoResource.class);
        }
    }
}
