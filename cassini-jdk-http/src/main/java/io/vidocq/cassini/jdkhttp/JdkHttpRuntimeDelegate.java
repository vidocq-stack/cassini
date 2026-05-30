package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpServer;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link jakarta.ws.rs.ext.RuntimeDelegate} for the JDK HttpServer transport.
 *
 * <p>Extends {@link CassiniRuntimeDelegate} (UriBuilder /
 * ResponseBuilder / HeaderDelegate boilerplate) and implements {@code bootstrap()}
 * to start a JDK {@link HttpServer}.</p>
 *
 * <p>Selected via ServiceLoader: {@code provides RuntimeDelegate with ...}
 * in {@code module-info.java}. <b>Possible conflict</b> if {@code cassini-chappe}
 * is also on the classpath — use
 * {@code -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.jdkhttp.JdkHttpRuntimeDelegate}
 * to force the choice.</p>
 */
public final class JdkHttpRuntimeDelegate extends CassiniRuntimeDelegate {

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application,
                                                           SeBootstrap.Configuration config) {
        return CompletableFuture.supplyAsync(() -> new JdkSeBootstrapInstance(application, config));
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz,
                                                            SeBootstrap.Configuration config) {
        try {
            return bootstrap(clazz.getDeclaredConstructor().newInstance(), config);
        } catch (ReflectiveOperationException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static final class JdkSeBootstrapInstance implements SeBootstrap.Instance {
        private final SeBootstrap.Configuration config;
        private final HttpServer server;

        JdkSeBootstrapInstance(Application application, SeBootstrap.Configuration requested) {
            Object p = requested.property(SeBootstrap.Configuration.PORT);
            int port = p instanceof Number n ? n.intValue() : -1;
            if (port <= 0) {
                try (var ss = new java.net.ServerSocket(0,
                        50, java.net.InetAddress.getByName("127.0.0.1"))) {
                    port = ss.getLocalPort();
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
            try {
                var stack = CassiniStack.builder().application(application).build();
                this.server = new JdkHttpAdapter(stack.adapter()).serve(port);
            } catch (java.io.IOException e) {
                throw new RuntimeException(e);
            }
            int actualPort = server.getAddress().getPort();
            java.util.Map<String, Object> effective = new java.util.HashMap<>();
            for (String k : new String[]{SeBootstrap.Configuration.PROTOCOL,
                    SeBootstrap.Configuration.HOST, SeBootstrap.Configuration.PORT,
                    SeBootstrap.Configuration.ROOT_PATH}) {
                Object v = requested.property(k);
                if (v != null) effective.put(k, v);
            }
            effective.put(SeBootstrap.Configuration.PORT, actualPort);
            effective.put(SeBootstrap.Configuration.HOST, "localhost");
            this.config = new SeBootstrap.Configuration() {
                final java.util.Map<String, Object> props = java.util.Map.copyOf(effective);
                @Override public Object property(String name) { return props.get(name); }
            };
        }

        @Override public SeBootstrap.Configuration configuration() { return config; }

        @Override public CompletionStage<StopResult> stop() {
            return CompletableFuture.supplyAsync(() -> {
                server.stop(0);
                return new StopResult() {
                    @Override public <T> T unwrap(Class<T> nativeClass) {
                        throw new IllegalArgumentException();
                    }
                };
            });
        }

        @Override public <T> T unwrap(Class<T> nativeClass) {
            if (nativeClass.isInstance(server)) return nativeClass.cast(server);
            throw new IllegalArgumentException("Cannot unwrap to " + nativeClass);
        }
    }
}
