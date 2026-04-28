package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpExchange;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
}
