package io.vidocq.cassini.spi.http;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Abstraction of the in-flight HTTP request/response, exposed by a transport
 * (Chappe, JDK HttpServer, etc.) to the Cassini runtime.
 *
 * <p>The contract is deliberately minimal: Cassini does not depend on any
 * particular HTTP engine. All JAX-RS logic (routing, providers, filters,
 * interceptors) is carried by {@code cassini-core} and operates on this
 * interface.
 *
 * <p><b>Lifecycle</b>: an exchange is valid for the duration of one dispatch.
 * Headers and status are read just before the body is written. The output
 * stream is consumed exactly once.
 */
public interface CassiniHttpExchange {

    String method();

    URI requestUri();

    /** Raw, un-decoded URI — preserves the original encoding (cf. JAX-RS §3.7). */
    String requestUriRaw();

    Map<String, List<String>> requestHeaders();

    /** Case-insensitive header lookup — returns {@code null} if absent. */
    default String firstHeader(String name) {
        for (var e : requestHeaders().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                List<String> v = e.getValue();
                return v == null || v.isEmpty() ? null : v.get(0);
            }
        }
        return null;
    }

    /** Case-insensitive lookup — returns an empty list if absent. */
    default List<String> headers(String name) {
        for (var e : requestHeaders().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue() == null ? List.of() : e.getValue();
            }
        }
        return List.of();
    }

    /** Decoded query string — Map of key → list of values. Empty map if no query. */
    default Map<String, List<String>> queryParams() {
        String raw = requestUri().getRawQuery();
        if (raw == null || raw.isEmpty()) return Map.of();
        java.util.Map<String, java.util.List<String>> out = new java.util.LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            try {
                k = java.net.URLDecoder.decode(k, java.nio.charset.StandardCharsets.UTF_8);
                v = java.net.URLDecoder.decode(v, java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception ignored) {}
            out.computeIfAbsent(k, _ -> new java.util.ArrayList<>()).add(v);
        }
        return out;
    }

    InputStream requestBody();

    /** Request Content-Length, or {@code -1} if unknown/not supplied. */
    default long contentLength() {
        String raw = firstHeader("Content-Length");
        if (raw == null || raw.isEmpty()) return -1L;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return -1L; }
    }

    /** Application prefix (e.g. {@code "/api"}). Empty or {@code "/"} if no context. */
    default String contextPath() { return ""; }

    /**
     * Request path stripped of the contextPath — ready for routing.
     *
     * <p>The default implementation subtracts {@link #contextPath()} from
     * {@link #requestUri()}{@code .getRawPath()}. Transports exposing a native
     * {@code pathInfo} (e.g. Chappe {@code Request.pathInfo()}) should override
     * this method to avoid a double decoding pass.
     */
    default String routingPath() {
        URI u = requestUri();
        String path = u != null ? u.getRawPath() : null;
        if (path == null || path.isEmpty()) path = "/";
        String ctx = contextPath();
        if (ctx != null && !ctx.isEmpty() && !"/".equals(ctx) && path.startsWith(ctx)) {
            path = path.substring(ctx.length());
            if (path.isEmpty()) path = "/";
        }
        return path;
    }

    void setStatus(int code);

    /** Mutable response headers, read just before flushing the body. */
    Map<String, List<String>> responseHeaders();

    OutputStream responseBody();

    SocketAddress remoteAddress();

    boolean isSecure();

    /** Authentication scheme (BASIC, DIGEST, BEARER...) or {@code null} if unauthenticated. */
    String authScheme();

    /** Authenticated principal, or {@code null}. */
    Principal userPrincipal();

    /** Role check delegated to the transport (BASIC auth via Chappe, etc.). */
    boolean isUserInRole(String role);

    /**
     * Request-scoped attribute store, thread-safe with virtual threads (M2h).
     * Replaces the per-request {@code ThreadLocal}s inside {@code cassini-core}.
     */
    void setAttribute(String key, Object value);

    /** @return the value of attribute {@code key}, or {@code null} if absent. */
    Object getAttribute(String key);

    /**
     * Opens streaming mode for SSE / chunked-transfer (M2i).
     *
     * <p>Sends the response headers with an unknown-length body
     * ({@code Transfer-Encoding: chunked} on HTTP/1.1) and returns a
     * {@link CassiniStreamingSink} allowing chunk-by-chunk writes.
     *
     * <p>Transports that do not support streaming return {@code null} —
     * the caller must fall back to buffered mode.
     *
     * @param status HTTP response code (e.g. 200)
     * @param headers response headers to send before the body
     */
    default CassiniStreamingSink openForStreaming(int status, Map<String, List<String>> headers) {
        return null;
    }
}
