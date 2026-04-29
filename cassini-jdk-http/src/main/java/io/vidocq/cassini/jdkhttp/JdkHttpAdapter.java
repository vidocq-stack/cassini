package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

/**
 * Adapter HTTP basé sur {@link HttpServer} (JDK pur).
 *
 * <p>Sert de transport autonome pour Cassini en Mode A, sans dépendance
 * externe. Utilisé pour les unit tests Cassini-pur et comme transport de
 * référence si l'utilisateur ne souhaite pas dépendre de Chappe.
 *
 * <p>Chaque requête est exécutée sur un virtual thread via
 * {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()} —
 * configurer l'executor du {@link HttpServer} en conséquence (ou utiliser
 * {@link #serve(int)} qui le fait automatiquement).
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

    /** Crée un {@link HttpHandler} JDK qui dispatche vers Cassini. */
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

    /** Démarre un {@link HttpServer} JDK sur {@code port} et bind l'adapter à {@code contextPath}. */
    public HttpServer serve(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(contextPath.isEmpty() ? "/" : contextPath, asHandler());
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    /**
     * Démarre un serveur avec une {@link Application} JAX-RS bootstrappée via
     * {@link CassiniStack}.
     */
    public static HttpServer serve(int port, jakarta.ws.rs.core.Application app)
            throws IOException {
        var stack = CassiniStack.builder().application(app).build();
        return new JdkHttpAdapter(stack.adapter()).serve(port);
    }
}
