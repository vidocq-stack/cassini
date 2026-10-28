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

import io.vidocq.cassini.internal.MediaTypes;
import io.vidocq.cassini.internal.runtime.HeaderValues;
import io.vidocq.cassini.internal.runtime.StatusCodes;
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
 * {@link ContainerResponseContext} implementation wrapping a
 * mutable internal representation of the outgoing response (status + headers
 * + entity). Response filters can change all of this before the
 * MBW.
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
     *  collect the final body even when a filter has wrapped entityStream
     *  via setEntityStream(). The application wrapper is expected to forward to
     *  this sink (cf. TCK tests setEntityStreamTest). */
    public java.io.ByteArrayOutputStream originalStream() { return originalStream; }

    public CassiniResponseContext(int status, Object entity, Type entityType,
                                  MultivaluedMap<String, Object> headers) {
        this(status, entity, entityType, null, headers);
    }

    public CassiniResponseContext(int status, Object entity, Type entityType,
                                  Annotation[] annotations, MultivaluedMap<String, Object> headers) {
        this.status = status;
        this.statusInfo = statusInfoOf(status);
        this.entity = entity;
        this.entityType = entityType;
        if (annotations != null) this.annotations = annotations;
        this.headers = headers == null ? new MultivaluedHashMap<>() : headers;
    }

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int code) { this.status = code; this.statusInfo = statusInfoOf(code); }

    /** The standard {@link Response.Status} of {@code code}, or a bare one for a non-standard code: the
     *  status info is never null (CASSINI-014). */
    private static Response.StatusType statusInfoOf(int code) {
        Response.StatusType standard = StatusCodes.of(code);
        if (standard != null) return standard;
        return new Response.StatusType() {
            @Override public int getStatusCode() { return code; }
            @Override public Response.Status.Family getFamily() { return Response.Status.Family.familyOf(code); }
            @Override public String getReasonPhrase() { return ""; }
        };
    }
    @Override public Response.StatusType getStatusInfo() { return statusInfo; }
    @Override public void setStatusInfo(Response.StatusType si) { this.statusInfo = si; this.status = si.getStatusCode(); }

    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }

    @Override public MultivaluedMap<String, String> getStringHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : headers.entrySet()) for (Object v : e.getValue()) m.add(e.getKey(), headerToString(v));
        return m;
    }

    @Override public String getHeaderString(String name) {
        java.util.List<Object> v = headers.get(name);
        if (v == null || v.isEmpty()) return null;
        java.util.StringJoiner joined = new java.util.StringJoiner(",");
        for (Object o : v) joined.add(headerToString(o));
        return joined.toString();
    }

    /** A header value as text, through its {@code HeaderDelegate} when there is one (CASSINI-014). */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String headerToString(Object v) {
        if (v == null || v instanceof String) return (String) v;
        jakarta.ws.rs.ext.RuntimeDelegate.HeaderDelegate hd =
                jakarta.ws.rs.ext.RuntimeDelegate.getInstance().createHeaderDelegate(v.getClass());
        return hd == null ? v.toString() : hd.toString(v);
    }

    @Override public boolean containsHeaderString(String n, String sep, java.util.function.Predicate<String> p) {
        // §6.7.4 : recherche d'header case-insensitive (RFC 7230).
        java.util.List<Object> vs = caseInsensitiveLookup(n);
        for (Object v : vs) for (String tok : v.toString().split(sep)) if (p.test(tok.trim())) return true;
        return false;
    }

    private java.util.List<Object> caseInsensitiveLookup(String n) {
        java.util.List<Object> direct = headers.get(n);
        if (direct != null) return direct;
        for (var e : headers.entrySet()) {
            if (e.getKey().equalsIgnoreCase(n)) return e.getValue();
        }
        return java.util.List.of();
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
    @Override public Date getDate() { return HeaderValues.httpDate(headers.getFirst("Date")); }
    @Override public Locale getLanguage() { return HeaderValues.language(headers); }
    @Override public int getLength() { return HeaderValues.length(headers); }
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
            else {
                NewCookie nc = parseCookie(v.toString());
                if (nc != null) out.put(nc.getName(), nc);
            }
        }
        return out;
    }

    private static NewCookie parseCookie(String raw) {
        try {
            return jakarta.ws.rs.ext.RuntimeDelegate.getInstance().createHeaderDelegate(NewCookie.class).fromString(raw);
        } catch (RuntimeException _) {
            return null; // malformed Set-Cookie value: not exposed as a cookie
        }
    }
    @Override public EntityTag getEntityTag() { return HeaderValues.entityTag(headers); }
    @Override public Date getLastModified() { return HeaderValues.httpDate(headers.getFirst("Last-Modified")); }
    @Override public URI getLocation() { return HeaderValues.location(headers); }
    @Override public Set<Link> getLinks() { return HeaderValues.links(headers); }
    @Override public boolean hasLink(String relation) { return getLink(relation) != null; }
    @Override public Link getLink(String relation) { return HeaderValues.link(getLinks(), relation); }
    @Override public Link.Builder getLinkBuilder(String relation) {
        Link l = getLink(relation);
        return l == null ? null : Link.fromLink(l);
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
