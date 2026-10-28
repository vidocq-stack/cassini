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
package io.vidocq.cassini.internal.multipart;

import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** §3.5.4: {@link EntityPart.Builder} implementation. */
public final class CassiniEntityPartBuilder implements EntityPart.Builder {

    private final String name;
    private String fileName;
    /** The media type given to {@link #mediaType}, or {@code null} for the default. */
    private MediaType mediaType;
    private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    private byte[] content;

    public CassiniEntityPartBuilder(String name) {
        this.name = requireArgument(name, "name");
    }

    @Override public EntityPart.Builder mediaType(MediaType mt) {
        this.mediaType = requireArgument(mt, "mediaType");
        return this;
    }
    @Override public EntityPart.Builder mediaType(String mt) {
        this.mediaType = MediaType.valueOf(requireArgument(mt, "mediaType"));
        return this;
    }
    /** Replaces any value previously added under {@code n} (EntityPart.Builder contract). */
    @Override public EntityPart.Builder header(String n, String... v) {
        headers.put(requireArgument(n, "headerName"), new java.util.ArrayList<>(java.util.Arrays.asList(v)));
        return this;
    }
    @Override public EntityPart.Builder headers(MultivaluedMap<String, String> h) {
        requireArgument(h, "headers").forEach((n, v) -> header(n, v.toArray(String[]::new)));
        return this;
    }
    @Override public EntityPart.Builder fileName(String fn) {
        this.fileName = requireArgument(fn, "fileName");
        return this;
    }

    @Override public EntityPart.Builder content(InputStream is) throws IllegalArgumentException {
        if (is == null) throw new IllegalArgumentException("content stream is null");
        try { this.content = is.readAllBytes(); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
        return this;
    }

    @Override public EntityPart.Builder content(String fileName, InputStream is) throws IllegalArgumentException {
        requireArgument(is, "content");
        return fileName(fileName).content(is);
    }

    @Override public <T> EntityPart.Builder content(T content, Class<? extends T> type) throws IllegalArgumentException {
        return contentImpl(content);
    }

    @Override public <T> EntityPart.Builder content(T content, GenericType<T> type) throws IllegalArgumentException {
        return contentImpl(content);
    }

    @Override public EntityPart.Builder content(Object content) throws IllegalArgumentException {
        return contentImpl(content);
    }

    private EntityPart.Builder contentImpl(Object value) {
        if (value == null) throw new IllegalArgumentException("content is null");
        if (value instanceof byte[] b) { this.content = b; return this; }
        if (value instanceof String s) { this.content = s.getBytes(StandardCharsets.UTF_8); return this; }
        if (value instanceof InputStream is) {
            try { this.content = is.readAllBytes(); }
            catch (IOException e) { throw new IllegalArgumentException(e); }
            return this;
        }
        if (value instanceof java.io.File f) {
            try (InputStream fis = new java.io.FileInputStream(f)) {
                this.content = fis.readAllBytes();
                if (fileName == null) fileName = f.getName();
            } catch (IOException e) { throw new IllegalArgumentException(e); }
            return this;
        }
        // Fallback: toString → bytes
        this.content = value.toString().getBytes(StandardCharsets.UTF_8);
        return this;
    }

    /**
     * @throws IllegalStateException when no content was given (EntityPart.Builder contract)
     */
    @Override public EntityPart build() {
        if (content == null) throw new IllegalStateException("EntityPart '" + name + "' has no content");
        return new CassiniEntityPart(name, fileName, effectiveMediaType(), headers, content);
    }

    /** text/plain by default, application/octet-stream once a file name is set (CASSINI-027). */
    private MediaType effectiveMediaType() {
        if (mediaType != null) return mediaType;
        return fileName == null ? MediaType.TEXT_PLAIN_TYPE : MediaType.APPLICATION_OCTET_STREAM_TYPE;
    }

    private static <T> T requireArgument(T value, String what) {
        if (value == null) throw new IllegalArgumentException(what + " must not be null");
        return value;
    }
}
