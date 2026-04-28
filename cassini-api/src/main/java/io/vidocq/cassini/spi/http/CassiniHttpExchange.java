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

    InputStream requestBody();

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
}
