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
package io.vidocq.cassini.tck;

import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.net.ServerSocket;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal test harness for mounting a complete Cassini runtime in front of
 * Chappe on an ephemeral port. Used by the Arquillian TCK adapter
 * ({@code VidocqCassiniDeployableContainer}).
 *
 * <p>Builds:</p>
 * <ul>
 *   <li>a {@link UriRouter} from the provided {@code @Path} classes;</li>
 *   <li>an {@link Invoker} with a resolver based on no-arg constructor
 *     instantiation (the TCK deploys simple resources);</li>
 *   <li>a {@link ChappeHttpAdapter} and mounts it on a local Chappe
 *     {@link Server}.</li>
 * </ul>
 */
public final class CassiniTestHarness implements AutoCloseable {

    private final Server server;
    private final int port;
    private final String baseUrl;

    private CassiniTestHarness(Server server, int port, String baseUrl) {
        this.server = server;
        this.port = port;
        this.baseUrl = baseUrl;
    }

    public int port() { return port; }
    public String baseUrl() { return baseUrl; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        /** Explicitly provided instances (resolved identically per request). */
        private final Map<Class<?>, Object> beans = new HashMap<>();
        /** Classes to be instantiated per-request (JAX-RS §3.1.1). */
        private final java.util.Set<Class<?>> perRequestClasses = new java.util.LinkedHashSet<>();
        private final FilterRegistry filters = new FilterRegistry();
        private final ExceptionMapperRegistry exceptionMappers = new ExceptionMapperRegistry();
        private final MessageBodyRegistry bodies = new MessageBodyRegistry();
        private String contextPath = "/";
        private Integer fixedPort;
        private jakarta.ws.rs.core.Application application;

        /** Publishes the user-level Application instance: injected via @Context
         *  {@link jakarta.ws.rs.core.Application} into resource methods/fields
         *  (§9.4). */
        public Builder application(jakarta.ws.rs.core.Application app) {
            this.application = app;
            return this;
        }

        public Builder provider(Object instance) {
            filters.register(instance);
            if (instance instanceof ExceptionMapper<?> em) {
                registerExceptionMapper(em);
            }
            if (instance instanceof MessageBodyReader<?> r) bodies.addReader(r);
            if (instance instanceof MessageBodyWriter<?> w) bodies.addWriter(w);
            return this;
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private void registerExceptionMapper(ExceptionMapper em) {
            for (var iface : em.getClass().getGenericInterfaces()) {
                if (iface instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getRawType() == ExceptionMapper.class
                        && pt.getActualTypeArguments().length == 1
                        && pt.getActualTypeArguments()[0] instanceof Class<?> c
                        && Throwable.class.isAssignableFrom(c)) {
                    exceptionMappers.register((Class) c, em);
                    return;
                }
            }
        }

        public Builder resource(Object instance) {
            beans.put(instance.getClass(), instance);
            return this;
        }

        public Builder resourceClass(Class<?> cls) {
            // §3.1.1: only a public, non-abstract class can be a root resource.
            // Non-public constructors → the class
            // is ignored (→ 404 visible from the TCK).
            if (!java.lang.reflect.Modifier.isPublic(cls.getModifiers())) {
                return this;
            }
            if (java.lang.reflect.Modifier.isAbstract(cls.getModifiers())) {
                return this;
            }
            if (pickConstructor(cls) == null) {
                return this;
            }
            perRequestClasses.add(cls);
            return this;
        }

        /** §3.1.1: picks the public constructor with the most injectable
         *  parameters (@Context, @*Param). Returns {@code null} if no suitable
         *  public constructor exists. */
        private static java.lang.reflect.Constructor<?> pickConstructor(Class<?> cls) {
            java.lang.reflect.Constructor<?> best = null;
            int bestScore = -1;
            for (var c : cls.getConstructors()) { // public only
                int paramCount = c.getParameterCount();
                boolean allInjectable = true;
                for (var p : c.getParameters()) {
                    if (!isInjectable(p)) { allInjectable = false; break; }
                }
                if (!allInjectable) continue;
                if (paramCount > bestScore) { bestScore = paramCount; best = c; }
            }
            return best;
        }

        private static boolean isInjectable(java.lang.reflect.Parameter p) {
            if (p.getAnnotation(jakarta.ws.rs.core.Context.class) != null) return true;
            if (p.getAnnotation(jakarta.ws.rs.PathParam.class) != null) return true;
            if (p.getAnnotation(jakarta.ws.rs.QueryParam.class) != null) return true;
            if (p.getAnnotation(jakarta.ws.rs.HeaderParam.class) != null) return true;
            if (p.getAnnotation(jakarta.ws.rs.CookieParam.class) != null) return true;
            if (p.getAnnotation(jakarta.ws.rs.MatrixParam.class) != null) return true;
            if (p.getAnnotation(jakarta.ws.rs.FormParam.class) != null) return true;
            if (p.getAnnotation(jakarta.ws.rs.BeanParam.class) != null) return true;
            // A no-arg constructor is trivially "all injectable".
            return false;
        }

        public Builder contextPath(String path) {
            this.contextPath = path == null || path.isEmpty() ? "/" : path;
            return this;
        }

        public Builder port(int port) { this.fixedPort = port; return this; }

        /** Builds the bridge + handler without starting a server.
         *  Used by {@link io.vidocq.cassini.tck.arquillian.VidocqCassiniDeployableContainer}
         *  for the multi-context shared server. */
        public record BuiltHandler(Handler bridgeHandler, String prefix) {}

        public BuiltHandler buildHandler() {
            java.util.Set<Class<?>> allClasses = new java.util.LinkedHashSet<>(beans.keySet());
            allClasses.addAll(perRequestClasses);
            List<ResourceMethod> routes = ResourceScanner.discover(allClasses.toArray(Class<?>[]::new));
            // §6.5.5: DynamicFeatures execute once per resource method,
            // after scanning, to bind specific filters/interceptors.
            filters.applyDynamicFeatures(routes);
            UriRouter router = new UriRouter(routes);
            java.util.function.Function<Class<?>, Object> resolver = cls -> {
                Object fixed = beans.get(cls);
                if (fixed != null) return fixed;
                var ctor = pickConstructor(cls);
                if (ctor == null) throw new RuntimeException("No suitable constructor on " + cls);
                try {
                    if (ctor.getParameterCount() == 0) return ctor.newInstance();
                    var match = Invoker.currentMatch();
                    var req = Invoker.currentRequest();
                    Object[] args = io.vidocq.cassini.internal.ParamExtractor
                            .resolveConstructorArgs(ctor.getParameters(), match, req);
                    return ctor.newInstance(args);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate " + cls, e);
                }
            };
            Invoker invoker = new Invoker(resolver, bodies, exceptionMappers);
            invoker.setFilters(filters);
            // M2h: the deployment's Application is an Invoker field seeded into
            // each request scope — no per-request handler wrapper / ThreadLocal.
            if (this.application != null) invoker.setApplication(this.application);
            io.vidocq.cassini.internal.DefaultCassiniHttpAdapter engine =
                    new io.vidocq.cassini.internal.DefaultCassiniHttpAdapter(router, invoker);
            ChappeHttpAdapter bridge = new ChappeHttpAdapter(engine);
            final String prefix = "/".equals(contextPath) ? "" : contextPath;
            return new BuiltHandler(bridge, prefix);
        }

        public CassiniTestHarness start() {
            BuiltHandler bh = buildHandler();
            Handler rootHandler = bh.prefix().isEmpty() ? bh.bridgeHandler()
                    : new ContextStrippingHandler(bh.prefix(), bh.bridgeHandler());

            RuntimeException last = null;
            // More aggressive retry on fixed port (port 8080 may remain in
            // TIME_WAIT between tests). 10 attempts with 100 ms.
            int attempts = fixedPort != null ? 10 : 5;
            for (int attempt = 0; attempt < attempts; attempt++) {
                int port;
                if (fixedPort != null) {
                    port = fixedPort;
                } else {
                    try (ServerSocket s = new ServerSocket(0)) { port = s.getLocalPort(); }
                    catch (Exception e) { throw new RuntimeException(e); }
                }
                try {
                    Server server = Server.builder()
                            .host("127.0.0.1").port(port).handler(rootHandler).build();
                    server.start();
                    String url = "http://127.0.0.1:" + port
                            + ("/".equals(contextPath) ? "" : contextPath);
                    return new CassiniTestHarness(server, port, url);
                } catch (RuntimeException e) {
                    last = e;
                    if (fixedPort != null && attempt + 1 < attempts) {
                        try { Thread.sleep(100); } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            }
            throw last;
        }
    }

    @Override public void close() {
        try { server.stop(); } catch (RuntimeException ignored) {}
    }

    /**
     * Root handler that strips the contextPath before delegating to the Cassini
     * bridge. Reproduces what {@code ChappeMountPoint.mount(prefix, ...)}
     * does in the normal Vidocq integration, but without depending on the full
     * engine (the harness embeds just a bare Chappe Server).
     */
    private record ContextStrippingHandler(String prefix, Handler delegate) implements Handler {
        @Override public Response handle(Request request) throws Exception {
            String path = request.path();
            if (path == null) path = "/";
            if (!path.startsWith(prefix)) {
                // Outside the deployed context — direct 404 to avoid matching
                // the Cassini tree on unrelated paths.
                return Response.builder()
                        .status(io.vidocq.chappe.api.StatusCode.NOT_FOUND)
                        .body(io.vidocq.chappe.api.Body.empty())
                        .build();
            }
            String stripped = path.substring(prefix.length());
            if (stripped.isEmpty()) stripped = "/";
            final String newPath = stripped;
            Request remapped = new Request() {
                @Override public io.vidocq.chappe.api.HttpMethod method() { return request.method(); }
                @Override public java.net.URI uri() { return request.uri(); }
                @Override public String path() { return newPath; }
                @Override public String query() { return request.query(); }
                @Override public io.vidocq.chappe.api.HttpVersion version() { return request.version(); }
                @Override public io.vidocq.chappe.api.Headers headers() { return request.headers(); }
                @Override public io.vidocq.chappe.api.Body body() { return request.body(); }
                @Override public java.util.Map<String, String> pathParams() { return request.pathParams(); }
                @Override public java.util.Map<String, String> queryParams() { return request.queryParams(); }
                @Override public String contextPath() { return prefix; }
                @Override public String pathInfo() { return newPath; }
                // Delegate per-request attributes to the wrapped request — the
                // interface defaults are no-ops and would silently drop state
                // set by upstream handlers (e.g. cassini.auth from BASIC auth).
                @Override public Object attribute(String key) { return request.attribute(key); }
                @Override public Request attribute(String key, Object value) { request.attribute(key, value); return this; }
            };
            return delegate.handle(remapped);
        }
    }
}
