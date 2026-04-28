package io.vidocq.cassini.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * Routeur Cassini : sélection best-match JAX-RS 4.0 §3.7.2.
 *
 * <p>Au constructeur, les routes sont triées par spécificité décroissante :</p>
 * <ol>
 *   <li>nombre de caractères littéraux (descendant)</li>
 *   <li>nombre total de groupes capturants (descendant)</li>
 *   <li>nombre de groupes à regex par défaut (descendant)</li>
 * </ol>
 *
 * <p>Lors du routing, on parcourt cette liste triée et on retient le
 * premier template qui matche (et dont le verbe HTTP correspond).</p>
 */
public final class UriRouter {

    // §3.7.2 : d'abord la spécificité du @Path racine (classe), puis celle
    // du template combiné. Une sous-ressource @Path("resource/subresource")
    // doit battre une méthode @Path("subresource") sur @Path("resource").
    // En cas d'égalité, les routes directes (non-locatées) passent avant les
    // routes issues d'un sub-resource locator (§3.7.2 Step 2c > Step 2d).
    private static final Comparator<ResourceMethod> BY_SPECIFICITY =
            Comparator.comparingInt((ResourceMethod r) -> r.classPathLiterals()).reversed()
                    .thenComparing(Comparator.comparingInt((ResourceMethod r) -> r.template().literalChars()).reversed())
                    .thenComparing(Comparator.comparingInt((ResourceMethod r) -> r.template().totalCaptures()).reversed())
                    .thenComparing(Comparator.comparingInt(r -> r.template().defaultCaptures()))
                    .thenComparing(r -> r.isLocated() ? 1 : 0); // routes directes avant locatées

    private final List<ResourceMethod> routes;

    public UriRouter(List<ResourceMethod> routes) {
        List<ResourceMethod> sorted = new ArrayList<>(routes);
        sorted.sort(BY_SPECIFICITY);
        this.routes = List.copyOf(sorted);
    }

    public Optional<MatchResult> match(String httpMethod, String path) {
        var all = matchAll(httpMethod, path);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }

    /** Retourne tous les ResourceMethod qui matchent (verb, path).
     *  L'Invoker utilise cette liste pour filtrer par @Consumes (Content-Type
     *  requête) et @Produces (Accept header) §3.7.2. */
    public List<MatchResult> matchAll(String httpMethod, String path) {
        String normalized = normalize(path);
        String p = stripMatrixParams(normalized); // chemin sans matrix params pour le matching
        List<MatchResult> out = new ArrayList<>();
        for (ResourceMethod r : routes) {
            Optional<java.util.Map<String, java.util.List<String>>> params = r.template().match(p);
            if (params.isPresent() && (r.httpMethod().equals("*") || r.httpMethod().equalsIgnoreCase(httpMethod))) {
                // rawParams : valeurs avec matrix params, pour PathSegment injection §3.2.
                Optional<java.util.Map<String, java.util.List<String>>> rawParams = r.template().match(normalized);
                out.add(new MatchResult(r, params.get(), rawParams.orElse(params.get())));
            }
        }
        // §3.3.5 : HEAD → GET fallback (body discard côté Bridge)
        if (out.isEmpty() && "HEAD".equalsIgnoreCase(httpMethod)) {
            for (ResourceMethod r : routes) {
                Optional<java.util.Map<String, java.util.List<String>>> params = r.template().match(p);
                if (params.isPresent() && "GET".equalsIgnoreCase(r.httpMethod())) {
                    Optional<java.util.Map<String, java.util.List<String>>> rawParams = r.template().match(normalized);
                    out.add(new MatchResult(r, params.get(), rawParams.orElse(params.get())));
                }
            }
        }
        return out;
    }

    /** §3.7 : les matrix params (segments contenant ';') ne participent pas
     *  au matching URI → on les strippe avant d'essayer les templates. */
    static String stripMatrixParams(String path) {
        if (path == null || path.indexOf(';') < 0) return path;
        StringBuilder sb = new StringBuilder(path.length());
        int start = 0;
        while (start < path.length()) {
            int slash = path.indexOf('/', start);
            int end = slash < 0 ? path.length() : slash;
            int semi = path.indexOf(';', start);
            int stop = (semi >= 0 && semi < end) ? semi : end;
            sb.append(path, start, stop);
            start = end;
            if (slash >= 0) { sb.append('/'); start = slash + 1; }
        }
        return sb.toString();
    }

    public List<ResourceMethod> routes() {
        return routes;
    }

    public List<String> methodsAllowedFor(String path) {
        String p = stripMatrixParams(normalize(path));
        List<String> verbs = new ArrayList<>();
        for (ResourceMethod r : routes) {
            if (r.template().match(p).isPresent() && !verbs.contains(r.httpMethod())) {
                verbs.add(r.httpMethod());
            }
        }
        return verbs;
    }

    private static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
