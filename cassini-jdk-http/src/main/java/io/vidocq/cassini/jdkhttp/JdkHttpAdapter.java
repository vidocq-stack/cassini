package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

/**
 * HTTP adapter based on {@link HttpServer} (pure JDK).
 *
 * <p>Serves as a standalone Mode A transport for Cassini, with no external
 * dependency. Used for Cassini-pure unit tests and as a reference transport
 * when users do not want to depend on Chappe.
 *
 * <p>Each request is executed on a virtual thread via
 * {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()} —
 * configure the {@link HttpServer} executor accordingly (or use
 * {@link #serve(int)} which does it automatically).
 */
public final class JdkHttpAdapter {

    private final CassiniHttpAdapter engine;
    private final String contextPath;

    public JdkHttpAdapter(CassiniHttpAdapter engine) {
        this(engine, "");
    }

    public JdkHttpAdapter(CassiniHttpAdapter engine, String contextPath) {
        this.engine = engine;
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    /** Creates a JDK {@link HttpHandler} that dispatches to Cassini. */
    public HttpHandler asHandler() {
        return jdkExchange -> {
            JdkHttpExchange exchange = new JdkHttpExchange(jdkExchange, contextPath);
            try {
                engine.dispatch(exchange).toCompletableFuture().get();
                writeResponse(jdkExchange, exchange);
            } catch (Exception e) {
                byte[] body = (e.getMessage() == null ? "Internal Server Error" : e.getMessage())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                jdkExchange.getResponseHeaders().add("Content-Type", "text/plain;charset=utf-8");
                jdkExchange.sendResponseHeaders(500, body.length);
                try (var os = jdkExchange.getResponseBody()) { os.write(body); }
            }
        };
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange jdkEx,
                                      JdkHttpExchange exchange) throws IOException {
        var headers = exchange.collectedHeaders();
        for (var e : headers.entrySet()) {
            jdkEx.getResponseHeaders().put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        byte[] body = exchange.collectedBody();
        jdkEx.sendResponseHeaders(exchange.collectedStatus(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (var os = jdkEx.getResponseBody()) { os.write(body); }
        } else {
            jdkEx.close();
        }
    }

    /** Starts a JDK {@link HttpServer} on {@code port} and binds the adapter to {@code contextPath}. */
    public HttpServer serve(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(contextPath.isEmpty() ? "/" : contextPath, asHandler());
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    /**
     * Starts a server with a JAX-RS {@link Application} bootstrapped via
     * {@link CassiniStack}.
     */
    public static HttpServer serve(int port, jakarta.ws.rs.core.Application app)
            throws IOException {
        var stack = CassiniStack.builder().application(app).build();
        return new JdkHttpAdapter(stack.adapter()).serve(port);
    }
}
