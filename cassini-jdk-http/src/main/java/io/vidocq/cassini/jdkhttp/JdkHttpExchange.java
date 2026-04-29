package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpExchange;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.spi.http.CassiniStreamingSink;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Implémentation {@link CassiniHttpExchange} adossée à un
 * {@link HttpExchange} JDK ({@code com.sun.net.httpserver}).
 *
 * <p>Sert de transport "Mode A" autonome (zéro dépendance externe), utilisé
 * pour les unit tests Cassini-pur et comme fallback si Vauban ne peut pas
 * dépendre de Chappe.
 */
public final class JdkHttpExchange implements CassiniHttpExchange {

    private final HttpExchange exchange;
    private final String contextPath;
    private int responseStatus = 200;
    private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    private final Map<String, Object> attributes = new java.util.HashMap<>();

    public JdkHttpExchange(HttpExchange exchange) {
        this(exchange, "");
    }

    public JdkHttpExchange(HttpExchange exchange, String contextPath) {
        this.exchange = exchange;
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    public HttpExchange jdkExchange() { return exchange; }
    public int collectedStatus() { return responseStatus; }

    @Override public String method() { return exchange.getRequestMethod(); }

    @Override public URI requestUri() { return exchange.getRequestURI(); }

    @Override public String requestUriRaw() { return exchange.getRequestURI().toString(); }

    @Override public Map<String, List<String>> requestHeaders() { return exchange.getRequestHeaders(); }

    @Override public InputStream requestBody() { return exchange.getRequestBody(); }

    @Override public String contextPath() { return contextPath; }

    @Override public void setStatus(int code) { this.responseStatus = code; }

    @Override public Map<String, List<String>> responseHeaders() { return responseHeaders; }

    @Override public OutputStream responseBody() { return exchange.getResponseBody(); }

    @Override public SocketAddress remoteAddress() { return exchange.getRemoteAddress(); }

    @Override public boolean isSecure() {
        return "https".equalsIgnoreCase(exchange.getRequestURI().getScheme());
    }

    @Override public String authScheme() { return null; }

    @Override public Principal userPrincipal() { return null; }

    @Override public boolean isUserInRole(String role) { return false; }

    @Override public void setAttribute(String key, Object value) { attributes.put(key, value); }
    @Override public Object getAttribute(String key) { return attributes.get(key); }

    /**
     * Ouvre le mode streaming chunked (M2i) : envoie les headers immédiatement
     * avec longueur=0 (chunked transfer) et retourne un sink permettant
     * d'écrire des events SSE au fil de l'eau.
     */
    @Override
    public CassiniStreamingSink openForStreaming(int status, Map<String, List<String>> headers) {
        try {
            for (var e : headers.entrySet()) {
                exchange.getResponseHeaders().put(e.getKey(),
                        new java.util.ArrayList<>(e.getValue()));
            }
            exchange.sendResponseHeaders(status, 0); // 0 = chunked
            OutputStream out = exchange.getResponseBody();
            return new CassiniStreamingSink() {
                private volatile boolean open = true;

                @Override
                public CompletionStage<Void> writeChunk(byte[] data) {
                    try {
                        out.write(data);
                        return CompletableFuture.completedFuture(null);
                    } catch (IOException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }

                @Override
                public CompletionStage<Void> flush() {
                    try {
                        out.flush();
                        return CompletableFuture.completedFuture(null);
                    } catch (IOException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }

                @Override
                public CompletionStage<Void> close() {
                    open = false;
                    try {
                        out.close();
                        exchange.close();
                        return CompletableFuture.completedFuture(null);
                    } catch (IOException e) {
                        return CompletableFuture.failedFuture(e);
                    }
                }

                @Override public boolean isOpen() { return open; }
            };
        } catch (IOException e) {
            return null;
        }
    }
}
