package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.examples.vauban.resource.GreetingResource;
import io.vidocq.cassini.examples.vauban.resource.TodoResource;
import io.vidocq.cassini.examples.vauban.service.TodoService;
import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée Cassini + Chappe + Vauban CDI avec UI HTML statique.
 *
 * <p>Architecture :</p>
 * <ul>
 *   <li><b>Vauban CDI</b> : démarre le container et s'enregistre comme
 *       {@code CDI.current()} via {@code VaubanCDIProvider} (ServiceLoader).</li>
 *   <li><b>Cassini</b> : assemble la stack via {@code CassiniStack.builder()}
 *       qui auto-détecte le {@code BeanProvider} Vauban (ServiceLoader).</li>
 *   <li><b>Handler composite Chappe</b> : sert l'UI statique
 *       ({@code index.html}, {@code style.css}, {@code app.js}) sur {@code /}
 *       et délègue les routes {@code /api/*} à Cassini.</li>
 * </ul>
 */
public class Main {

    static void main(String[] args) throws Exception {
        var container = VaubanContainer.builder()
                .addBeanClass(TodoService.class)
                .addBeanClass(GreetingResource.class)
                .addBeanClass(TodoResource.class)
                .build();

        var server = Server.builder()
                .host("0.0.0.0").port(8080)
                .handler(VaubanApp.composeHandler())
                .build();
        server.start();

        System.out.println("┌──────────────────────────────────────────────┐");
        System.out.println("│ Cassini + Vauban CDI — démarré sur port 8080 │");
        System.out.println("├──────────────────────────────────────────────┤");
        System.out.println("│  UI  → http://localhost:8080/                │");
        System.out.println("│  API → http://localhost:8080/api/todos       │");
        System.out.println("│        http://localhost:8080/api/greetings   │");
        System.out.println("└──────────────────────────────────────────────┘");
        System.out.println("CTRL-C pour arrêter.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.stop();
            container.close();
        }));
        new CountDownLatch(1).await();
    }
}
