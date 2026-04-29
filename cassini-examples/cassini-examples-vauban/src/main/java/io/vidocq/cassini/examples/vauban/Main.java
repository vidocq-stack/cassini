package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée Cassini + Chappe + Vauban CDI.
 *
 * <p>Séquence :</p>
 * <ol>
 *   <li>Démarrer le container CDI Vauban — s'enregistre automatiquement
 *       comme {@code CDI.current()} via {@code VaubanCDIProvider} (ServiceLoader).</li>
 *   <li>Obtenir les instances CDI (injection résolue) et les passer comme
 *       singletons à l'Application JAX-RS.</li>
 *   <li>SeBootstrap découvre {@code ChappeRuntimeDelegate} via ServiceLoader
 *       et démarre le serveur avec ces singletons.</li>
 * </ol>
 */
public class Main {

    static void main(String[] args) throws Exception {
        var container = VaubanContainer.builder()
                .addBeanClass(TodoService.class)
                .addBeanClass(GreetingResource.class)
                .addBeanClass(TodoResource.class)
                .build();

        // Instances CDI avec injection résolue (@Inject TodoService satisfait)
        var greeting = container.select(GreetingResource.class);
        var todos    = container.select(TodoResource.class);

        var instance = SeBootstrap.start(
                new Application() {
                    @Override public Set<Object> getSingletons() {
                        return Set.of(greeting, todos);
                    }
                },
                SeBootstrap.Configuration.builder()
                        .host("0.0.0.0").port(8080).build()
        ).toCompletableFuture().get();

        System.out.println("Cassini + Vauban CDI démarré sur port "
                + instance.configuration().port());
        System.out.println("  GET  http://localhost:8080/greetings");
        System.out.println("  POST http://localhost:8080/todos");
        System.out.println("CTRL-C pour arrêter.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            instance.stop();
            container.close();
        }));
        new CountDownLatch(1).await();
    }
}
