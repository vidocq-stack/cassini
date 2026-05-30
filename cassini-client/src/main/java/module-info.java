/**
 * Cassini JAX-RS Client 4.0 — zero-dependency {@link jakarta.ws.rs.client.ClientBuilder}
 * implementation built on {@link java.net.http.HttpClient} and virtual threads.
 *
 * <p>Standard discovery via {@link java.util.ServiceLoader} on the classpath and via
 * JPMS {@code provides} on the module-path. Reuses {@code MessageBodyRegistry},
 * {@code CassiniResponse}, {@code CassiniUriBuilder} and {@code CassiniRuntimeDelegate}
 * from {@code cassini-core} to share request/response serialization.</p>
 */
module io.vidocq.cassini.client {
    requires transitive io.vidocq.cassini.api;
    requires transitive jakarta.ws.rs;
    requires io.vidocq.cassini.core;
    requires java.net.http;

    // @Priority used to resolve the order of ClientRequest/ResponseFilters
    // when no explicit value is passed to register(component, priority).
    requires static jakarta.annotation;

    // jdk.httpserver used exclusively by FakeHttpServer under src/test/java —
    // `static` because there is no runtime production dependency on com.sun.net.httpserver.
    requires static jdk.httpserver;

    // No public package is exposed in the MVP — the entire JAX-RS Client surface is consumed
    // through the jakarta.ws.rs.client.* interfaces and the `provides` discovery below.

    provides jakarta.ws.rs.client.ClientBuilder
            with io.vidocq.cassini.client.internal.CassiniClientBuilder;

    // Auto-discovery of third-party Features (OTel instrumentation, auth, logging...) that
    // register themselves via ServiceLoader. CassiniClientBuilder.build() invokes them on
    // a FeatureContext adapter. MP Telemetry 2.1 mandates this behaviour so that
    // ClientBuilder.newClient() is auto-instrumented without an explicit .register().
    uses jakarta.ws.rs.core.Feature;

    // RuntimeDelegate: required by UriBuilder.fromUri(...) on the Client side. cassini-core
    // already contains CassiniRuntimeDelegate but does not declare it as a default service
    // to avoid a collision when several adapters (chappe, jdk-http) are on the classpath.
    // Client side: we expose it here to make cassini-client self-contained — ServiceLoader
    // resolves one of the three providers (chappe / jdk-http / client) depending on classpath;
    // all three point to CassiniRuntimeDelegate, so there is no runtime divergence.
    provides jakarta.ws.rs.ext.RuntimeDelegate
            with io.vidocq.cassini.client.internal.CassiniClientRuntimeDelegate;
}
