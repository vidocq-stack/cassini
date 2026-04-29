package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée Cassini + Chappe + Vauban CDI.
 *
 * <p>Séquence :</p>
 * <ol>
 *   <li>Démarrer le container CDI Vauban — s'enregistre automatiquement
 *       comme {@code CDI.current()} via {@code VaubanCDIProvider} (ServiceLoader).</li>
 *   <li>{@code SeBootstrap.start} — Cassini découvre via ServiceLoader la SPI
 *       {@code BeanProvider} fournie par {@code cassini-cdi-vauban} et scanne
 *       automatiquement les classes {@code @Path}/{@code @Provider} connues
 *       du container, sans qu'il soit nécessaire de les déclarer dans
 *       {@code Application}.</li>
 * </ol>
 */
public class Main {

    static void main(String[] args) throws Exception {
        var container = VaubanContainer.builder()
                .addBeanClass(TodoService.class)
                .addBeanClass(GreetingResource.class)
                .addBeanClass(TodoResource.class)
                .build();

        var instance = SeBootstrap.start(
                new Application() {},
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
