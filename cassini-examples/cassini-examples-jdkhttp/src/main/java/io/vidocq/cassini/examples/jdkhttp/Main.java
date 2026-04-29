package io.vidocq.cassini.examples.jdkhttp;

import io.vidocq.cassini.examples.jdkhttp.resource.GreetingResource;
import io.vidocq.cassini.examples.jdkhttp.resource.TodoResource;
import io.vidocq.cassini.jdkhttp.JdkHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.core.Application;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Point d'entrée Cassini + transport JDK HttpServer (com.sun.net.httpserver).
 *
 * <p><b>Pas de SeBootstrap ici</b> : le module {@code cassini-jdk-http}
 * n'expose pas de {@code RuntimeDelegate} via ServiceLoader, donc l'utilisateur
 * bootstrappe manuellement via {@link CassiniStack} :</p>
 *
 * <pre>{@code
 * var stack = CassiniStack.builder().application(app).build();
 * var server = new JdkHttpAdapter(stack.adapter()).serve(8080);
 * }</pre>
 *
 * <p>Pourquoi pas SeBootstrap ?
 * <ul>
 *   <li>Si {@code cassini-chappe} et {@code cassini-jdk-http} fournissaient
 *       tous deux un {@code RuntimeDelegate} via ServiceLoader, le choix
 *       serait non-déterministe (premier provider trouvé).</li>
 *   <li>{@code cassini-jdk-http} est conçu comme transport "alternatif" pour
 *       les cas où on ne veut pas dépendre de Chappe — le bootstrap explicite
 *       évite toute ambiguïté.</li>
 * </ul>
 *
 * <p>Pour utiliser SeBootstrap avec JDK HttpServer, ajoutez vous-même un
 * {@code RuntimeDelegate} dans votre application et déclarez-le via
 * {@code provides jakarta.ws.rs.ext.RuntimeDelegate with ...} dans votre
 * {@code module-info.java}.</p>
 */
public class Main {

    static void main(String[] args) throws Exception {
        var stack = CassiniStack.builder()
                .application(new ExamplesApp())
                .build();

        var server = new JdkHttpAdapter(stack.adapter()).serve(8080);

        System.out.println("Cassini JDK HttpServer démarré sur port "
                + server.getAddress().getPort());
        System.out.println("  GET  http://localhost:8080/greetings");
        System.out.println("  GET  http://localhost:8080/greetings/{name}");
        System.out.println("  GET  http://localhost:8080/todos");
        System.out.println("  POST http://localhost:8080/todos");
        System.out.println("CTRL-C pour arrêter.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        new CountDownLatch(1).await();
    }

    /** Application JAX-RS déclarant les ressources de l'exemple. */
    public static final class ExamplesApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(GreetingResource.class, TodoResource.class);
        }
    }
}
