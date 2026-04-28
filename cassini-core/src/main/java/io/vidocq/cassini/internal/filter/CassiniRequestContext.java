package io.vidocq.cassini.internal.filter;

import fr.vidocq.chappe.api.Request;
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

    private final Request request;
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

    public void markPostMatching() { this.postMatching = true; }

    public CassiniRequestContext(Request request, UriInfo uriInfo) {
        this.request = request;
        this.httpHeaders = new CassiniHttpHeaders(request);
        this.headers = buildHeaders(request);
        this.method = request.method().name();
        this.requestUri = request.uri();
        this.baseUri = uriInfo.getBaseUri();
        this.entityStream = request.body() == null ? new ByteArrayInputStream(new byte[0])
                : request.body().asInputStream();
        this.securityContext = new CassiniSecurityContext(request);
        this.uriInfo = uriInfo;
    }

    public Request chappeRequest() { return request; }
    public InputStream currentEntityStream() { return entityStream; }
    public boolean isAborted() { return aborted != null; }
    public Response abortedResponse() { return aborted; }
    /** §6.6.1 : method/URI courants après mutation par les pre-matching filters. */
    public String currentMethod() { return method; }
    public URI currentRequestUri() { return requestUri; }

    private static MultivaluedMap<String, String> buildHeaders(Request request) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : request.headers()) m.add(e.name(), e.value());
        return m;
    }

    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public Collection<String> getPropertyNames() { return Collections.unmodifiableSet(properties.keySet()); }
    @Override public void setProperty(String name, Object value) { properties.put(name, value); }
    @Override public void removeProperty(String name) { properties.remove(name); }

    @Override public UriInfo getUriInfo() { return uriInfo; }
    @Override public void setRequestUri(URI requestUri) {
        if (postMatching) throw new IllegalStateException("setRequestUri cannot be called in post-matching filters (§6.6)");
        this.requestUri = requestUri;
    }
    @Override public void setRequestUri(URI baseUri, URI requestUri) {
        if (postMatching) throw new IllegalStateException("setRequestUri cannot be called in post-matching filters (§6.6)");
        this.baseUri = baseUri; this.requestUri = requestUri;
    }

    @Override public jakarta.ws.rs.core.Request getRequest() {
        return new io.vidocq.cassini.internal.context.CassiniRequest(request);
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
        List<String> vs = headers.get(n);
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
        return request.body() != null && request.body().contentLength() != 0;
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
    }

    @Override public void abortWith(Response response) {
        // §6.6 : abortWith autorisé dans pre-matching ET post-matching filters.
        // Interdit quand le context est injecté dans une méthode/champ de
        // ressource (flag postResource).
        if (postResource) throw new IllegalStateException("abortWith cannot be called from resource methods (§6.6)");
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
    public static CassiniRequestContext create(Request request, UriInfo uriInfo) {
        return new CassiniRequestContext(request, uriInfo);
    }
}
