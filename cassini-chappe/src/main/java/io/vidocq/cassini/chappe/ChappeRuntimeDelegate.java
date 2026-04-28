package io.vidocq.cassini.chappe;

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * {@link CassiniRuntimeDelegate} qui surcharge {@code bootstrap()} avec un
 * transport HTTP basé sur Chappe.
 *
 * <p>Sélectionnée via la property système :
 * {@code -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.chappe.ChappeRuntimeDelegate}
 * (déjà configurée dans le profil {@code tck-official}).
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

    private record CassiniBootstrapConfig(java.util.Map<String, Object> props)
            implements SeBootstrap.Configuration {
        @Override public Object property(String name) { return props.get(name); }
    }

    private static final class ChappeSeBootstrapInstance implements SeBootstrap.Instance {
        private final SeBootstrap.Configuration config;
        private volatile Server server;

        ChappeSeBootstrapInstance(Application application, SeBootstrap.Configuration requested) {
            Object p = requested.property(SeBootstrap.Configuration.PORT);
            int reqPort = p instanceof Number n ? n.intValue() : -1;
            if (reqPort <= 0) {
                try (var ss = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
                    reqPort = ss.getLocalPort();
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
            java.util.Map<String, Object> effective = new java.util.HashMap<>();
            for (String k : new String[]{SeBootstrap.Configuration.PROTOCOL,
                    SeBootstrap.Configuration.HOST, SeBootstrap.Configuration.PORT,
                    SeBootstrap.Configuration.ROOT_PATH,
                    SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION,
                    SeBootstrap.Configuration.SSL_CONTEXT}) {
                Object v = requested.property(k);
                if (v != null) effective.put(k, v);
            }
            effective.put(SeBootstrap.Configuration.PORT, reqPort);
            effective.put(SeBootstrap.Configuration.HOST, "localhost");
            this.config = new CassiniBootstrapConfig(java.util.Map.copyOf(effective));

            try {
                java.util.Set<Class<?>> resourceClasses = new java.util.LinkedHashSet<>();
                java.util.Map<Class<?>, Object> resourceSingletons = new java.util.HashMap<>();
                if (application.getClasses() != null) resourceClasses.addAll(application.getClasses());
                if (application.getSingletons() != null) {
                    for (Object o : application.getSingletons()) {
                        resourceClasses.add(o.getClass());
                        resourceSingletons.put(o.getClass(), o);
                    }
                }
                java.util.Set<Class<?>> pathClasses = new java.util.LinkedHashSet<>();
                var filters = new io.vidocq.cassini.internal.filter.FilterRegistry();
                var bodies = new io.vidocq.cassini.internal.MessageBodyRegistry();
                var mappers = new io.vidocq.cassini.internal.ExceptionMapperRegistry();
                for (Class<?> c : resourceClasses) {
                    if (c.isAnnotationPresent(jakarta.ws.rs.Path.class)) pathClasses.add(c);
                    if (c.isAnnotationPresent(jakarta.ws.rs.ext.Provider.class)) {
                        Object inst = resourceSingletons.computeIfAbsent(c, k -> {
                            try { return k.getDeclaredConstructor().newInstance(); }
                            catch (ReflectiveOperationException e) { return null; }
                        });
                        if (inst == null) continue;
                        filters.register(inst);
                        if (inst instanceof jakarta.ws.rs.ext.MessageBodyReader<?> r) bodies.addReader(r);
                        if (inst instanceof jakarta.ws.rs.ext.MessageBodyWriter<?> w) bodies.addWriter(w);
                    }
                }
                var routes = io.vidocq.cassini.internal.ResourceScanner
                        .discover(pathClasses.toArray(Class<?>[]::new));
                var router = new io.vidocq.cassini.internal.UriRouter(routes);
                java.util.function.Function<Class<?>, Object> resolver = cls -> {
                    Object fixed = resourceSingletons.get(cls);
                    if (fixed != null) return fixed;
                    try { return cls.getDeclaredConstructor().newInstance(); }
                    catch (ReflectiveOperationException e) {
                        throw new RuntimeException("Failed to instantiate " + cls, e);
                    }
                };
                var invoker = new io.vidocq.cassini.internal.Invoker(resolver, bodies, mappers);
                invoker.setFilters(filters);
                var bridge = new ChappeHttpAdapter(router, invoker);

                String rootPath = (String) requested.property(SeBootstrap.Configuration.ROOT_PATH);
                String appPath = "";
                jakarta.ws.rs.ApplicationPath ap =
                        application.getClass().getAnnotation(jakarta.ws.rs.ApplicationPath.class);
                if (ap != null) {
                    appPath = ap.value();
                    if (!appPath.isEmpty() && !appPath.startsWith("/")) appPath = "/" + appPath;
                    if (appPath.length() > 1 && appPath.endsWith("/")) appPath = appPath.substring(0, appPath.length() - 1);
                }
                String rootNorm = (rootPath == null || "/".equals(rootPath) || rootPath.isEmpty())
                        ? "" : (rootPath.startsWith("/") ? rootPath : "/" + rootPath);
                if (rootNorm.length() > 1 && rootNorm.endsWith("/")) rootNorm = rootNorm.substring(0, rootNorm.length() - 1);
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
                    };
                    return bridge.handle(remapped);
                };

                Server s = Server.builder()
                        .host("127.0.0.1").port(reqPort).handler(handler).build();
                s.start();
                this.server = s;
            } catch (RuntimeException e) {
                this.server = null;
            }
        }

        @Override public SeBootstrap.Configuration configuration() { return config; }

        @Override public CompletionStage<SeBootstrap.Instance.StopResult> stop() {
            return CompletableFuture.supplyAsync(() -> {
                if (server != null) {
                    try { server.stop(); } catch (RuntimeException ignored) {}
                }
                return new SeBootstrap.Instance.StopResult() {
                    @Override public <T> T unwrap(Class<T> nativeClass) { throw new IllegalArgumentException(); }
                };
            });
        }

        @Override public <T> T unwrap(Class<T> nativeClass) {
            if (nativeClass.isInstance(server)) return nativeClass.cast(server);
            throw new IllegalArgumentException("Cannot unwrap to " + nativeClass);
        }
    }
}
