package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.PathSegment;
import io.vidocq.cassini.internal.runtime.CassiniUriBuilder;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.UriInfo;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * {@link UriInfo} implementation backed by a {@link CassiniHttpExchange} plus the
 * path parameters captured by the router.
 */
public final class CassiniUriInfo implements UriInfo {

    private final CassiniHttpExchange exchange;
    private final String contextPath;
    private final Map<String, List<String>> pathParams;

    public CassiniUriInfo(CassiniHttpExchange exchange, String contextPath, Map<String, List<String>> pathParams) {
        this(exchange, contextPath, pathParams, null);
    }

    private final String matchedTemplate;
    public CassiniUriInfo(CassiniHttpExchange exchange, String contextPath, Map<String, List<String>> pathParams,
                          String matchedTemplate) {
        this.exchange = exchange;
        this.contextPath = contextPath == null ? "" : contextPath;
        this.pathParams = pathParams == null ? Map.of() : pathParams;
        this.matchedTemplate = matchedTemplate;
    }

    /** Path après contextPath (équivalent du {@code pathInfo} servlet). */
    private String pathInfo() {
        String p = exchange.requestUri().getRawPath();
        if (p == null) return "";
        if (!contextPath.isEmpty() && !"/".equals(contextPath) && p.startsWith(contextPath)) {
            p = p.substring(contextPath.length());
        }
        return p;
    }

    @Override public String getPath() { return getPath(true); }

    @Override public String getPath(boolean decode) {
        String p = pathInfo();
        if (p == null || p.isEmpty()) return "";
        String out = p.startsWith("/") ? p.substring(1) : p;
        if (decode) {
            try { out = java.net.URLDecoder.decode(out, java.nio.charset.StandardCharsets.UTF_8); }
            catch (Exception ignored) {}
        }
        return out;
    }

    @Override public List<PathSegment> getPathSegments() { return getPathSegments(true); }

    @Override public List<PathSegment> getPathSegments(boolean decode) {
        List<PathSegment> segs = new ArrayList<>();
        for (String s : getPath(decode).split("/")) {
            if (s.isEmpty()) continue;
            segs.add(new SimpleSegment(s));
        }
        return Collections.unmodifiableList(segs);
    }

    @Override public URI getRequestUri() {
        URI u = exchange.requestUri();
        if (u != null && u.isAbsolute()) return u;
        return resolveAbsolute(u == null ? null : u.toString());
    }

    private URI resolveAbsolute(String pathAndQuery) {
        try {
            String scheme = exchange.isSecure() ? "https" : "http";
            String host = exchange.firstHeader("Host");
            if (host == null || host.isEmpty()) host = "127.0.0.1";
            String pq = pathAndQuery == null ? "/" : pathAndQuery;
            if (!pq.startsWith("/")) pq = "/" + pq;
            return new URI(scheme + "://" + host + pq);
        } catch (Exception e) { return exchange.requestUri(); }
    }

    @Override public UriBuilder getRequestUriBuilder() {
        return CassiniUriBuilder.fromUri(getRequestUri());
    }

    @Override public URI getAbsolutePath() {
        URI req = getRequestUri();
        try {
            return new URI(req.getScheme(), req.getAuthority(), req.getPath(), null, null);
        } catch (Exception e) { return req; }
    }

    @Override public UriBuilder getAbsolutePathBuilder() {
        return CassiniUriBuilder.fromUri(getAbsolutePath());
    }

    @Override public URI getBaseUri() {
        URI req = getRequestUri();
        try {
            String base = contextPath.isEmpty() || "/".equals(contextPath) ? "/" : contextPath + "/";
            return new URI(req.getScheme(), req.getAuthority(), base, null, null);
        } catch (Exception e) { return req; }
    }

    @Override public UriBuilder getBaseUriBuilder() {
        return CassiniUriBuilder.fromUri(getBaseUri());
    }

    @Override public MultivaluedMap<String, String> getPathParameters() { return getPathParameters(true); }

    @Override public MultivaluedMap<String, String> getPathParameters(boolean decode) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        pathParams.forEach((k, values) -> {
            for (String v : values) {
                String val = v;
                if (decode && v != null && v.indexOf('%') >= 0) {
                    try { val = java.net.URLDecoder.decode(v.replace("+", "%2B"),
                            java.nio.charset.StandardCharsets.UTF_8); }
                    catch (Exception ignored) {}
                }
                m.add(k, val);
            }
        });
        return m;
    }

    @Override public MultivaluedMap<String, String> getQueryParameters() { return getQueryParameters(true); }

    @Override public MultivaluedMap<String, String> getQueryParameters(boolean decode) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        // In decode=false mode we re-read from getRawQuery(); otherwise we use
        // the SPI helper that decodes.
        if (!decode) {
            String rawQuery = exchange.requestUri().getRawQuery();
            if (rawQuery == null || rawQuery.isEmpty()) return m;
            for (String pair : rawQuery.split("&")) {
                if (pair.isEmpty()) continue;
                int eq = pair.indexOf('=');
                String k = eq < 0 ? pair : pair.substring(0, eq);
                String v = eq < 0 ? "" : pair.substring(eq + 1);
                m.add(k, v);
            }
        } else {
            exchange.queryParams().forEach((k, vs) -> { for (String v : vs) m.add(k, v); });
        }
        return m;
    }

    @Override public List<String> getMatchedURIs() { return getMatchedURIs(true); }

    @Override public List<String> getMatchedURIs(boolean decode) {
        // §9.2.1: list of matched URIs, from the most specific (method)
        // to the broadest (root resource). Minimalist: we split pathInfo
        // into two levels (method then class) whenever there is at least
        // one '/'.
        String p = decode ? getPath() : getPath(false);
        if (p == null || p.isEmpty()) return List.of();
        int lastSlash = p.lastIndexOf('/');
        if (lastSlash <= 0) return List.of(p);
        String parent = p.substring(0, lastSlash);
        return List.of(p, parent);
    }

    @Override public String getMatchedResourceTemplate() {
        if (matchedTemplate == null) return "";
        // §10 (jaxrs40): the returned template includes the path declared by
        // @ApplicationPath of the Application subclass (if any).
        var app = io.vidocq.cassini.internal.ParamExtractor.currentApplication();
        if (app != null) {
            jakarta.ws.rs.ApplicationPath ap = app.getClass().getAnnotation(jakarta.ws.rs.ApplicationPath.class);
            if (ap != null && !ap.value().isEmpty()) {
                String prefix = ap.value();
                if (!prefix.startsWith("/")) prefix = "/" + prefix;
                if (prefix.endsWith("/") && prefix.length() > 1) prefix = prefix.substring(0, prefix.length() - 1);
                return prefix + matchedTemplate;
            }
        }
        return matchedTemplate;
    }

    @Override @SuppressWarnings("unchecked")
    public List<Object> getMatchedResources() {
        var matched = (java.util.List<Object>) exchange.getAttribute(
                io.vidocq.cassini.internal.Invoker.ATTR_MATCHED_RESOURCES);
        return matched == null ? List.of() : List.copyOf(matched);
    }

    @Override public URI resolve(URI uri) { return getBaseUri().resolve(uri); }

    @Override public URI relativize(URI uri) { return getRequestUri().relativize(uri); }

    private record SimpleSegment(String value) implements PathSegment {
        @Override public String getPath() { return value; }
        @Override public MultivaluedMap<String, String> getMatrixParameters() { return new MultivaluedHashMap<>(); }
    }
}
