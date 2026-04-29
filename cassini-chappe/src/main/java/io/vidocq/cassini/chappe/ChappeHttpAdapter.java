package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MatchResult;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Adapter HTTP Chappe → Cassini.
 *
 * <p>Pour chaque requête HTTP reçue par Chappe :</p>
 * <ol>
 *   <li>convertit {@link Request}/{@link Response} Chappe ↔ {@link io.vidocq.cassini.spi.http.CassiniHttpExchange} ;</li>
 *   <li>exécute les pre-matching filters via {@link Invoker} ;</li>
 *   <li>matche la route via {@link UriRouter} ;</li>
 *   <li>invoque la méthode resource via {@link Invoker} ;</li>
 *   <li>copie le {@link CassiniHttpResponse} produit dans la {@link Response} Chappe.</li>
 * </ol>
 */
public final class ChappeHttpAdapter implements Handler {

    private static final System.Logger LOG = System.getLogger(ChappeHttpAdapter.class.getName());

    /** Hook lifecycle : entre/sort du scope CDI ({@code @RequestScoped}) si fourni. */
    @FunctionalInterface
    public interface Scoped {
        void runInScope(Runnable action);
        Scoped IDENTITY = Runnable::run;
    }

    private final UriRouter router;
    private final Invoker invoker;
    private final Scoped scoped;

    public ChappeHttpAdapter(UriRouter router, Invoker invoker) {
        this(router, invoker, Scoped.IDENTITY);
    }

    public ChappeHttpAdapter(UriRouter router, Invoker invoker, Scoped scoped) {
        this.router = router;
        this.invoker = invoker;
        this.scoped = scoped == null ? Scoped.IDENTITY : scoped;
    }

    /** Convertit la {@link CassiniHttpResponse} produite par Cassini en {@link Response} Chappe. */
    private static Response toChappe(CassiniHttpResponse out) {
        var b = Response.builder().status(StatusCode.of(out.status()));
        for (var e : out.headers().entrySet()) {
            for (String v : e.getValue()) b.header(e.getKey(), v);
        }
        b.body(out.body() == null || out.body().length == 0 ? Body.empty() : Body.of(out.body()));
        return b.build();
    }

    /**
     * Chaque requête est traitée sur un virtual thread dédié (M2h).
     * Cela garantit : (1) isolation des ScopedValues request-scope,
     * (2) aucun starvation de platform thread si la resource method bloque
     * sur I/O ou attend un CompletionStage.
     */
    @Override
    public Response handle(Request request) throws Exception {
        var future = new CompletableFuture<Response>();
        Thread.ofVirtual().name("cassini-req").start(() -> {
            try {
                future.complete(dispatchOnCurrentThread(request));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get();
        } catch (ExecutionException ee) {
            Throwable cause = ee.getCause();
            if (cause instanceof Exception ex) throw ex;
            throw new RuntimeException(cause);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted waiting for virtual thread", ie);
        }
    }

    private Response dispatchOnCurrentThread(Request request) throws Exception {
        ChappeHttpExchange exchange = new ChappeHttpExchange(request);
        String verb = request.method().name();
        String path = normalize(request.pathInfo());
        // §6.6.1 : pre-matching filters s'exécutent AVANT le routing → si
        // l'un d'eux abortWith(), retourner directement sans tenter de
        // matcher une route (sinon /chemin-inexistant tombe en 404 même
        // si un filter aurait short-circuité).
        var preMatchFilters = invoker.filters().preMatching();
        if (!preMatchFilters.isEmpty()) {
            Object[] holderPre = new Object[1];
            try {
                scoped.runInScope(() -> {
                    try { holderPre[0] = invoker.runPreMatching(exchange); }
                    catch (Exception e) { holderPre[0] = e; }
                });
            } catch (Exception ignored) {}
            if (holderPre[0] instanceof Exception ex) throw ex;
            if (holderPre[0] instanceof Invoker.PreMatchResult pmr) {
                if (pmr.response() != null) return toChappe(pmr.response());
                // §6.6.1 : si un pre-matching filter a appelé setMethod /
                // setRequestUri, on relance le routing sur les valeurs mutées.
                if (pmr.ctx() != null) {
                    String mutMethod = pmr.ctx().currentMethod();
                    if (mutMethod != null) verb = mutMethod;
                    java.net.URI mutUri = pmr.ctx().currentRequestUri();
                    if (mutUri != null) {
                        String mutPath = mutUri.getRawPath();
                        if (mutPath == null) mutPath = mutUri.getPath();
                        if (mutPath == null) mutPath = "/";
                        // Strip baseUri prefix : §6.6.1 setRequestUri(absolute)
                        // pointe vers la ressource cible — on réutilise le path
                        // après prefix-strip déjà effectué (ContextStrippingHandler).
                        path = normalize(stripBase(mutPath, request));
                    }
                }
            }
        }
        List<MatchResult> candidates = router.matchAll(verb, path);
        Optional<MatchResult> match = candidates.isEmpty() ? Optional.empty() : Optional.of(candidates.get(0));

        if (match.isEmpty()) {
            List<String> allowed = router.methodsAllowedFor(path);
            if (!allowed.isEmpty()) {
                // §3.3.5 : OPTIONS sans handler explicite → 200 + Allow header
                if ("OPTIONS".equalsIgnoreCase(verb)) {
                    if (!allowed.contains("OPTIONS")) allowed.add("OPTIONS");
                    if (allowed.contains("GET") && !allowed.contains("HEAD")) allowed.add("HEAD");
                    return Response.builder()
                            .status(StatusCode.OK)
                            .header("Allow", String.join(", ", allowed))
                            .header("Content-Type", "application/vnd.sun.wadl+xml")
                            .body(Body.empty())
                            .build();
                }
                // §3.7.2 : 405 doit passer via WebApplicationException pour
                // que les ExceptionMapper<WebApplicationException> de l'app
                // puissent l'intercepter.
                var r405 = jakarta.ws.rs.core.Response.status(405)
                        .header("Allow", String.join(", ", allowed)).build();
                return toChappe(invoker.renderThrowable(
                        new jakarta.ws.rs.WebApplicationException(r405), exchange));
            }
            return toChappe(invoker.renderThrowable(
                    new jakarta.ws.rs.NotFoundException("No resource matches " + verb + " " + path),
                    exchange));
        }

        MatchResult result = match.get();
        Object[] holder = new Object[1];
        try {
            scoped.runInScope(() -> {
                try {
                    holder[0] = invoker.invoke(candidates, exchange);
                } catch (Exception e) {
                    holder[0] = e;
                }
            });
            if (holder[0] instanceof Exception ex) throw ex;
            CassiniHttpResponse out = (CassiniHttpResponse) holder[0];
            Response resp = toChappe(out);
            // §3.3.5 : HEAD invoqué sur méthode @GET → on renvoie le header
            // mais on remplace le body par vide (le client n'en a pas besoin
            // pour HEAD).
            if ("HEAD".equalsIgnoreCase(verb) && !"HEAD".equalsIgnoreCase(result.method().httpMethod())) {
                var b = Response.builder().status(resp.status());
                for (var e : resp.headers()) b.header(e.name(), e.value());
                return b.body(Body.empty()).build();
            }
            return resp;
        } catch (Exception e) {
            LOG.log(System.Logger.Level.ERROR, "Cassini handler error on " + verb + " " + path, e);
            return Response.builder()
                    .status(StatusCode.INTERNAL_SERVER_ERROR)
                    .header("Content-Type", "text/plain;charset=utf-8")
                    .body(Body.of(e.getMessage() == null ? "Internal Server Error"
                            : e.getMessage()))
                    .build();
        }
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        return raw;
    }

    /** Strip le contextPath de la requête originale du chemin absolu fourni
     *  par {@code setRequestUri()}, afin de re-router sur les @Path. */
    private static String stripBase(String absPath, Request originalRequest) {
        String ctx = originalRequest.contextPath();
        if (ctx != null && !ctx.isEmpty() && !"/".equals(ctx) && absPath.startsWith(ctx)) {
            return absPath.substring(ctx.length());
        }
        return absPath;
    }
}
