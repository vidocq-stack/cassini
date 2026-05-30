package io.vidocq.cassini.examples.vauban;

import io.vidocq.cassini.chappe.ChappeHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.StaticFileHandler;
import jakarta.ws.rs.core.Application;

/**
 * Composes the Chappe {@link Handler} serving the static UI on {@code /} and
 * the Cassini REST API on {@code /api/*}.
 *
 * <p>Composite handler pattern: depending on the incoming {@code path}, dispatches
 * to the static handler (reading from {@code classpath:/static/}) or to the
 * {@link ChappeHttpAdapter} which points to {@link CassiniStack}.</p>
 */
public final class VaubanApp {

    private VaubanApp() {}

    /** Prefix of the JAX-RS routes exposed by Cassini. */
    public static final String API_PREFIX = "/api";

    public static Handler composeHandler() {
        var stack = CassiniStack.builder().application(new Application() {}).build();
        Handler cassini = new ChappeHttpAdapter(stack.adapter());
        // Chappe natively provides a StaticFileHandler with classpath support,
        // default index.html, and an in-memory cache for small resources.
        Handler statique = StaticFileHandler.builder()
                .addClasspath("static")
                .cacheInMemory(true)
                .build();
        return composite(statique, cassini);
    }

    /** Combined handler: {@code /api/*} to Cassini (strip), otherwise to static (pathInfo=path). */
    private static Handler composite(Handler staticH, Handler cassiniH) {
        return req -> {
            String path = req.path() == null ? "/" : req.path();
            if (path.startsWith(API_PREFIX + "/") || path.equals(API_PREFIX)) {
                String stripped = path.substring(API_PREFIX.length());
                if (stripped.isEmpty()) stripped = "/";
                return cassiniH.handle(stripContext(req, API_PREFIX, stripped));
            }
            // StaticFileHandler uses pathInfo() — we ensure it is filled in.
            // Rewrite / to /index.html (the internal indexFile fallback is broken
            // when getResource("static/") returns the directory URL in classpath mode).
            String resolved = (path.endsWith("/")) ? path + "index.html" : path;
            return staticH.handle(stripContext(req, "", resolved));
        };
    }

    /** Wraps the request, rewriting {@code path()} and {@code pathInfo()}. */
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
