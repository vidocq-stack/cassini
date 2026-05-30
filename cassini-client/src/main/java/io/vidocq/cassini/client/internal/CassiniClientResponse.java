/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Client-side {@link Response} implementation — wraps a {@code byte[]} body and exposes
 * {@code readEntity(Class<T>)} for {@code String}, {@code byte[]}, {@code InputStream}.
 * Complex types (JSON-B, XML…) will arrive with the {@code MessageBodyRegistry}
 * integration in commit #3.
 */
final class CassiniClientResponse extends Response {

    private final int status;
    private final byte[] body;
    private final MultivaluedMap<String, Object> headers;
    private final MultivaluedMap<String, String> stringHeaders;
    private boolean closed;

    private CassiniClientResponse(int status, byte[] body,
                                   MultivaluedMap<String, Object> headers,
                                   MultivaluedMap<String, String> stringHeaders) {
        this.status = status;
        this.body = body == null ? new byte[0] : body;
        this.headers = headers;
        this.stringHeaders = stringHeaders;
    }

    static CassiniClientResponse from(HttpResponse<byte[]> response) {
        MultivaluedMap<String, Object> h = new MultivaluedHashMap<>();
        MultivaluedMap<String, String> hs = new MultivaluedHashMap<>();
        response.headers().map().forEach((k, vs) -> {
            for (String v : vs) {
                h.add(k, v);
                hs.add(k, v);
            }
        });
        return new CassiniClientResponse(response.statusCode(), response.body(), h, hs);
    }

    /**
     * Builds a {@code CassiniClientResponse} from the data of a
     * {@link CassiniClientResponseContext} after the {@code ClientResponseFilter}
     * chain has been applied. Headers are already in String form and may have
     * been mutated by the filters.
     */
    static CassiniClientResponse fromBufferedBody(int status, byte[] body,
                                                    MultivaluedMap<String, String> stringHeaders) {
        MultivaluedMap<String, Object> h = new MultivaluedHashMap<>();
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
        if (type == String.class) return (T) new String(body, charset());
        if (type == byte[].class) return (T) body;
        if (type == InputStream.class) return (T) new ByteArrayInputStream(body);
        if (type == Void.class || type == void.class) return null;
        throw new UnsupportedOperationException(
                "Cassini Client MVP supports readEntity(String/byte[]/InputStream) only — got "
                        + type.getName() + ". MessageBodyRegistry integration arrives in commit #3.");
    }

    @Override public <T> T readEntity(GenericType<T> type) { return readEntity((Class<T>) type.getRawType()); }
    @Override public <T> T readEntity(Class<T> type, Annotation[] annotations) { return readEntity(type); }
    @Override public <T> T readEntity(GenericType<T> type, Annotation[] annotations) { return readEntity(type); }

    @Override public boolean hasEntity() { return body != null && body.length > 0; }
    @Override public boolean bufferEntity() { return true; /* already buffered */ }
    @Override public void close() { closed = true; }

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

    @Override public int getLength() { return body == null ? -1 : body.length; }
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
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }
}
