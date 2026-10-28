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
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.core.AbstractMultivaluedMap;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Client-side implementation of {@link Response} — wraps a byte[] body and exposes
 * {@code readEntity(Class<T>)} for {@code String}, {@code byte[]}, {@code InputStream}.
 * Complex types (JSON-B, XML…) will come with the integration of
 * {@code MessageBodyRegistry} in commit #3.
 */
final class CassiniClientResponse extends Response {

    private final int status;
    private final byte[] body;
    private final MultivaluedMap<String, Object> headers;
    private final MultivaluedMap<String, String> stringHeaders;
    private boolean closed;
    /** Registered client provider instances (ContextResolver<Jsonb>, …); set by the invocation. */
    private java.util.Collection<Object> providers = java.util.Collections.emptyList();

    void setProviders(java.util.Collection<Object> providers) {
        this.providers = providers == null ? java.util.Collections.emptyList() : providers;
    }

    private MediaType contentType() {
        String ct = stringHeaders.getFirst(HttpHeaders.CONTENT_TYPE);
        if (ct == null || ct.isBlank()) {
            return null;
        }
        try {
            return MediaType.valueOf(ct);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private CassiniClientResponse(int status, byte[] body,
                                   MultivaluedMap<String, Object> headers,
                                   MultivaluedMap<String, String> stringHeaders) {
        this.status = status;
        this.body = body;
        this.headers = headers;
        this.stringHeaders = stringHeaders;
    }

    /**
     * A header map whose names compare case-insensitively, as HTTP field names do
     * (RFC 9110 §5.1): {@code java.net.http} hands them over as the server spelled them,
     * all lower case over HTTP/2 (CASSINI-019).
     */
    static <V> MultivaluedMap<String, V> headerMap() {
        return new AbstractMultivaluedMap<>(new TreeMap<>(String.CASE_INSENSITIVE_ORDER)) { };
    }

    /**
     * Builds a {@code CassiniClientResponse} from the data of a
     * {@link CassiniClientResponseContext} after applying the
     * {@code ClientResponseFilter} chain. Headers are already in String form and
     * may have been mutated by the filters.
     */
    static CassiniClientResponse fromBufferedBody(int status, byte[] body,
                                                    MultivaluedMap<String, String> stringHeaders) {
        MultivaluedMap<String, Object> h = headerMap();
        stringHeaders.forEach((k, vs) -> vs.forEach(v -> h.add(k, v)));
        return new CassiniClientResponse(status, body, h, stringHeaders);
    }

    @Override public int getStatus() { return status; }

    @Override
    public StatusType getStatusInfo() {
        StatusType s = Status.fromStatusCode(status);
        return s != null ? s : new StatusType() {
            @Override public int getStatusCode() { return status; }
            @Override public String getReasonPhrase() { return ""; }
            @Override public Status.Family getFamily() { return Status.Family.familyOf(status); }
        };
    }

    @Override public Object getEntity() { return body; }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T readEntity(Class<T> type) {
        checkOpen();
        if (type == String.class) return (T) new String(body, charset());
        if (type == byte[].class) return (T) body;
        if (type == InputStream.class) return (T) new ByteArrayInputStream(body);
        if (type == Void.class || type == void.class) return null;
        MediaType mt = contentType();
        if (ClientEntityJsonb.isJson(mt) && !ClientEntityJsonb.isSimple(type)) {
            // §4.2.3: deserialise a JSON body into a POJO through JSON-B, honouring a
            // registered ContextResolver<Jsonb> — mirrors the server-side MBR.
            var jsonb = ClientEntityJsonb.resolve(providers, type, mt);
            return (T) jsonb.fromJson(new ByteArrayInputStream(body), type);
        }
        throw new ProcessingException(
                "Cassini Client: readEntity(" + type.getName() + ") needs a JSON media type or a "
                        + "String/byte[]/InputStream target (Content-Type was " + mt + ").");
    }

    @Override public <T> T readEntity(GenericType<T> type) { return readEntity((Class<T>) type.getRawType()); }
    @Override public <T> T readEntity(Class<T> type, Annotation[] annotations) { return readEntity(type); }
    @Override public <T> T readEntity(GenericType<T> type, Annotation[] annotations) { return readEntity(type); }

    @Override public boolean hasEntity() { checkOpen(); return body.length > 0; }
    @Override public boolean bufferEntity() { checkOpen(); return true; /* already buffered */ }
    @Override public void close() { closed = true; }

    /** §4.2.1 / {@link Response#close()}: a closed response can no longer be read (CASSINI-020). */
    private void checkOpen() {
        if (closed) throw new IllegalStateException("Response closed");
    }

    @Override
    public MediaType getMediaType() {
        String ct = stringHeaders.getFirst("Content-Type");
        return ct == null ? null : MediaType.valueOf(ct);
    }

    @Override
    public Locale getLanguage() {
        String l = stringHeaders.getFirst("Content-Language");
        return l == null ? null : Locale.forLanguageTag(l);
    }

    @Override public int getLength() { return body.length; }
    @Override public Set<String> getAllowedMethods() { return Set.of(); }
    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public EntityTag getEntityTag() { return null; }
    @Override public Date getDate() { return null; }
    @Override public Date getLastModified() { return null; }

    @Override
    public URI getLocation() {
        String l = stringHeaders.getFirst("Location");
        return l == null ? null : URI.create(l);
    }

    @Override public Set<Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public Link getLink(String relation) { return null; }
    @Override public Link.Builder getLinkBuilder(String relation) { return null; }

    @Override public MultivaluedMap<String, Object> getMetadata() { return headers; }
    @Override public MultivaluedMap<String, String> getStringHeaders() { return stringHeaders; }

    @Override
    public String getHeaderString(String name) {
        java.util.List<String> v = stringHeaders.get(name);
        if (v == null || v.isEmpty()) return null;
        return String.join(",", v);
    }

    private java.nio.charset.Charset charset() {
        MediaType mt = getMediaType();
        if (mt == null) return StandardCharsets.UTF_8;
        String cs = mt.getParameters().get(MediaType.CHARSET_PARAMETER);
        try {
            return cs == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(cs);
        } catch (Exception _) {
            return StandardCharsets.UTF_8;
        }
    }
}
