package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.StaticFileHandler;
import jakarta.ws.rs.core.Application;

/**
 * Compose le {@link Handler} Chappe servant l'UI statique sur {@code /}
 * et l'API REST Cassini sur {@code /api/*}.
 *
 * <p>Pattern handler composite : selon le {@code path} entrant, on dispatche
 * vers le handler statique (lecture depuis {@code classpath:/static/}) ou
 * vers le {@link ChappeHttpAdapter} qui pointe sur {@link CassiniStack}.</p>
 */
public final class VaubanApp {

    private VaubanApp() {}

    /** Préfixe des routes JAX-RS exposées par Cassini. */
    public static final String API_PREFIX = "/api";

    public static Handler composeHandler() {
        var stack = CassiniStack.builder().application(new Application() {}).build();
        Handler cassini = new ChappeHttpAdapter(stack.adapter());
        // Chappe fournit nativement un StaticFileHandler avec support classpath,
        // index.html par défaut, cache mémoire pour les petites ressources.
        Handler statique = StaticFileHandler.builder()
                .addClasspath("static")
                .cacheInMemory(true)
                .build();
        return composite(statique, cassini);
    }

    /** Handler combiné : {@code /api/*} → Cassini (strip), sinon → statique (pathInfo=path). */
    private static Handler composite(Handler staticH, Handler cassiniH) {
        return req -> {
            String path = req.path() == null ? "/" : req.path();
            if (path.startsWith(API_PREFIX + "/") || path.equals(API_PREFIX)) {
                String stripped = path.substring(API_PREFIX.length());
                if (stripped.isEmpty()) stripped = "/";
                return cassiniH.handle(stripContext(req, API_PREFIX, stripped));
            }
            // StaticFileHandler utilise pathInfo() — on garantit qu'il est rempli.
            // Réécriture / → /index.html (le fallback indexFile interne est cassé
            // quand getResource("static/") retourne l'URL du directory en mode classpath).
            String resolved = (path.endsWith("/")) ? path + "index.html" : path;
            return staticH.handle(stripContext(req, "", resolved));
        };
    }

    /** Wrap la requête en réécrivant {@code path()} et {@code pathInfo()}. */
    private static Request stripContext(Request req, String prefix, String newPath) {
        return new Request() {
            @Override public io.vidocq.chappe.api.HttpMethod method()  { return req.method(); }
            @Override public java.net.URI uri()                         { return req.uri(); }
            @Override public String path()                              { return newPath; }
            @Override public String query()                             { return req.query(); }
            @Override public io.vidocq.chappe.api.HttpVersion version() { return req.version(); }
            @Override public io.vidocq.chappe.api.Headers headers()     { return req.headers(); }
            @Override public Body body()                                { return req.body(); }
            @Override public java.util.Map<String, String> pathParams() { return req.pathParams(); }
            @Override public java.util.Map<String, String> queryParams(){ return req.queryParams(); }
            @Override public String contextPath()                       { return prefix; }
            @Override public String pathInfo()                          { return newPath; }
        };
    }

}
