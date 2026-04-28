package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MatchResult;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;

/**
 * Adapter HTTP basé sur {@link HttpServer} (JDK pur).
 *
 * <p>Sert de transport autonome pour Cassini en Mode A, sans dépendance
 * externe. Utilisé pour les unit tests Cassini-pur et comme transport de
 * référence si l'utilisateur ne souhaite pas dépendre de Chappe.
 *
 * <p>Limites : pas d'async réel, pas de virtual threads natifs (le
 * {@code HttpServer} JDK utilise un Executor classique). Pour M2h+, préférer
 * {@code cassini-chappe} qui supportera les virtual threads.
 */
public final class JdkHttpAdapter {

    private final UriRouter router;
    private final Invoker invoker;
    private final String contextPath;

    public JdkHttpAdapter(UriRouter router, Invoker invoker) {
        this(router, invoker, "");
    }

    public JdkHttpAdapter(UriRouter router, Invoker invoker, String contextPath) {
        this.router = router;
        this.invoker = invoker;
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    /** Crée un {@link HttpHandler} JDK qui dispatche vers Cassini. */
    public HttpHandler asHandler() {
        return jdkExchange -> {
            JdkHttpExchange exchange = new JdkHttpExchange(jdkExchange, contextPath);
            String verb = exchange.method();
            String path = exchange.requestUri().getRawPath();
            if (path == null || path.isEmpty()) path = "/";
            if (!contextPath.isEmpty() && path.startsWith(contextPath)) {
                path = path.substring(contextPath.length());
                if (path.isEmpty()) path = "/";
            }

            try {
                CassiniHttpResponse out;
                List<MatchResult> candidates = router.matchAll(verb, path);
                if (candidates.isEmpty()) {
                    var allowed = router.methodsAllowedFor(path);
                    if (!allowed.isEmpty()) {
                        var r405 = jakarta.ws.rs.core.Response.status(405)
                                .header("Allow", String.join(", ", allowed)).build();
                        out = invoker.renderThrowable(
                                new jakarta.ws.rs.WebApplicationException(r405), exchange);
                    } else {
                        out = invoker.renderThrowable(
                                new jakarta.ws.rs.NotFoundException("No resource matches " + verb + " " + path),
                                exchange);
                    }
                } else {
                    out = invoker.invoke(candidates, exchange);
                }
                writeResponse(jdkExchange, out);
            } catch (Exception e) {
                byte[] body = (e.getMessage() == null ? "Internal Server Error" : e.getMessage())
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                jdkExchange.getResponseHeaders().add("Content-Type", "text/plain;charset=utf-8");
                jdkExchange.sendResponseHeaders(500, body.length);
                try (var os = jdkExchange.getResponseBody()) { os.write(body); }
            }
        };
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange jdkExchange,
                                      CassiniHttpResponse out) throws java.io.IOException {
        for (var e : out.headers().entrySet()) {
            jdkExchange.getResponseHeaders().put(e.getKey(), new java.util.ArrayList<>(e.getValue()));
        }
        byte[] body = out.body() == null ? new byte[0] : out.body();
        jdkExchange.sendResponseHeaders(out.status(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (var os = jdkExchange.getResponseBody()) { os.write(body); }
        } else {
            jdkExchange.close();
        }
    }

    /** Démarre un {@link HttpServer} JDK sur {@code port} et bind l'adapter à {@code contextPath}. */
    public HttpServer serve(int port) throws java.io.IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(contextPath.isEmpty() ? "/" : contextPath, asHandler());
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }
}
