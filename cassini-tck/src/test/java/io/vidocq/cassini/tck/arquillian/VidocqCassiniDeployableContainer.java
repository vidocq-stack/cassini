package io.vidocq.cassini.tck.arquillian;

import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.Server;
import fr.vidocq.chappe.api.StatusCode;
import fr.vidocq.chappe.api.Body;
import io.vidocq.cassini.tck.CassiniTestHarness;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.Provider;
import org.jboss.arquillian.container.spi.client.container.DeployableContainer;
import org.jboss.arquillian.container.spi.client.container.DeploymentException;
import org.jboss.arquillian.container.spi.client.container.LifecycleException;
import org.jboss.arquillian.container.spi.client.protocol.ProtocolDescription;
import org.jboss.arquillian.container.spi.client.protocol.metadata.HTTPContext;
import org.jboss.arquillian.container.spi.client.protocol.metadata.ProtocolMetaData;
import org.jboss.arquillian.container.spi.client.protocol.metadata.Servlet;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.Node;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.jboss.shrinkwrap.descriptor.api.Descriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adaptateur Arquillian qui déploie un {@link WebArchive} de test TCK REST
 * sur une instance {@link CassiniTestHarness}.
 *
 * <p>Stratégie : extraire les classes de {@code /WEB-INF/classes/}, filtrer
 * celles annotées {@code @Path} (ou implémentant une {@link Application}
 * déclarant des classes), les instancier par reflection (le classloader
 * de test les voit — le jar runtime TCK est sur le classpath) et les
 * charger dans le harness Cassini. Retourne un {@link ProtocolMetaData}
 * {@code Servlet 3.0} avec le baseUrl pour que le client TCK cible la
 * bonne URL.</p>
 */
public class VidocqCassiniDeployableContainer implements DeployableContainer<VidocqContainerConfiguration> {

    private VidocqContainerConfiguration config;

    /** prefix → bridge handler (sans ContextStrippingHandler — géré par le dispatcher). */
    private final LinkedHashMap<String, Handler> contextHandlers = new LinkedHashMap<>();
    /** archive name → prefix (pour undeploy). */
    private final LinkedHashMap<String, String> archivePrefixes = new LinkedHashMap<>();
    /** archive name → baseUrl (pour ProtocolMetaData). */
    private final LinkedHashMap<String, String> archiveBaseUrls = new LinkedHashMap<>();

    /** Serveur partagé unique sur le port 8080. */
    private Server sharedServer;
    private int sharedPort;

    @Override public Class<VidocqContainerConfiguration> getConfigurationClass() {
        return VidocqContainerConfiguration.class;
    }

    @Override public void setup(VidocqContainerConfiguration cfg) { this.config = cfg; }

    @Override public ProtocolDescription getDefaultProtocol() {
        return new ProtocolDescription("Servlet 3.0");
    }

    @Override public void start() throws LifecycleException {}

    @Override public void stop() throws LifecycleException {
        stopSharedServer();
        contextHandlers.clear();
        archivePrefixes.clear();
        archiveBaseUrls.clear();
    }

    private void stopSharedServer() {
        if (sharedServer != null) {
            try { sharedServer.stop(); } catch (RuntimeException ignored) {}
            sharedServer = null;
        }
    }

    /** Redémarre le serveur partagé avec tous les contextes actuellement enregistrés. */
    private void restartSharedServer(int port) {
        stopSharedServer();
        if (contextHandlers.isEmpty()) return;
        // Snapshot immutable pour le handler
        var snapshot = new LinkedHashMap<>(contextHandlers);
        Handler dispatcher = request -> {
            String path = request.path() == null ? "/" : request.path();
            // Longest-prefix match
            String bestPrefix = null;
            Handler best = null;
            for (var e : snapshot.entrySet()) {
                String pfx = e.getKey();
                if (path.startsWith(pfx) && (bestPrefix == null || pfx.length() > bestPrefix.length())) {
                    bestPrefix = pfx;
                    best = e.getValue();
                }
            }
            if (best == null) {
                return Response.builder().status(StatusCode.NOT_FOUND).body(Body.empty()).build();
            }
            final String stripped = path.substring(bestPrefix.length());
            final String newPath = stripped.isEmpty() ? "/" : stripped;
            final String finalPrefix = bestPrefix;
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
                @Override public String contextPath() { return finalPrefix; }
                @Override public String pathInfo() { return newPath; }
                @Override public boolean isSecure() { return request.isSecure(); }
            };
            return best.handle(remapped);
        };
        RuntimeException last = null;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                sharedServer = Server.builder().host("127.0.0.1").port(port).handler(dispatcher).build();
                sharedServer.start();
                sharedPort = port;
                return;
            } catch (RuntimeException e) {
                last = e;
                try { Thread.sleep(100); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        throw last;
    }

    @Override public ProtocolMetaData deploy(Archive<?> archive) throws DeploymentException {
        if (!(archive instanceof WebArchive war)) {
            throw new DeploymentException("only WebArchive supported, got " + archive.getClass());
        }

        CassiniTestHarness.Builder builder = CassiniTestHarness.builder();
        String archiveName = war.getName();
        String ctxName = archiveName == null ? "" : archiveName;
        if (ctxName.endsWith(".war")) ctxName = ctxName.substring(0, ctxName.length() - 4);
        String prefix = ctxName.isEmpty() ? "" : "/" + ctxName;
        if (!prefix.isEmpty()) builder.contextPath(prefix);

        String portProp = System.getProperty("webServerPort", "8080");
        int port = 8080;
        try { port = Integer.parseInt(portProp); } catch (NumberFormatException ignored) {}

        var cl = Thread.currentThread().getContextClassLoader();
        List<String> registered = new java.util.ArrayList<>();
        List<String> providers = new java.util.ArrayList<>();

        // Étape 1 : collecter toutes les classes du WAR.
        List<Class<?>> classes = new java.util.ArrayList<>();
        for (Node node : war.getContent().values()) {
            String path = node.getPath().get();
            if (!path.endsWith(".class")) continue;
            if (!path.startsWith("/WEB-INF/classes/")) continue;
            String className = path.substring("/WEB-INF/classes/".length(),
                    path.length() - ".class".length()).replace('/', '.');
            try { classes.add(Class.forName(className, true, cl)); }
            catch (Throwable t) { /* ignored */ }
        }

        // Étape 2 : Application sub-class → getClasses()/getSingletons().
        Application appInstance = null;
        for (Class<?> c : classes) {
            if (Application.class.isAssignableFrom(c) && !Application.class.equals(c)) {
                try { appInstance = (Application) c.getDeclaredConstructor().newInstance(); break; }
                catch (ReflectiveOperationException ignored) {}
            }
        }
        java.util.Set<Class<?>> appClasses = appInstance == null ? java.util.Set.of() : appInstance.getClasses();
        java.util.Set<Object> appSingletons = appInstance == null ? java.util.Set.of() : appInstance.getSingletons();
        boolean appFiltersResources = appInstance != null
                && (!appClasses.isEmpty() || !appSingletons.isEmpty());

        // Étape 3 : enregistrer resources + providers.
        if (appFiltersResources) {
            for (Class<?> c : appClasses) registerDiscovered(c, builder, registered, providers);
            for (Object s : appSingletons) registerSingleton(s, builder, registered, providers);
        } else {
            for (Class<?> c : classes) registerDiscovered(c, builder, registered, providers);
        }
        // §10 : ServiceLoader (META-INF/services/) — Feature et DynamicFeature
        // peuvent être déclarés ici en plus de Application.getClasses().
        java.util.Set<Class<?>> features = new java.util.LinkedHashSet<>();
        for (Class<?> c : (appFiltersResources ? appClasses : classes)) {
            if (jakarta.ws.rs.core.Feature.class.isAssignableFrom(c)) features.add(c);
        }
        for (String spi : new String[]{
                "jakarta.ws.rs.core.Feature",
                "jakarta.ws.rs.container.DynamicFeature"}) {
            for (Node node : war.getContent().values()) {
                String np = node.getPath().get();
                if (!np.equals("/WEB-INF/classes/META-INF/services/" + spi)) continue;
                try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(
                        node.getAsset().openStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String s = line.trim();
                        if (s.isEmpty() || s.startsWith("#")) continue;
                        try { features.add(Class.forName(s, true, cl)); }
                        catch (Throwable ignored) {}
                    }
                } catch (java.io.IOException ignored) {}
            }
        }
        // §10 : invoquer Feature.configure() — register() délègue au builder.
        for (Class<?> c : features) {
            if (!c.isInterface() && !java.lang.reflect.Modifier.isAbstract(c.getModifiers())
                    && jakarta.ws.rs.core.Feature.class.isAssignableFrom(c)) {
                try {
                    jakarta.ws.rs.core.Feature feature =
                            (jakarta.ws.rs.core.Feature) c.getDeclaredConstructor().newInstance();
                    jakarta.ws.rs.core.FeatureContext fctx = new CassiniFeatureContext(builder, registered, providers);
                    feature.configure(fctx);
                    providers.add(c.getSimpleName());
                } catch (ReflectiveOperationException ignored) {}
            } else if (jakarta.ws.rs.container.DynamicFeature.class.isAssignableFrom(c)) {
                // DynamicFeature : enregistre comme provider — le bridge
                // l'appelle par méthode resource au scan-time (M2g).
                try {
                    Object instance = c.getDeclaredConstructor().newInstance();
                    builder.provider(instance);
                    providers.add(c.getSimpleName());
                } catch (ReflectiveOperationException ignored) {}
            }
        }
        if (appInstance != null) {
            builder.application(appInstance);
            // §11.2.1 : @ApplicationPath sur la sous-classe Application
            // s'ajoute au contextPath de l'archive comme servlet path.
            jakarta.ws.rs.ApplicationPath appPath =
                    appInstance.getClass().getAnnotation(jakarta.ws.rs.ApplicationPath.class);
            if (appPath != null && !appPath.value().isEmpty()) {
                // §2.3.2 : @ApplicationPath value est URI-encodé, on décode
                // pour matcher la forme transportée par le client (ex. %21 → !).
                String ap = java.net.URLDecoder.decode(appPath.value(),
                        java.nio.charset.StandardCharsets.UTF_8);
                if (!ap.startsWith("/")) ap = "/" + ap;
                if (ap.endsWith("/") && ap.length() > 1) ap = ap.substring(0, ap.length() - 1);
                builder.contextPath(prefix + ap);
            }
        }

        // Construire le bridge sans démarrer de serveur, puis enregistrer dans
        // le dispatcher partagé. Le serveur partagé est (re)démarré pour prendre
        // en compte tous les contextes connus.
        CassiniTestHarness.Builder.BuiltHandler bh = builder.buildHandler();
        String actualPrefix = bh.prefix();
        // §6.1 BASIC : si web.xml déclare une security-constraint avec
        // <auth-method>BASIC</auth-method>, on wrappe le bridge avec un
        // handler qui valide Authorization Basic et pose un AuthInfo.
        fr.vidocq.chappe.api.Handler bridgeOrAuth = bh.bridgeHandler();
        String authPattern = parseBasicAuthPattern(war, actualPrefix);
        if (authPattern != null) {
            System.err.println("[VidocqCassiniTCK] BASIC auth wrap pattern=" + authPattern);
            bridgeOrAuth = new BasicAuthHandler(bridgeOrAuth, authPattern);
        }
        contextHandlers.put(actualPrefix, bridgeOrAuth);
        archivePrefixes.put(archive.getName(), actualPrefix);

        String baseUrl = "http://" + config.getHost() + ":" + port + actualPrefix;
        archiveBaseUrls.put(archive.getName(), baseUrl);

        restartSharedServer(port);

        System.err.println("[VidocqCassiniTCK] deploy archive=" + war.getName()
                + " host=" + config.getHost() + " port=" + port
                + " resources=" + registered + " providers=" + providers
                + " baseUrl=" + baseUrl);

        ProtocolMetaData pmd = new ProtocolMetaData();
        HTTPContext ctx = new HTTPContext(config.getHost(), port);
        String contextRoot = actualPrefix.isEmpty() ? "/" : actualPrefix;
        ctx.add(new Servlet(registered.isEmpty() ? "_cassini" : registered.get(0), contextRoot));
        pmd.addContext(ctx);
        return pmd;
    }

    @Override public void undeploy(Archive<?> archive) {
        String prefix = archivePrefixes.remove(archive.getName());
        if (prefix != null) contextHandlers.remove(prefix);
        archiveBaseUrls.remove(archive.getName());
        if (contextHandlers.isEmpty()) {
            stopSharedServer();
        } else {
            restartSharedServer(sharedPort);
        }
    }

    @Override public void deploy(Descriptor descriptor) {}
    @Override public void undeploy(Descriptor descriptor) {}

    private static void registerDiscovered(Class<?> cls, CassiniTestHarness.Builder b,
                                           List<String> registered, List<String> providers) {
        if (cls.isAnnotationPresent(Path.class)) {
            try { b.resourceClass(cls); registered.add(cls.getSimpleName()); }
            catch (RuntimeException ignored) {}
        } else if (cls.isAnnotationPresent(Provider.class) || isProviderClass(cls)) {
            // §9.4 : Application.getClasses() peut renvoyer une classe qui
            // implémente un type provider standard sans porter @Provider.
            // §9.2 : un provider singleton peut avoir des constructeurs
            // @Context-only — on choisit le plus large public et instancie
            // avec null aux args. Les fields @Context seront ré-injectés
            // per-request par injectProviderContexts().
            Object instance = instantiateProvider(cls);
            if (instance != null) {
                b.provider(instance);
                providers.add(cls.getSimpleName());
            }
        }
    }

    private static Object instantiateProvider(Class<?> cls) {
        try {
            return cls.getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException ignored) {}
        // Pas de constructeur no-arg : §4.5 sélectionne le ctor public avec
        // le plus d'arguments dont chacun a un type @Context résolvable. Les
        // valeurs sont des proxies dynamiques qui délèguent à la requête
        // courante (ThreadLocal Invoker.CURRENT_REQUEST + CURRENT_MATCH).
        java.lang.reflect.Constructor<?> best = null;
        int bestParams = -1;
        for (var c : cls.getConstructors()) {
            boolean ok = true;
            for (var pp : c.getParameters()) {
                if (!isResolvableContext(pp.getType())) { ok = false; break; }
            }
            if (ok && c.getParameterCount() > bestParams) {
                best = c;
                bestParams = c.getParameterCount();
            }
        }
        if (best == null) return null;
        try {
            Object[] args = new Object[best.getParameterCount()];
            for (int i = 0; i < args.length; i++) {
                args[i] = ContextProxies.proxy(best.getParameterTypes()[i]);
            }
            return best.newInstance(args);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Parse WEB-INF/web.xml du WAR : si une security-constraint avec
     * url-pattern et auth-method=BASIC est trouvée, retourne un regex
     * matchant les paths protégés (le contextPath du WAR ayant déjà été
     * strippé par le dispatcher partagé en amont — donc on ne préfixe
     * PAS la regex), sinon null.
     */
    private static String parseBasicAuthPattern(WebArchive war, String prefix) {
        Node webXml = war.get("/WEB-INF/web.xml");
        if (webXml == null || webXml.getAsset() == null) {
            webXml = war.get("WEB-INF/web.xml");
        }
        if (webXml == null || webXml.getAsset() == null) return null;
        String content;
        try (var in = webXml.getAsset().openStream()) {
            content = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) { return null; }
        if (!content.contains("<auth-method>BASIC</auth-method>")) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "<security-constraint>[\\s\\S]*?</security-constraint>").matcher(content);
        java.util.List<String> patterns = new java.util.ArrayList<>();
        while (m.find()) {
            java.util.regex.Matcher up = java.util.regex.Pattern.compile(
                    "<url-pattern>([^<]+)</url-pattern>").matcher(m.group());
            while (up.find()) patterns.add(up.group(1).trim());
        }
        if (patterns.isEmpty()) return null;
        StringBuilder sb = new StringBuilder("^(");
        for (int i = 0; i < patterns.size(); i++) {
            if (i > 0) sb.append("|");
            String p = patterns.get(i);
            String rx = p.replace(".", "\\.").replace("*", ".*");
            sb.append(rx);
        }
        sb.append(")");
        return sb.toString();
    }

    private static boolean isResolvableContext(Class<?> type) {
        return type == jakarta.ws.rs.core.HttpHeaders.class
                || type == jakarta.ws.rs.core.UriInfo.class
                || type == jakarta.ws.rs.core.Application.class
                || type == jakarta.ws.rs.core.Request.class
                || type == jakarta.ws.rs.core.SecurityContext.class
                || type == jakarta.ws.rs.ext.Providers.class
                || type == jakarta.ws.rs.container.ResourceInfo.class
                || type == jakarta.ws.rs.container.ResourceContext.class
                || type == jakarta.ws.rs.core.Configuration.class;
    }

    private static boolean isProviderClass(Class<?> cls) {
        return jakarta.ws.rs.ext.MessageBodyReader.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.MessageBodyWriter.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ExceptionMapper.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ContextResolver.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ReaderInterceptor.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.WriterInterceptor.class.isAssignableFrom(cls)
                || jakarta.ws.rs.ext.ParamConverterProvider.class.isAssignableFrom(cls)
                || jakarta.ws.rs.container.ContainerRequestFilter.class.isAssignableFrom(cls)
                || jakarta.ws.rs.container.ContainerResponseFilter.class.isAssignableFrom(cls)
                || jakarta.ws.rs.container.DynamicFeature.class.isAssignableFrom(cls)
                || jakarta.ws.rs.core.Feature.class.isAssignableFrom(cls);
    }

    private static void registerSingleton(Object instance, CassiniTestHarness.Builder b,
                                          List<String> registered, List<String> providers) {
        Class<?> cls = instance.getClass();
        if (cls.isAnnotationPresent(Path.class)) {
            b.resource(instance); registered.add(cls.getSimpleName());
            return;
        }
        // §9.4 : un singleton peut être un provider sans annotation @Provider
        // s'il implémente un des types standards (MBR, MBW, ExceptionMapper,
        // ContextResolver, Feature, DynamicFeature, Filter, Interceptor).
        if (cls.isAnnotationPresent(Provider.class) || isProviderType(instance)) {
            b.provider(instance); providers.add(cls.getSimpleName());
            return;
        }
        // Singleton utilitaire sans type reconnu : on l'expose comme ressource.
        b.resource(instance); registered.add(cls.getSimpleName());
    }

    private static boolean isProviderType(Object instance) {
        return instance instanceof jakarta.ws.rs.ext.MessageBodyReader<?>
                || instance instanceof jakarta.ws.rs.ext.MessageBodyWriter<?>
                || instance instanceof jakarta.ws.rs.ext.ExceptionMapper<?>
                || instance instanceof jakarta.ws.rs.ext.ContextResolver<?>
                || instance instanceof jakarta.ws.rs.ext.ReaderInterceptor
                || instance instanceof jakarta.ws.rs.ext.WriterInterceptor
                || instance instanceof jakarta.ws.rs.ext.ParamConverterProvider
                || instance instanceof jakarta.ws.rs.container.ContainerRequestFilter
                || instance instanceof jakarta.ws.rs.container.ContainerResponseFilter
                || instance instanceof jakarta.ws.rs.container.DynamicFeature
                || instance instanceof jakarta.ws.rs.core.Feature;
    }

    /** §10 FeatureContext minimal : .register() délègue au harness builder. */
    private static final class CassiniFeatureContext implements jakarta.ws.rs.core.FeatureContext {
        private final CassiniTestHarness.Builder builder;
        private final List<String> registered;
        private final List<String> providers;
        private final java.util.Map<String, Object> properties = new java.util.LinkedHashMap<>();

        CassiniFeatureContext(CassiniTestHarness.Builder b, List<String> r, List<String> p) {
            this.builder = b;
            this.registered = r;
            this.providers = p;
        }

        @Override public jakarta.ws.rs.core.Configuration getConfiguration() {
            return new jakarta.ws.rs.core.Configuration() {
                @Override public jakarta.ws.rs.RuntimeType getRuntimeType() { return jakarta.ws.rs.RuntimeType.SERVER; }
                @Override public java.util.Map<String, Object> getProperties() { return properties; }
                @Override public Object getProperty(String name) { return properties.get(name); }
                @Override public java.util.Collection<String> getPropertyNames() { return properties.keySet(); }
                @Override public boolean isEnabled(jakarta.ws.rs.core.Feature f) { return false; }
                @Override public boolean isEnabled(Class<? extends jakarta.ws.rs.core.Feature> fc) { return false; }
                @Override public boolean isRegistered(Object component) { return false; }
                @Override public boolean isRegistered(Class<?> componentClass) { return false; }
                @Override public java.util.Map<Class<?>, Integer> getContracts(Class<?> c) { return java.util.Map.of(); }
                @Override public java.util.Set<Class<?>> getClasses() { return java.util.Set.of(); }
                @Override public java.util.Set<Object> getInstances() { return java.util.Set.of(); }
            };
        }
        @Override public jakarta.ws.rs.core.FeatureContext property(String name, Object value) {
            properties.put(name, value); return this;
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Class<?> componentClass) {
            registerDiscovered(componentClass, builder, registered, providers);
            return this;
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Class<?> componentClass, int priority) {
            return register(componentClass);
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Class<?> componentClass, Class<?>... contracts) {
            return register(componentClass);
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Class<?> componentClass, java.util.Map<Class<?>, Integer> contracts) {
            return register(componentClass);
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Object component) {
            registerSingleton(component, builder, registered, providers);
            return this;
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Object component, int priority) {
            return register(component);
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Object component, Class<?>... contracts) {
            return register(component);
        }
        @Override public jakarta.ws.rs.core.FeatureContext register(Object component, java.util.Map<Class<?>, Integer> contracts) {
            return register(component);
        }
    }
}
