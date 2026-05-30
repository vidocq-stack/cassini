package io.vidocq.cassini.examples.jdkhttp;

import io.vidocq.cassini.examples.jdkhttp.resource.GreetingResource;
import io.vidocq.cassini.examples.jdkhttp.resource.TodoResource;
import io.vidocq.cassini.jdkhttp.JdkHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.core.Application;

import java.net.ServerSocket;
import java.util.Set;

/**
 * Test server — starts Cassini + JDK HttpServer on a random port.
 *
 * <p>Manual bootstrap via {@link CassiniStack#builder()} (no SeBootstrap for the
 * JDK transport — see {@link Main} for details).</p>
 */
public class ExampleServer implements AutoCloseable {

    private final HttpServer server;
    private final int port;

    public ExampleServer() throws Exception {
        try (var ss = new ServerSocket(0)) {
            this.port = ss.getLocalPort();
        }

        var stack = CassiniStack.builder()
                .application(new Application() {
                    @Override public Set<Class<?>> getClasses() {
                        return Set.of(GreetingResource.class, TodoResource.class);
                    }
                })
                .build();

        this.server = new JdkHttpAdapter(stack.adapter()).serve(this.port);
    }

    public int port()       { return port; }
    public String baseUrl() { return "http://127.0.0.1:" + port; }

    @Override
    public void close() {
        server.stop(0);
    }
}
