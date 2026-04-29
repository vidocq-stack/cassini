package io.vidocq.cassini.examples.chappe;

import io.vidocq.cassini.examples.chappe.resource.GreetingResource;
import io.vidocq.cassini.examples.chappe.resource.TodoResource;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée Cassini + Chappe (standalone, sans CDI).
 *
 * <p>Le {@link jakarta.ws.rs.ext.RuntimeDelegate} Chappe est découvert
 * automatiquement via ServiceLoader (déclaré dans {@code cassini-chappe}).</p>
 */
public class Main {

    static void main(String[] args) throws Exception {
        var instance = SeBootstrap.start(new ExamplesApp(),
                SeBootstrap.Configuration.builder()
                        .host("0.0.0.0")
                        .port(8080)
                        .build())
                .toCompletableFuture()
                .get();

        System.out.println("Cassini Chappe example started on port "
                + instance.configuration().port());
        System.out.println("  GET  http://localhost:8080/greetings");
        System.out.println("  GET  http://localhost:8080/greetings/{name}");
        System.out.println("  GET  http://localhost:8080/todos");
        System.out.println("  POST http://localhost:8080/todos");
        System.out.println("Press CTRL-C to stop.");

        new CountDownLatch(1).await();
    }

    /**
     * Application JAX-RS déclarant les ressources de l'exemple.
     */
    public static final class ExamplesApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(GreetingResource.class, TodoResource.class);
        }
    }
}
