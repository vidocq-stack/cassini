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
package io.vidocq.cassini.chappe;

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cassini {@link jakarta.ws.rs.ext.RuntimeDelegate} providing SE bootstrap
 * through Chappe.
 *
 * <p>Selected through the system property:
 * {@code -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.chappe.ChappeRuntimeDelegate}
 *
 * <p>Extends {@link CassiniRuntimeDelegate} (UriBuilder / ResponseBuilder /
 * HeaderDelegate boilerplate, like {@code JdkHttpRuntimeDelegate}) and only
 * implements {@code bootstrap()} to start a Chappe {@link Server}. The former
 * standalone copy of every delegate (CASSINI-003) is gone: {@code cassini-chappe}
 * has had a hard {@code requires io.vidocq.cassini.core} for a while, so the
 * "no dependency on cassini-core" rationale for the duplication no longer held.
 */
public final class ChappeRuntimeDelegate extends CassiniRuntimeDelegate {

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application,
                                                           SeBootstrap.Configuration config) {
        return CompletableFuture.supplyAsync(() -> new ChappeSeBootstrapInstance(application, config));
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz,
                                                            SeBootstrap.Configuration config) {
        try {
            return bootstrap(clazz.getDeclaredConstructor().newInstance(), config);
        } catch (ReflectiveOperationException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private record EffectiveConfig(java.util.Map<String, Object> props)
            implements SeBootstrap.Configuration {
        @Override public Object property(String name) { return props.get(name); }
    }

    private static final class ChappeSeBootstrapInstance implements SeBootstrap.Instance {
        private final SeBootstrap.Configuration config;
        private volatile Server server;

        ChappeSeBootstrapInstance(Application application,
                                  SeBootstrap.Configuration requested) {
            Object p = requested.property(SeBootstrap.Configuration.PORT);
            int reqPort = p instanceof Number n ? n.intValue() : -1;
            if (reqPort <= 0) {
                try (var ss = new java.net.ServerSocket(
                        0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
                    reqPort = ss.getLocalPort();
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
            java.util.Map<String, Object> effective = new java.util.HashMap<>();
            for (String k : new String[]{
                    SeBootstrap.Configuration.PROTOCOL,
                    SeBootstrap.Configuration.HOST,
                    SeBootstrap.Configuration.PORT,
                    SeBootstrap.Configuration.ROOT_PATH,
                    SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION,
                    SeBootstrap.Configuration.SSL_CONTEXT}) {
                Object v = requested.property(k);
                if (v != null) effective.put(k, v);
            }
            effective.put(SeBootstrap.Configuration.PORT, reqPort);
            effective.put(SeBootstrap.Configuration.HOST, "localhost");
            this.config = new EffectiveConfig(java.util.Map.copyOf(effective));

            try {
                // Bootstrap through the public SPI — no direct access to internals.
                var stack = CassiniStack.builder().application(application).build();
                var bridge = new ChappeHttpAdapter(stack.adapter());

                String rootPath = (String) requested.property(
                        SeBootstrap.Configuration.ROOT_PATH);
                String appPath = "";
                jakarta.ws.rs.ApplicationPath ap =
                        application.getClass().getAnnotation(
                                jakarta.ws.rs.ApplicationPath.class);
                if (ap != null) {
                    appPath = ap.value();
                    if (!appPath.isEmpty() && !appPath.startsWith("/"))
                        appPath = "/" + appPath;
                    if (appPath.length() > 1 && appPath.endsWith("/"))
                        appPath = appPath.substring(0, appPath.length() - 1);
                }
                String rootNorm = (rootPath == null || "/".equals(rootPath)
                        || rootPath.isEmpty())
                        ? "" : (rootPath.startsWith("/") ? rootPath : "/" + rootPath);
                if (rootNorm.length() > 1 && rootNorm.endsWith("/"))
                    rootNorm = rootNorm.substring(0, rootNorm.length() - 1);
                final String prefix = rootNorm + appPath;

                Handler handler = prefix.isEmpty() ? bridge
                        : req -> {
                    String pth = req.path() == null ? "/" : req.path();
                    if (!pth.startsWith(prefix)) {
                        return Response.builder()
                                .status(StatusCode.NOT_FOUND)
                                .body(Body.empty()).build();
                    }
                    String stripped = pth.substring(prefix.length());
                    if (stripped.isEmpty()) stripped = "/";
                    final String newPath = stripped;
                    Request remapped = new Request() {
                        @Override public io.vidocq.chappe.api.HttpMethod method() { return req.method(); }
                        @Override public java.net.URI uri() { return req.uri(); }
                        @Override public String path() { return newPath; }
                        @Override public String query() { return req.query(); }
                        @Override public io.vidocq.chappe.api.HttpVersion version() { return req.version(); }
                        @Override public io.vidocq.chappe.api.Headers headers() { return req.headers(); }
                        @Override public Body body() { return req.body(); }
                        @Override public java.util.Map<String, String> pathParams() { return req.pathParams(); }
                        @Override public java.util.Map<String, String> queryParams() { return req.queryParams(); }
                        @Override public String contextPath() { return prefix; }
                        @Override public String pathInfo() { return newPath; }
                        // Delegate per-request attributes to the wrapped request — the
                        // interface defaults are no-ops and would silently drop state
                        // set by upstream handlers (e.g. cassini.auth from BASIC auth).
                        @Override public Object attribute(String key) { return req.attribute(key); }
                        @Override public Request attribute(String key, Object value) { req.attribute(key, value); return this; }
                    };
                    return bridge.handle(remapped);
                };

                Server s = Server.builder()
                        .host("127.0.0.1").port(reqPort).handler(handler).build();
                s.start();
                this.server = s;
            } catch (RuntimeException e) {
                this.server = null;
                throw e;
            }
        }

        @Override public SeBootstrap.Configuration configuration() { return config; }

        @Override
        public CompletionStage<SeBootstrap.Instance.StopResult> stop() {
            return CompletableFuture.supplyAsync(() -> {
                if (server != null) {
                    try { server.stop(); } catch (RuntimeException ignored) {}
                }
                return new SeBootstrap.Instance.StopResult() {
                    @Override public <T> T unwrap(Class<T> nativeClass) {
                        throw new IllegalArgumentException();
                    }
                };
            });
        }

        @Override public <T> T unwrap(Class<T> nativeClass) {
            if (nativeClass.isInstance(server)) return nativeClass.cast(server);
            throw new IllegalArgumentException("Cannot unwrap to " + nativeClass);
        }
    }
}
