package io.vidocq.cassini.internal.filter;

import io.vidocq.cassini.internal.MediaTypes;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.URI;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@link ContainerResponseContext} implementation wrapping a mutable
 * internal representation of the outgoing response (status + headers +
 * entity). Response filters can change all of these before the MBW.
 */
public final class CassiniResponseContext implements ContainerResponseContext {

    private int status;
    private Response.StatusType statusInfo;
    private Object entity;
    private Type entityType;
    private Annotation[] annotations = new Annotation[0];
    private final MultivaluedMap<String, Object> headers;
    private final java.io.ByteArrayOutputStream originalStream = new java.io.ByteArrayOutputStream();
    private OutputStream entityStream = originalStream;

    /** Original sink (ByteArrayOutputStream) — used by the runtime to
     *  collect the final body even when a filter wrapped entityStream
     *  via setEntityStream(). The application wrapper is expected to
     *  forward to this sink (see TCK tests setEntityStreamTest). */
    public java.io.ByteArrayOutputStream originalStream() { return originalStream; }

    public CassiniResponseContext(int status, Object entity, Type entityType,
                                  MultivaluedMap<String, Object> headers) {
        this(status, entity, entityType, null, headers);
    }

    public CassiniResponseContext(int status, Object entity, Type entityType,
                                  Annotation[] annotations, MultivaluedMap<String, Object> headers) {
        this.status = status;
        this.statusInfo = Response.Status.fromStatusCode(status);
        this.entity = entity;
        this.entityType = entityType;
        if (annotations != null) this.annotations = annotations;
        this.headers = headers == null ? new MultivaluedHashMap<>() : headers;
    }

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int code) { this.status = code; this.statusInfo = Response.Status.fromStatusCode(code); }
    @Override public Response.StatusType getStatusInfo() { return statusInfo; }
    @Override public void setStatusInfo(Response.StatusType si) { this.statusInfo = si; this.status = si.getStatusCode(); }

    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }

    @Override public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) for (Object v : e.getValue()) m.add(e.getKey(), String.valueOf(v));
        return m;
    }

    @Override public String getHeaderString(String name) {
        java.util.List<Object> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.size(); i++) { if (i > 0) sb.append(','); sb.append(v.get(i)); }
        return sb.toString();
    }

    @Override public boolean containsHeaderString(String n, String sep, java.util.function.Predicate<String> p) {
        // §6.7.4: case-insensitive header lookup (RFC 7230).
        java.util.List<Object> vs = caseInsensitiveLookup(n);
        if (vs == null) return false;
        for (Object v : vs) for (String tok : v.toString().split(sep)) if (p.test(tok.trim())) return true;
        return false;
    }

    private java.util.List<Object> caseInsensitiveLookup(String n) {
        java.util.List<Object> direct = headers.get(n);
        if (direct != null) return direct;
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(n)) return e.getValue();
        }
        return null;
    }

    @Override public boolean containsHeaderString(String n, java.util.function.Predicate<String> p) {
        return containsHeaderString(n, ",", p);
    }

    @Override public Set<String> getAllowedMethods() {
        Object v = headers.getFirst("Allow");
        if (v == null) return Set.of();
        Set<String> out = new java.util.LinkedHashSet<>();
        for (String tok : v.toString().split(",")) out.add(tok.trim().toUpperCase(Locale.ROOT));
        return out;
    }
    @Override public Date getDate() { return parseHttpDate(headers.getFirst("Date")); }
    @Override public Locale getLanguage() {
        Object v = headers.getFirst("Content-Language");
        return v == null ? null : Locale.forLanguageTag(v.toString());
    }
    @Override public int getLength() {
        Object v = headers.getFirst("Content-Length");
        if (v == null) return -1;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return -1; }
    }
    @Override public MediaType getMediaType() {
        Object v = headers.getFirst("Content-Type");
        if (v == null) return null;
        return v instanceof MediaType mt ? mt : MediaTypes.parse(v.toString());
    }
    @Override public Map<String, NewCookie> getCookies() {
        Map<String, NewCookie> out = new java.util.HashMap<>();
        java.util.List<Object> vs = headers.get("Set-Cookie");
        if (vs == null) return out;
        for (Object v : vs) {
            if (v instanceof NewCookie nc) out.put(nc.getName(), nc);
            else try {
                NewCookie nc = NewCookie.valueOf(v.toString());
                out.put(nc.getName(), nc);
            } catch (Exception ignored) {}
        }
        return out;
    }
    @Override public EntityTag getEntityTag() {
        Object v = headers.getFirst("ETag");
        if (v == null) return null;
        if (v instanceof EntityTag et) return et;
        try { return EntityTag.valueOf(v.toString()); } catch (Exception e) { return null; }
    }
    @Override public Date getLastModified() { return parseHttpDate(headers.getFirst("Last-Modified")); }
    @Override public URI getLocation() {
        Object v = headers.getFirst("Location");
        return v == null ? null : URI.create(v.toString());
    }
    @Override public Set<Link> getLinks() {
        java.util.List<Object> vs = headers.get("Link");
        if (vs == null || vs.isEmpty()) return Set.of();
        Set<Link> out = new java.util.LinkedHashSet<>();
        for (Object v : vs) {
            if (v instanceof Link l) { out.add(l); continue; }
            try { out.add(Link.valueOf(v.toString())); } catch (Exception ignored) {}
        }
        return out;
    }
    @Override public boolean hasLink(String relation) {
        for (Link l : getLinks()) if (relation != null && relation.equals(l.getRel())) return true;
        return false;
    }
    @Override public Link getLink(String relation) {
        for (Link l : getLinks()) if (relation != null && relation.equals(l.getRel())) return l;
        return null;
    }
    @Override public Link.Builder getLinkBuilder(String relation) {
        Link l = getLink(relation);
        return l == null ? null : Link.fromLink(l);
    }

    private static Date parseHttpDate(Object v) {
        if (v == null) return null;
        if (v instanceof Date d) return d;
        String s = v.toString();
        for (String fmt : new String[] {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"}) {
            try {
                return new java.text.SimpleDateFormat(fmt, Locale.US).parse(s);
            } catch (java.text.ParseException ignored) {}
        }
        return null;
    }

    @Override public boolean hasEntity() { return entity != null; }
    @Override public Object getEntity() { return entity; }
    @Override public Class<?> getEntityClass() { return entity == null ? null : entity.getClass(); }
    @Override public Type getEntityType() { return entityType; }
    @Override public void setEntity(Object entity) { this.entity = entity; if (entity != null) this.entityType = entity.getClass(); }
    @Override public void setEntity(Object entity, Annotation[] anns, MediaType mt) {
        this.entity = entity;
        this.annotations = anns == null ? new Annotation[0] : anns;
        if (entity != null) this.entityType = entity.getClass();
        if (mt != null) this.headers.putSingle("Content-Type", mt);
    }
    @Override public Annotation[] getEntityAnnotations() { return annotations; }
    @Override public OutputStream getEntityStream() { return entityStream; }
    @Override public void setEntityStream(OutputStream out) { this.entityStream = out; }
}
