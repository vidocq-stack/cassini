/*
 * Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * https://www.eclipse.org/legal/epl-2.0/
 *
 * This Source Code may also be made available under the following Secondary
 * Licenses when the conditions for such availability set forth in the Eclipse
 * Public License, v. 2.0 are satisfied: GNU General Public License, version 2
 * or any later version, which is available at
 * https://www.gnu.org/licenses/old-licenses/gpl-2.0.html
 *
 * It is also made available under the European Union Public Licence v. 1.2,
 * which is available at
 * https://joinup.ec.europa.eu/collection/eupl/eupl-text-eupl-12
 *
 * SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
 */
package io.vidocq.cassini.internal.filter;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.HeaderMaps;
import io.vidocq.cassini.internal.MediaTypes;
import io.vidocq.cassini.internal.context.CassiniHttpHeaders;
import io.vidocq.cassini.internal.context.CassiniSecurityContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link ContainerRequestContext} implementation backed by a
 * Chappe {@link Request}. Filters can mutate headers, entity stream,
 * method and abort the request via {@link #abortWith(Response)}.
 */
public final class CassiniRequestContext implements ContainerRequestContext {

    /**
     * Exchange attribute key carrying the {@link SecurityContext} set by a
     * request filter via {@link #setSecurityContext}. Read by {@code FieldInjector} and
     * {@code ParamExtractor} for {@code @Context SecurityContext} injection into resources.
     */
    public static final String ATTR_SECURITY_CONTEXT = "io.vidocq.cassini.securityContext";

    private final CassiniHttpExchange exchange;
    private final CassiniHttpHeaders httpHeaders;
    /** The mutable copy of the request headers, built the first time a filter asks for it: most
     *  filters never do, and the copy walks every header of the exchange. */
    private MultivaluedMap<String, String> headers;
    private String method;
    /** Set by {@link #setRequestUri}; until then the URI of the exchange, built when asked for. */
    private URI requestUri;
    /** Set by {@link #setRequestUri(URI, URI)} only; until then {@link #getUriInfo()} reads the
     *  base URI from the {@link UriInfo}, which costs nothing when nobody asks for it. */
    private URI baseUri;
    private InputStream entityStream;
    /** Whether a filter set the entity stream, which the resource method then reads (CASSINI-056). */
    private boolean entityReplaced;
    private SecurityContext securityContext;
    private UriInfo uriInfo;
    private Response aborted;
    /** §6.6: true once matching has occurred — setMethod and setRequestUri, which
     *  only a pre-matching filter may call, then throw. */
    private boolean postMatching = false;
    /** §6.6: during the response-filter pass — abortWith must throw
     *  IllegalStateException. Activated via {@link #runDuringResponsePhase}. */
    private boolean responsePhase = false;

    /** §6.7.1: no resource method is matched for the response this context goes with. */
    private boolean unmatched = false;

    public void markPostMatching() { this.postMatching = true; }

    /**
     * §6.7.1: marks a context whose response comes before any resource method is matched (a failure of a
     * sub-resource locator, a request the matching rejects): only the globally bound response filters process it.
     */
    public void markUnmatched() { this.unmatched = true; }

    /** Whether the response this context goes with comes before any resource method is matched (§6.7.1). */
    public boolean unmatched() { return unmatched; }
    /**
     * Runs {@code action} with the {@code responsePhase} flag active —
     * so a response filter that calls {@code abortWith} triggers
     * IllegalStateException, but a programmatic call to abortWith
     * outside the response chain (e.g. exception mapper internal flow)
     * remains allowed.
     */
    public void runDuringResponsePhase(Runnable action) {
        boolean prev = responsePhase;
        responsePhase = true;
        try { action.run(); } finally { responsePhase = prev; }
    }

    public CassiniRequestContext(CassiniHttpExchange exchange, UriInfo uriInfo) {
        this.exchange = exchange;
        this.httpHeaders = new CassiniHttpHeaders(exchange);
        this.method = exchange.method();
        this.entityStream = exchange.requestBody() == null ? new ByteArrayInputStream(new byte[0])
                : exchange.requestBody();
        // §6: a @PreMatching filter may have set a SecurityContext (stored on the exchange).
        // Any subsequent context instance (e.g. the one in the post-matching chain where
        // RolesAllowedRequestFilter runs) must reflect it, otherwise getSecurityContext()
        // returns the default and authorisation does not see the JWT principal.
        Object filterSc = exchange.getAttribute(ATTR_SECURITY_CONTEXT);
        this.securityContext = filterSc instanceof SecurityContext sc ? sc : new CassiniSecurityContext(exchange);
        this.uriInfo = uriInfo;
    }

    public CassiniHttpExchange exchange() { return exchange; }
    public InputStream currentEntityStream() { return entityStream; }
    public boolean isAborted() { return aborted != null; }
    public Response abortedResponse() { return aborted; }
    /** §6.6.1: current method/URI after mutation by pre-matching filters. */
    public String currentMethod() { return method; }
    public URI currentRequestUri() { return requestUri != null ? requestUri : exchange.requestUri(); }

    private MultivaluedMap<String, String> headers() {
        MultivaluedMap<String, String> h = headers;
        if (h == null) {
            h = HeaderMaps.copyOf(exchange.requestHeaders());
            headers = h;
        }
        return h;
    }

    // The properties belong to the request, not to this context: every context of the request shares them
    // (CASSINI-007), and none allocates anything for them until a property is set.
    @Override public Object getProperty(String name) { return RequestProperties.get(exchange, name); }
    @Override public Collection<String> getPropertyNames() { return RequestProperties.names(exchange); }
    /** A {@code null} value removes the property (ContainerRequestContext#setProperty). */
    @Override public void setProperty(String name, Object value) { RequestProperties.set(exchange, name, value); }
    @Override public void removeProperty(String name) { RequestProperties.remove(exchange, name); }

    @Override public UriInfo getUriInfo() {
        // §6.6.1: if setRequestUri was called, UriInfo must reflect the
        // new baseUri/requestUri values without rebuilding the whole chain.
        return new MutableUriInfoView(uriInfo, baseUri, currentRequestUri());
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

    @Override public MultivaluedMap<String, String> getHeaders() { return headers(); }

    @Override public String getHeaderString(String name) {
        List<String> v = headers().get(name);
        if (v == null || v.isEmpty()) return null;
        return String.join(",", v);
    }

    @Override public boolean containsHeaderString(String n, String sep, java.util.function.Predicate<String> p) {
        List<String> vs = headers().get(n);
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

    /** Allowed in any request filter, pre-matching or not; throws from a response filter or a resource
     *  (ContainerRequestContext#setEntityStream, CASSINI-056). */
    @Override public void setEntityStream(InputStream input) {
        requestStillOpen("setEntityStream");
        this.entityStream = input;
        this.entityReplaced = true;
    }

    @Override public SecurityContext getSecurityContext() { return securityContext; }

    /** Allowed in any request filter, pre-matching or not; throws from a response filter or a resource
     *  (ContainerRequestContext#setSecurityContext, CASSINI-056). */
    @Override public void setSecurityContext(SecurityContext context) {
        requestStillOpen("setSecurityContext");
        this.securityContext = context;
        // §6: a request filter may replace the SecurityContext. Propagate it to the
        // exchange so that @Context SecurityContext injection in a resource (FieldInjector /
        // ParamExtractor) reflects this value — otherwise they reconstruct a fresh
        // CassiniSecurityContext and ignore the context set by the filter (e.g. MicroProfile JWT).
        exchange.setAttribute(ATTR_SECURITY_CONTEXT, context);
    }

    /** Whether a filter set the entity stream on this context. */
    public boolean entityReplaced() { return entityReplaced; }

    /** The setters of the request throw once the resource method has the request: in a response filter, or on the
     *  context injected into a resource. */
    private void requestStillOpen(String setter) {
        if (responsePhase || postResource) {
            throw new IllegalStateException(setter + " cannot be called from a response filter or a resource");
        }
    }

    /**
     * The exchange the rest of the request reads once the filters ran on this context (CASSINI-056): the exchange this
     * context was built on, or, when a filter read or changed the headers, replaced the entity stream, or set the
     * method or the request URI, one that gives them as this context holds them.
     */
    public CassiniHttpExchange filteredExchange() {
        boolean methodChanged = method != null && !method.equals(exchange.method());
        if (headers == null && !entityReplaced && !methodChanged && requestUri == null) return exchange;
        return new FilteredExchange(exchange, headers, entityReplaced ? entityStream : null,
                methodChanged ? method : null, requestUri);
    }

    @Override public void abortWith(Response response) {
        // §6.6: abortWith is allowed in pre-matching AND post-matching filters.
        // Forbidden when the context is injected into a resource method/field
        // (postResource), or during the response-filter phase
        // (responsePhase, scoped via runDuringResponsePhase).
        if (postResource) throw new IllegalStateException("abortWith cannot be called from resource methods (§6.6)");
        if (responsePhase) throw new IllegalStateException("abortWith cannot be called from response filters (§6.6)");
        this.aborted = response;
    }

    /** Separate flag: true only when the context is injected into the
     *  resource method (not into filters). */
    private boolean postResource = false;
    public void markPostResource() { this.postResource = true; }

    public MediaType parsedContentType() {
        return MediaTypes.parse(getHeaderString("Content-Type"));
    }

    // Used by ParamExtractor / Invoker when headers have been mutated.
    public static CassiniRequestContext create(CassiniHttpExchange exchange, UriInfo uriInfo) {
        return new CassiniRequestContext(exchange, uriInfo);
    }

    /**
     * {@link UriInfo} view that reflects baseUri/requestUri modifications
     * made via setRequestUri. Delegates to the source UriInfo for all other
     * properties (PathSegments, parameters, matchedResources, etc.).
     */
    private static final class MutableUriInfoView implements UriInfo {
        private final UriInfo delegate;
        private final URI base;
        private final URI request;

        MutableUriInfoView(UriInfo delegate, URI base, URI request) {
            this.delegate = delegate; this.base = base; this.request = request;
        }
        @Override public String getPath() {
            URI baseUri = getBaseUri();
            String b = baseUri == null ? "/" : baseUri.getPath();
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
        /** The request URI without its query and fragment (CASSINI-031). */
        @Override public URI getAbsolutePath() {
            URI uri = getRequestUri();
            if (uri.getRawQuery() == null && uri.getRawFragment() == null) return uri;
            try {
                return new URI(uri.getScheme(), uri.getAuthority(), uri.getPath(), null, null);
            } catch (java.net.URISyntaxException _) {
                return uri;
            }
        }
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
        @Override public String getMatchedResourceTemplate() { return delegate.getMatchedResourceTemplate(); }
    }
}
