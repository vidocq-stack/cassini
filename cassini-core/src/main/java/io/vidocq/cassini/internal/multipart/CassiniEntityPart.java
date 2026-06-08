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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/** §3.5.4 EntityPart: representation of a multipart/form-data part. */
public final class CassiniEntityPart implements EntityPart {

    private final String name;
    private final String fileName;
    private final MediaType mediaType;
    private final MultivaluedMap<String, String> headers;
    private final byte[] content;
    private boolean consumed;

    CassiniEntityPart(String name, String fileName, MediaType mediaType,
                      MultivaluedMap<String, String> headers, byte[] content) {
        this.name = name;
        this.fileName = fileName;
        this.mediaType = mediaType == null ? MediaType.TEXT_PLAIN_TYPE : mediaType;
        this.headers = headers == null ? new MultivaluedHashMap<>() : headers;
        this.content = content == null ? new byte[0] : content;
    }

    @Override public String getName() { return name; }
    @Override public Optional<String> getFileName() { return Optional.ofNullable(fileName); }
    @Override public MediaType getMediaType() { return mediaType; }
    @Override public MultivaluedMap<String, String> getHeaders() { return headers; }

    public byte[] rawContent() { return content; }

    @Override
    public synchronized InputStream getContent() {
        if (consumed) throw new IllegalStateException("EntityPart content already consumed");
        consumed = true;
        return new ByteArrayInputStream(content);
    }

    @Override
    public synchronized <T> T getContent(Class<T> type) throws IOException {
        if (consumed) throw new IllegalStateException("EntityPart content already consumed");
        consumed = true;
        return convert(type, content);
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T> T getContent(GenericType<T> type) throws IOException {
        if (consumed) throw new IllegalStateException("EntityPart content already consumed");
        consumed = true;
        Class<?> raw = type.getRawType();
        return (T) convert(raw, content);
    }

    @SuppressWarnings("unchecked")
    private static <T> T convert(Class<T> type, byte[] bytes) {
        if (type == byte[].class) return (T) bytes;
        if (type == String.class) return (T) new String(bytes, StandardCharsets.UTF_8);
        if (type == InputStream.class) return (T) new ByteArrayInputStream(bytes);
        // Best-effort fallback: if the type has a (String) constructor or is CharSequence
        if (CharSequence.class.isAssignableFrom(type)) return (T) new String(bytes, StandardCharsets.UTF_8);
        throw new IllegalArgumentException("Unsupported EntityPart content type: " + type.getName());
    }
}
