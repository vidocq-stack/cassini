package io.vidocq.cassini.internal.filter;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.MediaTypes;
import io.vidocq.cassini.internal.context.CassiniHttpHeaders;
import io.vidocq.cassini.internal.context.CassiniSecurityContext;
import io.vidocq.cassini.internal.context.CassiniUriInfo;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implémentation de {@link ContainerRequestContext} adossée à une
 * {@link Request} Chappe. Les filtres peuvent muter headers, entity stream,
 * method et aborter la requête via {@link #abortWith(Response)}.
 */
public final class CassiniRequestContext implements ContainerRequestContext {

    /**
     * Clé d'attribut d'exchange portant le {@link SecurityContext} posé par un filtre
     * {@code @PreMatching} via {@link #setSecurityContext}. Lue par {@code FieldInjector} et
     * {@code ParamExtractor} pour l'injection {@code @Context SecurityContext} dans les ressources.
     */
    public static final String ATTR_SECURITY_CONTEXT = "io.vidocq.cassini.securityContext";

    private final CassiniHttpExchange exchange;
    private final CassiniHttpHeaders httpHeaders;
    private final Map<String, Object> properties = new HashMap<>();
    private final MultivaluedMap<String, String> headers;
    private String method;
    private URI requestUri;
    private URI baseUri;
    private InputStream entityStream;
    private SecurityContext securityContext;
    private UriInfo uriInfo;
    private Response aborted;
    /** §6.6 : vrai quand le matching a déjà eu lieu — empêche setMethod /
     *  setRequestUri / setSecurityContext / setEntityStream / abortWith
     *  depuis un filtre @PostMatching pour les mutations illégales. */
    private boolean postMatching = false;
    /** §6.6 : passage des response filters — abortWith doit lever
     *  IllegalStateException. Activé via {@link #runDuringResponsePhase}. */
    private boolean responsePhase = false;

    public void markPostMatching() { this.postMatching = true; }
    /**
     * Exécute {@code action} avec le flag {@code responsePhase} actif —
     * ainsi un response filter qui appelle {@code abortWith} déclenche
     * IllegalStateException, mais l'appel programmatique d'abortWith
     * en dehors du response chain (ex. exception mapper internal flow)
     * reste autorisé.
     */
    public void runDuringResponsePhase(Runnable action) {
        boolean prev = responsePhase;
        responsePhase = true;
        try { action.run(); } finally { responsePhase = prev; }
    }

    public CassiniRequestContext(CassiniHttpExchange exchange, UriInfo uriInfo) {
        this.exchange = exchange;
        this.httpHeaders = new CassiniHttpHeaders(exchange);
        this.headers = buildHeaders(exchange);
        this.method = exchange.method();
        this.requestUri = exchange.requestUri();
        this.baseUri = uriInfo.getBaseUri();
        this.entityStream = exchange.requestBody() == null ? new ByteArrayInputStream(new byte[0])
                : exchange.requestBody();
        this.securityContext = new CassiniSecurityContext(exchange);
        this.uriInfo = uriInfo;
    }

    public CassiniHttpExchange exchange() { return exchange; }
    public InputStream currentEntityStream() { return entityStream; }
    public boolean isAborted() { return aborted != null; }
    public Response abortedResponse() { return aborted; }
    /** §6.6.1 : method/URI courants après mutation par les pre-matching filters. */
    public String currentMethod() { return method; }
    public URI currentRequestUri() { return requestUri; }

    private static MultivaluedMap<String, String> buildHeaders(CassiniHttpExchange exchange) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : exchange.requestHeaders().entrySet()) {
            for (String v : e.getValue()) m.add(e.getKey(), v);
        }
        return m;
    }

    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return Collections.unmodifiableSet(properties.keySet()); }
    @Override public void setProperty(String name, Object value) { properties.put(name, value); }
    @Override public void removeProperty(String name) { properties.remove(name); }

    @Override public UriInfo getUriInfo() {
        // §6.6.1 : si setRequestUri a été appelé, la UriInfo doit refléter les
        // nouvelles valeurs de baseUri/requestUri sans recréer toute la chaîne.
        return new MutableUriInfoView(uriInfo, baseUri, requestUri);
    }
    @Override public void setRequestUri(URI requestUri) {
        if (postMatching) throw new IllegalStateException("setRequestUri cannot be called in post-matching filters (§6.6)");
        this.requestUri = requestUri;
    }
    @Override public void setRequestUri(URI baseUri, URI requestUri) {
        if (postMatching) throw new IllegalStateException("setRequestUri cannot be called in post-matching filters (§6.6)");
        this.baseUri = baseUri; this.requestUri = requestUri;
    }

    @Override public jakarta.ws.rs.core.Request getRequest() {
        return new io.vidocq.cassini.internal.context.CassiniRequest(exchange);
    }

    @Override public String getMethod() { return method; }
    @Override public void setMethod(String method) {
        if (postMatching) throw new IllegalStateException("setMethod cannot be called in post-matching filters (§6.6)");
        this.method = method;
    }

    @Override public MultivaluedMap<String, String> getHeaders() { return headers; }

    @Override public String getHeaderString(String name) {
        List<String> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        return String.join(",", v);
    }

    @Override public boolean containsHeaderString(String n, String sep, java.util.function.Predicate<String> p) {
        // §6.7.4 : recherche case-insensitive (RFC 7230).
        List<String> vs = headers.get(n);
        if (vs == null) {
            for (var e : headers.entrySet()) {
                if (e.getKey().equalsIgnoreCase(n)) { vs = e.getValue(); break; }
            }
        }
        if (vs == null) return false;
        for (String v : vs) for (String tok : v.split(sep)) if (p.test(tok.trim())) return true;
        return false;
    }

    @Override public boolean containsHeaderString(String n, java.util.function.Predicate<String> p) {
        return containsHeaderString(n, ",", p);
    }

    @Override public Date getDate() { return httpHeaders.getDate(); }
    @Override public Locale getLanguage() { return httpHeaders.getLanguage(); }
    @Override public int getLength() { return httpHeaders.getLength(); }
    @Override public MediaType getMediaType() { return httpHeaders.getMediaType(); }
    @Override public List<MediaType> getAcceptableMediaTypes() { return httpHeaders.getAcceptableMediaTypes(); }
    @Override public List<Locale> getAcceptableLanguages() { return httpHeaders.getAcceptableLanguages(); }
    @Override public Map<String, Cookie> getCookies() { return httpHeaders.getCookies(); }

    @Override public boolean hasEntity() {
        return exchange.contentLength() != 0;
    }

    @Override public InputStream getEntityStream() { return entityStream; }
    @Override public void setEntityStream(InputStream input) {
        if (postMatching) throw new IllegalStateException("setEntityStream cannot be called in post-matching filters (§6.6)");
        this.entityStream = input;
    }

    @Override public SecurityContext getSecurityContext() { return securityContext; }
    @Override public void setSecurityContext(SecurityContext context) {
        if (postMatching) throw new IllegalStateException("setSecurityContext cannot be called in post-matching filters (§6.6)");
        this.securityContext = context;
        // §6 : un filtre @PreMatching peut remplacer le SecurityContext. Le propager sur l'exchange
        // pour que l'injection @Context SecurityContext dans une ressource (FieldInjector /
        // ParamExtractor) reflète cette valeur — sinon ils reconstruisent un CassiniSecurityContext
        // neuf et ignorent le contexte posé par le filtre (cas MicroProfile JWT).
        exchange.setAttribute(ATTR_SECURITY_CONTEXT, context);
    }

    @Override public void abortWith(Response response) {
        // §6.6 : abortWith autorisé dans pre-matching ET post-matching filters.
        // Interdit quand le context est injecté dans une méthode/champ de
        // ressource (postResource), ou pendant la phase response filters
        // (responsePhase, scoped via runDuringResponsePhase).
        if (postResource) throw new IllegalStateException("abortWith cannot be called from resource methods (§6.6)");
        if (responsePhase) throw new IllegalStateException("abortWith cannot be called from response filters (§6.6)");
        this.aborted = response;
    }

    /** Flag séparé : true uniquement quand on injecte le context dans la
     *  resource method (pas dans les filtres). */
    private boolean postResource = false;
    public void markPostResource() { this.postResource = true; }

    public MediaType parsedContentType() {
        return MediaTypes.parse(getHeaderString("Content-Type"));
    }

    // Used by ParamExtractor / Invoker quand les headers ont été mutés.
    public static CassiniRequestContext create(CassiniHttpExchange exchange, UriInfo uriInfo) {
        return new CassiniRequestContext(exchange, uriInfo);
    }

    /**
     * Vue {@link UriInfo} qui reflète les modifications de baseUri/requestUri
     * faites via setRequestUri. Délègue au UriInfo source pour les autres
     * propriétés (PathSegments, parameters, matchedResources, etc.).
     */
    private static final class MutableUriInfoView implements UriInfo {
        private final UriInfo delegate;
        private final URI base;
        private final URI request;

        MutableUriInfoView(UriInfo delegate, URI base, URI request) {
            this.delegate = delegate; this.base = base; this.request = request;
        }
        @Override public String getPath() {
            String b = base == null ? "/" : base.getPath();
            String r = request == null ? "" : request.getPath();
            if (b == null) b = "/";
            if (r == null) r = "";
            return r.startsWith(b) ? r.substring(b.length()) : r;
        }
        @Override public String getPath(boolean decode) { return getPath(); }
        @Override public java.util.List<jakarta.ws.rs.core.PathSegment> getPathSegments() { return delegate.getPathSegments(); }
        @Override public java.util.List<jakarta.ws.rs.core.PathSegment> getPathSegments(boolean decode) { return delegate.getPathSegments(decode); }
        @Override public URI getRequestUri() { return request != null ? request : delegate.getRequestUri(); }
        @Override public jakarta.ws.rs.core.UriBuilder getRequestUriBuilder() {
            return jakarta.ws.rs.core.UriBuilder.fromUri(getRequestUri());
        }
        @Override public URI getAbsolutePath() { return getRequestUri(); }
        @Override public jakarta.ws.rs.core.UriBuilder getAbsolutePathBuilder() {
            return jakarta.ws.rs.core.UriBuilder.fromUri(getAbsolutePath());
        }
        @Override public URI getBaseUri() { return base != null ? base : delegate.getBaseUri(); }
        @Override public jakarta.ws.rs.core.UriBuilder getBaseUriBuilder() {
            return jakarta.ws.rs.core.UriBuilder.fromUri(getBaseUri());
        }
        @Override public MultivaluedMap<String, String> getPathParameters() { return delegate.getPathParameters(); }
        @Override public MultivaluedMap<String, String> getPathParameters(boolean decode) { return delegate.getPathParameters(decode); }
        @Override public MultivaluedMap<String, String> getQueryParameters() { return delegate.getQueryParameters(); }
        @Override public MultivaluedMap<String, String> getQueryParameters(boolean decode) { return delegate.getQueryParameters(decode); }
        @Override public java.util.List<String> getMatchedURIs() { return delegate.getMatchedURIs(); }
        @Override public java.util.List<String> getMatchedURIs(boolean decode) { return delegate.getMatchedURIs(decode); }
        @Override public java.util.List<Object> getMatchedResources() { return delegate.getMatchedResources(); }
        @Override public URI resolve(URI uri) { return delegate.resolve(uri); }
        @Override public URI relativize(URI uri) { return delegate.relativize(uri); }
        public String getMatchedResourceTemplate() { return ""; }
    }
}
