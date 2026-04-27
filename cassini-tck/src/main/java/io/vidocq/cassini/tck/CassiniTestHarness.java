package io.vidocq.cassini.tck;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Server;
import io.vidocq.cassini.internal.CassiniRestBridge;
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
 * Harness de test minimal pour monter un runtime Cassini complet devant
 * Chappe sur un port éphémère. Utilisé par l'adaptateur Arquillian TCK
 * ({@code VidocqCassiniDeployableContainer}).
 *
 * <p>Construit :</p>
 * <ul>
 *   <li>un {@link UriRouter} à partir des classes {@code @Path} fournies ;</li>
 *   <li>un {@link Invoker} avec resolver basé sur instanciation par
 *     constructeur sans argument (le TCK déploie des ressources simples) ;</li>
 *   <li>un {@link CassiniRestBridge} et le monte sur un {@link Server}
 *     Chappe local.</li>
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
        /** Instances fournies explicitement (résolues à l'identique par requête). */
        private final Map<Class<?>, Object> beans = new HashMap<>();
        /** Classes à instancier par-requête (JAX-RS §3.1.1). */
        private final java.util.Set<Class<?>> perRequestClasses = new java.util.LinkedHashSet<>();
        private final FilterRegistry filters = new FilterRegistry();
        private final ExceptionMapperRegistry exceptionMappers = new ExceptionMapperRegistry();
        private final MessageBodyRegistry bodies = new MessageBodyRegistry();
        private String contextPath = "/";
        private Integer fixedPort;
        private jakarta.ws.rs.core.Application application;

        /** Publie l'instance Application user-level : injectée via @Context
         *  {@link jakarta.ws.rs.core.Application} dans les méthodes/champs
         *  de ressource (§9.4). */
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
            // §3.1.1 : seule une classe publique et non abstraite peut être
            // une ressource root. Les constructeurs non-public → la classe
            // est ignorée (→ 404 visible depuis le TCK).
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

        /** §3.1.1 : choisit le constructeur public avec le plus de paramètres
         *  injectables (@Context, @*Param). Retourne {@code null} si aucun
         *  constructeur public approprié n'existe. */
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
            // Un constructeur no-arg est trivialement "tous injectables".
            return false;
        }

        public Builder contextPath(String path) {
            this.contextPath = path == null || path.isEmpty() ? "/" : path;
            return this;
        }

        public Builder port(int port) { this.fixedPort = port; return this; }

        /** Construit le bridge + handler sans démarrer de serveur.
         *  Utilisé par {@link io.vidocq.cassini.tck.arquillian.VidocqCassiniDeployableContainer}
         *  pour le serveur partagé multi-contextes. */
        public record BuiltHandler(Handler bridgeHandler, String prefix) {}

        public BuiltHandler buildHandler() {
            java.util.Set<Class<?>> allClasses = new java.util.LinkedHashSet<>(beans.keySet());
            allClasses.addAll(perRequestClasses);
            List<ResourceMethod> routes = ResourceScanner.discover(allClasses.toArray(Class<?>[]::new));
            UriRouter router = new UriRouter(routes);
            java.util.function.Function<Class<?>, Object> resolver = cls -> {
                Object fixed = beans.get(cls);
                if (fixed != null) return fixed;
                var ctor = pickConstructor(cls);
                if (ctor == null) throw new RuntimeException("No suitable constructor on " + cls);
                try {
                    if (ctor.getParameterCount() == 0) return ctor.newInstance();
                    var match = Invoker.CURRENT_MATCH.get();
                    var req = Invoker.CURRENT_REQUEST.get();
                    Object[] args = io.vidocq.cassini.internal.ParamExtractor
                            .resolveConstructorArgs(ctor.getParameters(), match, req);
                    return ctor.newInstance(args);
                } catch (ReflectiveOperationException e) {
                    throw new RuntimeException("Failed to instantiate " + cls, e);
                }
            };
            Invoker invoker = new Invoker(resolver, bodies, exceptionMappers);
            invoker.setFilters(filters);
            CassiniRestBridge bridge = new CassiniRestBridge(router, invoker);
            final String prefix = "/".equals(contextPath) ? "" : contextPath;
            final jakarta.ws.rs.core.Application appInstance = this.application;
            Handler wrappedBridge = appInstance == null ? bridge : (Handler) request -> {
                io.vidocq.cassini.internal.ParamExtractor.setApplication(appInstance);
                try { return bridge.handle(request); }
                finally { io.vidocq.cassini.internal.ParamExtractor.clearApplication(); }
            };
            return new BuiltHandler(wrappedBridge, prefix);
        }

        public CassiniTestHarness start() {
            BuiltHandler bh = buildHandler();
            Handler rootHandler = bh.prefix().isEmpty() ? bh.bridgeHandler()
                    : new ContextStrippingHandler(bh.prefix(), bh.bridgeHandler());

            RuntimeException last = null;
            // Retry plus agressif sur port fixe (port 8080 peut rester en
            // TIME_WAIT entre deux tests). 10 tentatives avec 100 ms.
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
     * Handler racine qui strippe le contextPath avant de déléguer au bridge
     * Cassini. Reproduit ce que {@code ChappeMountPoint.mount(prefix, ...)}
     * fait dans l'intégration Vidocq normale, mais sans dépendre du moteur
     * complet (le harness embarque juste un Chappe Server nu).
     */
    private record ContextStrippingHandler(String prefix, Handler delegate) implements Handler {
        @Override public Response handle(Request request) throws Exception {
            String path = request.path();
            if (path == null) path = "/";
            if (!path.startsWith(prefix)) {
                // Hors du contexte déployé — 404 direct pour éviter de matcher
                // l'arbre Cassini sur des chemins sans rapport.
                return Response.builder()
                        .status(fr.vidocq.chappe.api.StatusCode.NOT_FOUND)
                        .body(fr.vidocq.chappe.api.Body.empty())
                        .build();
            }
            String stripped = path.substring(prefix.length());
            if (stripped.isEmpty()) stripped = "/";
            final String newPath = stripped;
            Request remapped = new Request() {
                @Override public fr.vidocq.chappe.api.HttpMethod method() { return request.method(); }
                @Override public java.net.URI uri() { return request.uri(); }
                @Override public String path() { return newPath; }
                @Override public String query() { return request.query(); }
                @Override public fr.vidocq.chappe.api.HttpVersion version() { return request.version(); }
                @Override public fr.vidocq.chappe.api.Headers headers() { return request.headers(); }
                @Override public fr.vidocq.chappe.api.Body body() { return request.body(); }
                @Override public java.util.Map<String, String> pathParams() { return request.pathParams(); }
                @Override public java.util.Map<String, String> queryParams() { return request.queryParams(); }
                @Override public String contextPath() { return prefix; }
                @Override public String pathInfo() { return newPath; }
            };
            return delegate.handle(remapped);
        }
    }
}
