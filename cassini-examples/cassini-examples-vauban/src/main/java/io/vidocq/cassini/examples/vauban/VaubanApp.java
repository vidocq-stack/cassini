package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import jakarta.ws.rs.core.Application;

import java.io.IOException;
import java.io.InputStream;

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
        Handler statique = staticHandler();
        return composite(statique, cassini);
    }

    /** Handler combiné : {@code /api/*} → Cassini, sinon → statique. */
    private static Handler composite(Handler staticH, Handler cassiniH) {
        return req -> {
            String path = req.path() == null ? "/" : req.path();
            if (path.startsWith(API_PREFIX + "/") || path.equals(API_PREFIX)) {
                String stripped = path.substring(API_PREFIX.length());
                if (stripped.isEmpty()) stripped = "/";
                return cassiniH.handle(stripContext(req, API_PREFIX, stripped));
            }
            return staticH.handle(req);
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

    /** Sert les fichiers depuis {@code classpath:/static/} ; {@code /} → {@code index.html}. */
    private static Handler staticHandler() {
        return req -> {
            String path = req.path();
            if (path == null || "/".equals(path)) path = "/index.html";
            // Sécurité minimale : pas de traversée
            if (path.contains("..")) {
                return Response.builder()
                        .status(StatusCode.BAD_REQUEST).body(Body.empty()).build();
            }
            String resourcePath = "/static" + path;
            try (InputStream in = VaubanApp.class.getResourceAsStream(resourcePath)) {
                if (in == null) {
                    return Response.builder()
                            .status(StatusCode.NOT_FOUND)
                            .header("Content-Type", "text/plain;charset=utf-8")
                            .body(Body.of("Not Found: " + path))
                            .build();
                }
                byte[] bytes = in.readAllBytes();
                return Response.builder()
                        .status(StatusCode.OK)
                        .header("Content-Type", contentType(path))
                        .header("Cache-Control", "no-cache")
                        .body(Body.of(bytes))
                        .build();
            } catch (IOException e) {
                return Response.builder()
                        .status(StatusCode.INTERNAL_SERVER_ERROR)
                        .body(Body.of(e.getMessage() == null ? "I/O error" : e.getMessage()))
                        .build();
            }
        };
    }

    private static String contentType(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        return switch (path.substring(dot + 1).toLowerCase()) {
            case "html", "htm" -> "text/html;charset=utf-8";
            case "css"         -> "text/css;charset=utf-8";
            case "js"          -> "application/javascript;charset=utf-8";
            case "json"        -> "application/json";
            case "svg"         -> "image/svg+xml";
            case "png"         -> "image/png";
            case "ico"         -> "image/x-icon";
            default            -> "application/octet-stream";
        };
    }
}
