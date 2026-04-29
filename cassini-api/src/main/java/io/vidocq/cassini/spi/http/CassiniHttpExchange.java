package io.vidocq.cassini.spi.http;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * Abstraction de la requête/réponse HTTP en cours, exposée par un transport
 * (Chappe, JDK HttpServer, etc.) au runtime Cassini.
 *
 * <p>Le contrat est délibérément minimal : Cassini ne dépend d'aucun moteur HTTP
 * particulier. Toute la logique JAX-RS (routing, providers, filters, interceptors)
 * est portée par {@code cassini-core} et opère sur cette interface.
 *
 * <p><b>Cycle de vie</b> : un exchange est valide pour la durée d'un dispatch.
 * Les headers et le statut sont lus juste avant l'écriture du body. L'output
 * stream est consommé une seule fois.
 */
public interface CassiniHttpExchange {

    String method();

    URI requestUri();

    /** URI brute non-décodée — pour préserver l'encoding original (cf. JAX-RS §3.7). */
    String requestUriRaw();

    Map<String, List<String>> requestHeaders();

    /** Lookup case-insensitive d'un header — retourne {@code null} si absent. */
    default String firstHeader(String name) {
        for (var e : requestHeaders().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                List<String> v = e.getValue();
                return v == null || v.isEmpty() ? null : v.get(0);
            }
        }
        return null;
    }

    /** Lookup case-insensitive — retourne une liste vide si absent. */
    default List<String> headers(String name) {
        for (var e : requestHeaders().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue() == null ? List.of() : e.getValue();
            }
        }
        return List.of();
    }

    /** Query string décodée — Map clé → liste de valeurs. Map vide si pas de query. */
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

    /** Content-Length de la requête, ou {@code -1} si inconnu/non-fourni. */
    default long contentLength() {
        String raw = firstHeader("Content-Length");
        if (raw == null || raw.isEmpty()) return -1L;
        try { return Long.parseLong(raw.trim()); } catch (NumberFormatException e) { return -1L; }
    }

    /** Préfixe d'application (ex. {@code "/api"}). Vide ou {@code "/"} si pas de contexte. */
    default String contextPath() { return ""; }

    /**
     * Chemin de la requête sans le contextPath — prêt pour le routing.
     *
     * <p>L'implémentation par défaut soustrait {@link #contextPath()} de
     * {@link #requestUri()}{@code .getRawPath()}. Les transports qui disposent
     * d'un {@code pathInfo} natif (ex. Chappe {@code Request.pathInfo()}) doivent
     * surcharger cette méthode pour éviter un double-décodage.
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

    /** Headers de réponse mutables, lus juste avant flush du body. */
    Map<String, List<String>> responseHeaders();

    OutputStream responseBody();

    SocketAddress remoteAddress();

    boolean isSecure();

    /** Schéma d'authentification (BASIC, DIGEST, BEARER...) ou {@code null} si non authentifié. */
    String authScheme();

    /** Principal authentifié ou {@code null}. */
    Principal userPrincipal();

    /** Vérification de rôle déléguée au transport (BASIC auth via Chappe, etc.). */
    boolean isUserInRole(String role);

    /**
     * Store d'attributs request-scope, thread-safe avec virtual threads (M2h).
     * Remplace les {@code ThreadLocal} per-request dans {@code cassini-core}.
     */
    void setAttribute(String key, Object value);

    /** @return la valeur de l'attribut {@code key}, ou {@code null} si absent. */
    Object getAttribute(String key);

    /**
     * Ouvre le mode streaming pour SSE / chunked-transfer (M2i).
     *
     * <p>Envoie les headers de réponse avec un corps de longueur inconnue
     * ({@code Transfer-Encoding: chunked} pour HTTP/1.1) et retourne un
     * {@link CassiniStreamingSink} permettant d'écrire des chunks au fil de l'eau.
     *
     * <p>Les transports qui ne supportent pas le streaming retournent {@code null} —
     * l'appelant doit retomber sur le mode bufferisé.
     *
     * @param status code HTTP de la réponse (ex. 200)
     * @param headers headers de réponse à envoyer avant le corps
     */
    default CassiniStreamingSink openForStreaming(int status, Map<String, List<String>> headers) {
        return null;
    }
}
