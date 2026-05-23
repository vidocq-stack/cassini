/**
 * Cassini JAX-RS Client 4.0 — implémentation {@link jakarta.ws.rs.client.ClientBuilder}
 * zéro-dépendance basée sur {@link java.net.http.HttpClient} et virtual threads.
 *
 * <p>Découverte standard via {@link java.util.ServiceLoader} sur le classpath et via
 * {@code provides} JPMS sur le module-path. Réutilise {@code MessageBodyRegistry},
 * {@code CassiniResponse}, {@code CassiniUriBuilder} et {@code CassiniRuntimeDelegate}
 * de {@code cassini-core} pour mutualiser la sérialisation request/response.</p>
 */
module io.vidocq.cassini.client {
    requires transitive io.vidocq.cassini.api;
    requires transitive jakarta.ws.rs;
    requires io.vidocq.cassini.core;
    requires java.net.http;

    // Aucun package public exposé en MVP — toute la surface JAX-RS Client est consommée
    // via les interfaces jakarta.ws.rs.client.* et la discovery `provides` ci-dessous.

    provides jakarta.ws.rs.client.ClientBuilder
            with io.vidocq.cassini.client.internal.CassiniClientBuilder;
}
