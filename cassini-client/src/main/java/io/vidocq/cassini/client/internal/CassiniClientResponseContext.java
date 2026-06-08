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

import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NewCookie;
import jakarta.ws.rs.core.Response;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Mutable implementation of {@link ClientResponseContext} — feeds the
 * {@link jakarta.ws.rs.client.ClientResponseFilter ClientResponseFilters} that
 * may read/modify the status, headers, and entity stream.
 *
 * <p>The body is buffered once into a {@code byte[]} when the response arrives;
 * each call to {@code getEntityStream()} then returns a fresh
 * {@link ByteArrayInputStream} view — allowing multiple reads by several filters
 * without having to call {@code bufferEntity()} manually.</p>
 */
final class CassiniClientResponseContext implements ClientResponseContext {

    private int status;
    private Response.StatusType statusInfo;
    private final MultivaluedMap<String, String> headers;
    private byte[] body;
    private InputStream entityStreamOverride;

    private CassiniClientResponseContext(int status, byte[] body,
                                          MultivaluedMap<String, String> headers) {
        this.status = status;
        this.statusInfo = Response.Status.fromStatusCode(status);
        this.headers = headers;
        this.body = body == null ? new byte[0] : body;
    }

    static CassiniClientResponseContext from(HttpResponse<byte[]> response) {
        MultivaluedMap<String, String> hs = new MultivaluedHashMap<>();
        response.headers().map().forEach((k, vs) -> vs.forEach(v -> hs.add(k, v)));
        return new CassiniClientResponseContext(response.statusCode(), response.body(), hs);
    }

    static CassiniClientResponseContext fromAborted(Response response) {
        MultivaluedMap<String, String> hs = new MultivaluedHashMap<>();
        response.getStringHeaders().forEach((k, vs) -> vs.forEach(v -> hs.add(k, v)));
        Object entity = response.getEntity();
        byte[] body;
        if (entity == null) body = new byte[0];
        else if (entity instanceof byte[] b) body = b;
        else body = entity.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new CassiniClientResponseContext(response.getStatus(), body, hs);
    }

    byte[] body() {
        if (entityStreamOverride != null) {
            try {
                body = entityStreamOverride.readAllBytes();
                entityStreamOverride = null;
            } catch (java.io.IOException e) {
                throw new jakarta.ws.rs.ProcessingException("Failed to drain entity stream", e);
            }
        }
        return body;
    }

    @Override public int getStatus() { return status; }
    @Override public void setStatus(int status) {
        this.status = status;
        this.statusInfo = Response.Status.fromStatusCode(status);
    }

    @Override public Response.StatusType getStatusInfo() { return statusInfo; }
    @Override public void setStatusInfo(Response.StatusType statusInfo) {
        this.statusInfo = statusInfo;
        if (statusInfo != null) this.status = statusInfo.getStatusCode();
    }

    @Override public MultivaluedMap<String, String> getHeaders() { return headers; }

    @Override
    public String getHeaderString(String name) {
        List<String> vs = headers.get(name);
        if (vs == null || vs.isEmpty()) return null;
        return String.join(",", vs);
    }

    @Override
    public boolean containsHeaderString(String name, String valueSeparatorRegex, Predicate<String> valuePredicate) {
        String h = getHeaderString(name);
        if (h == null) return false;
        for (String tok : h.split(valueSeparatorRegex)) {
            if (valuePredicate.test(tok.trim())) return true;
        }
        return false;
    }

    @Override public Set<String> getAllowedMethods() { return Set.of(); }
    @Override public Date getDate() { return null; }
    @Override public Locale getLanguage() { return null; }
    @Override public int getLength() { return body().length; }

    @Override
    public MediaType getMediaType() {
        String ct = getHeaderString("Content-Type");
        return ct == null ? null : MediaType.valueOf(ct);
    }

    @Override public Map<String, NewCookie> getCookies() { return Map.of(); }
    @Override public EntityTag getEntityTag() { return null; }
    @Override public Date getLastModified() { return null; }

    @Override
    public URI getLocation() {
        String l = getHeaderString("Location");
        return l == null ? null : URI.create(l);
    }

    @Override public Set<Link> getLinks() { return Set.of(); }
    @Override public boolean hasLink(String relation) { return false; }
    @Override public Link getLink(String relation) { return null; }
    @Override public Link.Builder getLinkBuilder(String relation) { return null; }

    @Override public boolean hasEntity() {
        if (entityStreamOverride != null) return true;
        return body != null && body.length > 0;
    }

    @Override
    public InputStream getEntityStream() {
        if (entityStreamOverride != null) return entityStreamOverride;
        return new ByteArrayInputStream(body);
    }

    @Override
    public void setEntityStream(InputStream input) { this.entityStreamOverride = input; }

    /** Final conversion to {@link CassiniClientResponse} after applying the pipeline. */
    Response toResponse() {
        return CassiniClientResponse.fromBufferedBody(status, body(), headers);
    }
}
