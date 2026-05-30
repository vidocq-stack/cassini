package io.vidocq.cassini.internal.runtime;

import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import java.lang.annotation.Annotation;
import java.net.URI;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@link jakarta.ws.rs.core.Response.ResponseBuilder} implementation.
 * Covers the common setters (status / entity / header / type / location
 * / cookie / allow / cacheControl). Full variants and links will arrive
 * with M2e-2 if the TCK requires them.
 */
public final class CassiniResponseBuilder extends Response.ResponseBuilder {

    private int status = 200;
    private boolean statusExplicit = false;
    private String reason;
    private Object entity;
    private Annotation[] annotations;
    private final MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();

    @Override public Response build() {
        // §3.5: default status = 200 if entity != null, 204 otherwise. If
        // the caller explicitly set a status, honor it.
        int s = statusExplicit ? status : (entity == null ? 204 : 200);
        return new CassiniResponse(s, reason, entity, annotations, copy(headers));
    }

    @Override public Response.ResponseBuilder clone() {
        CassiniResponseBuilder c = new CassiniResponseBuilder();
        c.status = status;
        c.statusExplicit = statusExplicit;
        c.reason = reason;
        c.entity = entity;
        c.annotations = annotations;
        c.headers.putAll(headers);
        return c;
    }

    @Override public Response.ResponseBuilder status(int s) { this.status = s; this.reason = null; this.statusExplicit = true; return this; }
    @Override public Response.ResponseBuilder status(int s, String r) { this.status = s; this.reason = r; this.statusExplicit = true; return this; }

    @Override public Response.ResponseBuilder entity(Object e) { this.entity = e; return this; }
    @Override public Response.ResponseBuilder entity(Object e, Annotation[] a) { this.entity = e; this.annotations = a; return this; }

    @Override public Response.ResponseBuilder allow(String... methods) {
        if (methods == null) headers.remove("Allow");
        else headers.putSingle("Allow", String.join(", ", methods));
        return this;
    }

    @Override public Response.ResponseBuilder allow(Set<String> methods) {
        if (methods == null) headers.remove("Allow");
        else headers.putSingle("Allow", String.join(", ", methods));
        return this;
    }

    @Override public Response.ResponseBuilder cacheControl(CacheControl cc) {
        if (cc == null) headers.remove("Cache-Control");
        else headers.putSingle("Cache-Control", cc);
        return this;
    }

    @Override public Response.ResponseBuilder encoding(String encoding) {
        if (encoding == null) headers.remove("Content-Encoding");
        else headers.putSingle("Content-Encoding", encoding);
        return this;
    }

    @Override public Response.ResponseBuilder header(String name, Object value) {
        if (value == null) headers.remove(name);
        else headers.add(name, value);
        return this;
    }

    @Override public Response.ResponseBuilder replaceAll(MultivaluedMap<String, Object> h) {
        headers.clear();
        if (h != null) headers.putAll(h);
        return this;
    }

    @Override public Response.ResponseBuilder language(String lang) {
        if (lang == null) headers.remove("Content-Language");
        else headers.putSingle("Content-Language", lang);
        return this;
    }

    @Override public Response.ResponseBuilder language(Locale lang) {
        if (lang == null) headers.remove("Content-Language");
        else headers.putSingle("Content-Language", lang.toLanguageTag());
        return this;
    }

    @Override public Response.ResponseBuilder type(MediaType type) {
        if (type == null) headers.remove("Content-Type");
        else headers.putSingle("Content-Type", type);
        return this;
    }

    @Override public Response.ResponseBuilder type(String type) {
        if (type == null) headers.remove("Content-Type");
        else headers.putSingle("Content-Type", type);
        return this;
    }

    @Override public Response.ResponseBuilder variant(Variant variant) {
        if (variant == null) return this;
        if (variant.getMediaType() != null) type(variant.getMediaType());
        if (variant.getLanguage() != null) language(variant.getLanguage());
        if (variant.getEncoding() != null) encoding(variant.getEncoding());
        return this;
    }

    @Override public Response.ResponseBuilder contentLocation(URI location) {
        if (location == null) { headers.remove("Content-Location"); return this; }
        URI resolved = resolveAgainstBase(location);
        headers.putSingle("Content-Location", resolved.toString());
        return this;
    }

    @Override public Response.ResponseBuilder cookie(NewCookie... cookies) {
        if (cookies == null) { headers.remove("Set-Cookie"); return this; }
        for (NewCookie c : cookies) headers.add("Set-Cookie", c);
        return this;
    }

    @Override public Response.ResponseBuilder expires(Date expires) {
        if (expires == null) headers.remove("Expires");
        else headers.putSingle("Expires", expires);
        return this;
    }

    @Override public Response.ResponseBuilder lastModified(Date lm) {
        if (lm == null) headers.remove("Last-Modified");
        else headers.putSingle("Last-Modified", lm);
        return this;
    }

    @Override public Response.ResponseBuilder location(URI location) {
        if (location == null) { headers.remove("Location"); return this; }
        URI resolved = resolveAgainstBase(location);
        headers.putSingle("Location", resolved.toString());
        return this;
    }

    /** ThreadLocal baseUri to resolve relative URIs per §6.7. */
    private static final ThreadLocal<URI> BASE_URI = new ThreadLocal<>();
    public static void setBaseUri(URI base) { BASE_URI.set(base); }
    public static void clearBaseUri() { BASE_URI.remove(); }

    private static URI resolveAgainstBase(URI location) {
        if (location.isAbsolute()) return location;
        URI base = BASE_URI.get();
        if (base == null) return location;
        return base.resolve(location);
    }

    @Override public Response.ResponseBuilder tag(EntityTag tag) {
        if (tag == null) headers.remove("ETag");
        else headers.putSingle("ETag", tag);
        return this;
    }

    @Override public Response.ResponseBuilder tag(String tag) {
        if (tag == null) headers.remove("ETag");
        else headers.putSingle("ETag", new jakarta.ws.rs.core.EntityTag(tag));
        return this;
    }

    @Override public Response.ResponseBuilder variants(Variant... variants) {
        if (variants == null) { headers.remove("Vary"); return this; }
        // §4.4 : Vary header produced from the varying dimensions
        java.util.Set<String> dims = new java.util.LinkedHashSet<>();
        for (Variant v : variants) {
            if (v.getMediaType() != null) dims.add("Accept");
            if (v.getLanguage() != null) dims.add("Accept-Language");
            if (v.getEncoding() != null) dims.add("Accept-Encoding");
        }
        if (!dims.isEmpty()) headers.putSingle("Vary", String.join(", ", dims));
        return this;
    }
    @Override public Response.ResponseBuilder variants(List<Variant> variants) {
        return variants == null ? variants((Variant[]) null) : variants(variants.toArray(new Variant[0]));
    }
    @Override public Response.ResponseBuilder links(Link... links) {
        if (links == null) { headers.remove("Link"); return this; }
        headers.remove("Link");
        for (Link l : links) headers.add("Link", l);
        return this;
    }
    @Override public Response.ResponseBuilder link(URI uri, String rel) {
        if (uri == null) throw new IllegalArgumentException("uri is null");
        Link l = Link.fromUri(uri).rel(rel).build();
        headers.add("Link", l);
        return this;
    }
    @Override public Response.ResponseBuilder link(String uri, String rel) {
        if (uri == null) throw new IllegalArgumentException("uri is null");
        Link l = Link.fromUri(uri).rel(rel).build();
        headers.add("Link", l);
        return this;
    }

    private static MultivaluedMap<String, Object> copy(MultivaluedMap<String, Object> src) {
        return new MultivaluedHashMap<>(src);
    }
}
