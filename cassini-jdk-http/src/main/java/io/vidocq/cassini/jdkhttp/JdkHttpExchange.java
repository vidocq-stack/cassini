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
 * {@link CassiniHttpExchange} implementation backed by a
 * JDK {@link HttpExchange} ({@code com.sun.net.httpserver}).
 *
 * <p>Acts as a standalone "Mode A" transport (zero external dependencies), used
 * for pure Cassini unit tests and as a fallback when Vauban cannot depend on
 * Chappe.
 */
public final class JdkHttpExchange implements CassiniHttpExchange {

    private final HttpExchange exchange;
    private final String contextPath;
    private int responseStatus = 200;
    private final Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
    private final java.io.ByteArrayOutputStream bodyBuffer = new java.io.ByteArrayOutputStream();
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
    public byte[] collectedBody() { return bodyBuffer.toByteArray(); }
    public Map<String, List<String>> collectedHeaders() { return responseHeaders; }

    @Override public String method() { return exchange.getRequestMethod(); }

    @Override public URI requestUri() { return exchange.getRequestURI(); }

    @Override public String requestUriRaw() { return exchange.getRequestURI().toString(); }

    @Override public Map<String, List<String>> requestHeaders() { return exchange.getRequestHeaders(); }

    @Override public InputStream requestBody() { return exchange.getRequestBody(); }

    @Override public String contextPath() { return contextPath; }

    @Override public void setStatus(int code) { this.responseStatus = code; }

    @Override public Map<String, List<String>> responseHeaders() { return responseHeaders; }

    @Override public OutputStream responseBody() { return bodyBuffer; }

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
     * Opens chunked streaming mode (M2i): sends headers immediately
     * with length=0 (chunked transfer) and returns a sink that can
     * write SSE events as they are produced.
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
