package io.vidocq.cassini.examples.jdkhttp;

import io.vidocq.cassini.examples.jdkhttp.resource.GreetingResource;
import io.vidocq.cassini.examples.jdkhttp.resource.TodoResource;
import io.vidocq.cassini.jdkhttp.JdkHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.core.Application;

import java.util.Set;
import java.util.concurrent.CountDownLatch;

/**
 * Cassini + JDK HttpServer transport (com.sun.net.httpserver) entry point.
 *
 * <p><b>No SeBootstrap here</b>: the {@code cassini-jdk-http} module does not
 * expose a {@code RuntimeDelegate} via ServiceLoader, so the user bootstraps
 * manually via {@link CassiniStack}:</p>
 *
 * <pre>{@code
 * var stack = CassiniStack.builder().application(app).build();
 * var server = new JdkHttpAdapter(stack.adapter()).serve(8080);
 * }</pre>
 *
 * <p>Why no SeBootstrap?
 * <ul>
 *   <li>If both {@code cassini-chappe} and {@code cassini-jdk-http} provided a
 *       {@code RuntimeDelegate} via ServiceLoader, the choice would be
 *       non-deterministic (first provider found).</li>
 *   <li>{@code cassini-jdk-http} is designed as an "alternative" transport for
 *       cases where one does not want to depend on Chappe — explicit bootstrap
 *       avoids any ambiguity.</li>
 * </ul>
 *
 * <p>To use SeBootstrap with the JDK HttpServer, add a {@code RuntimeDelegate}
 * of your own in your application and declare it via
 * {@code provides jakarta.ws.rs.ext.RuntimeDelegate with ...} in your
 * {@code module-info.java}.</p>
 */
public class Main {

    static void main(String[] args) throws Exception {
        var stack = CassiniStack.builder()
                .application(new ExamplesApp())
                .build();

        var server = new JdkHttpAdapter(stack.adapter()).serve(8080);

        System.out.println("Cassini JDK HttpServer started on port "
                + server.getAddress().getPort());
        System.out.println("  GET  http://localhost:8080/greetings");
        System.out.println("  GET  http://localhost:8080/greetings/{name}");
        System.out.println("  GET  http://localhost:8080/todos");
        System.out.println("  POST http://localhost:8080/todos");
        System.out.println("CTRL-C to stop.");

        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        new CountDownLatch(1).await();
    }

    /** JAX-RS Application declaring the example resources. */
    public static final class ExamplesApp extends Application {
        @Override
        public Set<Class<?>> getClasses() {
            return Set.of(GreetingResource.class, TodoResource.class);
        }
    }
}
