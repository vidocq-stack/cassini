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

    // @Priority utilisé pour résoudre l'ordre des ClientRequest/ResponseFilter
    // quand aucune entrée explicite n'est passée à register(component, priority).
    requires static jakarta.annotation;

    // jdk.httpserver utilisé exclusivement par FakeHttpServer en src/test/java —
    // `static` car aucune dépendance runtime en production sur com.sun.net.httpserver.
    requires static jdk.httpserver;

    // Aucun package public exposé en MVP — toute la surface JAX-RS Client est consommée
    // via les interfaces jakarta.ws.rs.client.* et la discovery `provides` ci-dessous.

    provides jakarta.ws.rs.client.ClientBuilder
            with io.vidocq.cassini.client.internal.CassiniClientBuilder;

    // Auto-discovery des Features tiers (instrumentation OTel, auth, logging...) qui
    // s'enregistrent via ServiceLoader. CassiniClientBuilder.build() les invoque sur
    // un FeatureContext adapter. MP Telemetry 2.1 exige ce comportement pour que
    // ClientBuilder.newClient() soit auto-instrumenté sans .register() explicite.
    uses jakarta.ws.rs.core.Feature;

    // RuntimeDelegate : nécessaire pour UriBuilder.fromUri(...) côté Client. Cassini-core
    // contient déjà CassiniRuntimeDelegate mais ne le déclare pas comme service par défaut
    // pour éviter une collision si plusieurs adapters (chappe, jdk-http) sont en classpath.
    // Côté Client : on l'expose ici pour rendre cassini-client autonome — le ServiceLoader
    // résoudra l'un des trois providers (chappe / jdk-http / client) selon le classpath ;
    // tous trois pointent vers CassiniRuntimeDelegate, donc pas de divergence runtime.
    provides jakarta.ws.rs.ext.RuntimeDelegate
            with io.vidocq.cassini.client.internal.CassiniClientRuntimeDelegate;
}
