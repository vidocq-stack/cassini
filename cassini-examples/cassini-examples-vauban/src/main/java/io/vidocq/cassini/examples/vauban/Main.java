package io.vidocq.cassini.examples.vauban;

import io.vidocq.chappe.api.Server;
import io.vidocq.vauban.core.container.VaubanContainer;

import java.util.concurrent.CountDownLatch;

/**
 * Entry point for Cassini + Chappe + Vauban CDI with static HTML UI.
 *
 * <p>Architecture:</p>
 * <ul>
 *   <li><b>Vauban CDI</b>: starts the container and registers itself as
 *       {@code CDI.current()} via {@code VaubanCDIProvider} (ServiceLoader).</li>
 *   <li><b>Cassini</b>: assembles the stack via {@code CassiniStack.builder()}
 *       which auto-detects the Vauban {@code BeanProvider} (ServiceLoader).</li>
 *   <li><b>Chappe composite handler</b>: serves the static UI
 *       ({@code index.html}, {@code style.css}, {@code app.js}) on {@code /}
 *       and delegates the {@code /api/*} routes to Cassini.</li>
 * </ul>
 */
public class Main {

    static void main(String[] args) throws Exception {
        // scanClasspath() reads META-INF/vauban-beans.list, generated at compile time
        // by vauban-maven-plugin (goal 'generate'). Works in a JPMS named
        // module — META-INF/ resources are always accessible via
        // ClassLoader.getResources() regardless of packaging mode.
        var container = VaubanContainer.builder()
                .scanClasspath()
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
