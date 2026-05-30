package io.vidocq.cassini.internal;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * Cassini router: best-match selection per JAX-RS 4.0 §3.7.2.
 *
 * <p>At construction, routes are sorted by decreasing specificity:</p>
 * <ol>
 *   <li>number of literal characters (descending)</li>
 *   <li>total number of capturing groups (descending)</li>
 *   <li>number of default-regex groups (descending)</li>
 * </ol>
 *
 * <p>During routing, this sorted list is walked and the first matching
 * template (with matching HTTP verb) is returned.</p>
 */
public final class UriRouter {

    // §3.7.2: first the specificity of the root @Path (class), then that of
    // the combined template. A sub-resource @Path("resource/subresource")
    // must beat a method @Path("subresource") on @Path("resource").
    // On ties, direct (non-located) routes come before routes from a
    // sub-resource locator (§3.7.2 Step 2c > Step 2d).
    private static final Comparator<ResourceMethod> BY_SPECIFICITY =
            Comparator.comparingInt((ResourceMethod r) -> r.classPathLiterals()).reversed()
                    .thenComparing(Comparator.comparingInt((ResourceMethod r) -> r.template().literalChars()).reversed())
                    .thenComparing(Comparator.comparingInt((ResourceMethod r) -> r.template().totalCaptures()).reversed())
                    .thenComparing(Comparator.comparingInt(r -> r.template().defaultCaptures()))
                    .thenComparing(r -> r.isLocated() ? 1 : 0); // direct routes before located ones

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

    /** Returns all ResourceMethods that match (verb, path).
     *  The Invoker uses this list to filter by @Consumes (request Content-Type)
     *  and @Produces (Accept header) per §3.7.2. */
    public List<MatchResult> matchAll(String httpMethod, String path) {
        String normalized = normalize(path);
        String p = stripMatrixParams(normalized); // path without matrix params for matching
        List<MatchResult> out = new ArrayList<>();
        for (ResourceMethod r : routes) {
            Optional<java.util.Map<String, java.util.List<String>>> params = r.template().match(p);
            if (params.isPresent() && (r.httpMethod().equals("*") || r.httpMethod().equalsIgnoreCase(httpMethod))) {
                // rawParams: values with matrix params, for PathSegment injection §3.2.
                Optional<java.util.Map<String, java.util.List<String>>> rawParams = r.template().match(normalized);
                out.add(new MatchResult(r, params.get(), rawParams.orElse(params.get())));
            }
        }
        // §3.3.5: HEAD → GET fallback (body discarded by the Bridge)
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

    /** §3.7: matrix params (segments containing ';') do not participate in
     *  URI matching → strip them before trying templates. */
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
