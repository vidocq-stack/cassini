/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
/**
 * Cassini JAX-RS Client 4.0 — zero-dependency implementation of {@link jakarta.ws.rs.client.ClientBuilder}
 * based on {@link java.net.http.HttpClient} and virtual threads.
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

    // Client-side JSON-B entity (de)serialisation (ClientEntityJsonb) — mirrors
    // the server MBW/MBR so POJO entities round-trip instead of falling back to toString().
    requires jakarta.json.bind;

    // @Priority used to resolve the ordering of ClientRequest/ResponseFilter
    // when no explicit entry is passed to register(component, priority).
    requires static jakarta.annotation;

    // jdk.httpserver used exclusively by FakeHttpServer in src/test/java —
    // `static` because there is no production runtime dependency on com.sun.net.httpserver.
    requires static jdk.httpserver;

    // No public package exposed in MVP — all JAX-RS Client surface is consumed
    // via jakarta.ws.rs.client.* interfaces and the `provides` discovery below.

    provides jakarta.ws.rs.client.ClientBuilder
            with io.vidocq.cassini.client.internal.CassiniClientBuilder;

    // Auto-discovery of third-party Features (OTel instrumentation, auth, logging...) that
    // self-register via ServiceLoader. CassiniClientBuilder.build() invokes them on
    // a FeatureContext adapter. MP Telemetry 2.1 requires this behavior so that
    // ClientBuilder.newClient() is auto-instrumented without explicit .register().
    uses jakarta.ws.rs.core.Feature;

    // RuntimeDelegate: needed for UriBuilder.fromUri(...) on the Client side. Cassini-core
    // already contains CassiniRuntimeDelegate but does not declare it as a default service
    // to avoid a collision if multiple adapters (chappe, jdk-http) are on the classpath.
    // Client side: we expose it here to make cassini-client self-contained — the ServiceLoader
    // will resolve one of the three providers (chappe / jdk-http / client) based on the classpath;
    // all three point to CassiniRuntimeDelegate, so no runtime divergence.
    provides jakarta.ws.rs.ext.RuntimeDelegate
            with io.vidocq.cassini.client.internal.CassiniClientRuntimeDelegate;
}
