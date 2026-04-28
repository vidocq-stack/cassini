package io.vidocq.cassini.internal;

import fr.vidocq.chappe.api.Body;
import fr.vidocq.chappe.api.Handler;
import fr.vidocq.chappe.api.Request;
import fr.vidocq.chappe.api.Response;
import fr.vidocq.chappe.api.StatusCode;
import io.vidocq.vauban.core.context.RequestContext;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * Pont Chappe ↔ runtime JAX-RS Cassini.
 *
 * <p>Pour chaque requête HTTP reçue, on :</p>
 * <ol>
 *   <li>active le scope CDI {@code @RequestScoped} via {@link RequestContext};</li>
 *   <li>normalise le chemin (strip du contextPath déjà fait par Chappe);</li>
 *   <li>matche la {@link ResourceMethod} via {@link UriRouter};</li>
 *   <li>invoque la méthode via {@link Invoker} ;</li>
 *   <li>retourne 404 ou 405 si pas de match.</li>
 * </ol>
 */
public final class CassiniRestBridge implements Handler {

    private static final System.Logger LOG = System.getLogger(CassiniRestBridge.class.getName());

    private final UriRouter router;
    private final Invoker invoker;
    private final RequestContext requestContext;

    public CassiniRestBridge(UriRouter router, Invoker invoker) {
        this(router, invoker, new RequestContext());
    }

    public CassiniRestBridge(UriRouter router, Invoker invoker, RequestContext requestContext) {
        this.router = router;
        this.invoker = invoker;
        this.requestContext = requestContext;
    }

    @Override
    public Response handle(Request request) throws Exception {
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
                requestContext.runInScope(() -> {
                    try { holderPre[0] = invoker.runPreMatching(request); }
                    catch (Exception e) { holderPre[0] = e; }
                });
            } catch (Exception ignored) {}
            if (holderPre[0] instanceof Exception ex) throw ex;
            if (holderPre[0] instanceof Invoker.PreMatchResult pmr) {
                if (pmr.response() != null) return pmr.response();
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
                return invoker.renderThrowable(
                        new jakarta.ws.rs.WebApplicationException(r405), request);
            }
            return invoker.renderThrowable(
                    new jakarta.ws.rs.NotFoundException("No resource matches " + verb + " " + path),
                    request);
        }

        MatchResult result = match.get();
        Object[] holder = new Object[1];
        try {
            requestContext.runInScope(() -> {
                try {
                    holder[0] = invoker.invoke(candidates, request);
                } catch (Exception e) {
                    holder[0] = e;
                }
            });
            if (holder[0] instanceof Exception ex) throw ex;
            Response resp = (Response) holder[0];
            // §3.3.5 : HEAD invoqué sur méthode @GET → on renvoie le header
            // mais on remplace le body par vide (le client n'en a pas besoin
            // pour HEAD).
            if ("HEAD".equalsIgnoreCase(verb) && !"HEAD".equalsIgnoreCase(result.method().httpMethod())) {
                var b = Response.builder().status(resp.status());
                for (var e : resp.headers()) b.header(e.name(), e.value());
                return b.body(fr.vidocq.chappe.api.Body.empty()).build();
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
