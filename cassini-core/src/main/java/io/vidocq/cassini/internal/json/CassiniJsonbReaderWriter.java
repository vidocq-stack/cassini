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
package io.vidocq.cassini.internal.json;

import io.vidocq.cassini.internal.ParamExtractor;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * §4.2.3 / Core Profile: MBR/MBW for {@code application/json} (and the
 * compatible suffix {@code application/*+json}) backed by Jakarta JSON
 * Binding (Yasson).
 *
 * <p>§9.2: if the application provides a {@link ContextResolver}{@code <Jsonb>}
 * for the target type, its {@link Jsonb} is used. Otherwise, a default instance
 * created via {@link JsonbBuilder#create()} is used.</p>
 */
@Consumes({"application/json", "application/*+json", "text/json"})
@Produces({"application/json", "application/*+json", "text/json"})
public final class CassiniJsonbReaderWriter
        implements MessageBodyReader<Object>, MessageBodyWriter<Object> {

    private static final Jsonb DEFAULT = JsonbBuilder.create();

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJson(mediaType) && !isExcluded(type);
    }

    @Override
    public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations,
                           MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                           InputStream entityStream) throws IOException, WebApplicationException {
        // MessageBodyReader#readFrom: a type with no zero-length representation
        // answers an empty stream with a NoContentException, which the server
        // runtime turns into a 400 (§4.2.4) — instead of letting the JSON parser
        // fail on empty input. A record or a POJO has no such representation.
        // Jersey makes the same choice; RESTEasy returns null. cassini#39.
        PushbackInputStream in = new PushbackInputStream(entityStream, 1);
        int first = in.read();
        if (first == -1) {
            throw new NoContentException("Empty JSON entity");
        }
        in.unread(first);
        Jsonb jsonb = resolveJsonb(type, mediaType);
        return jsonb.fromJson(in, genericType == null ? type : genericType);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJson(mediaType) && !isExcluded(type);
    }

    @Override
    public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        Jsonb jsonb = resolveJsonb(type, mediaType);
        jsonb.toJson(o, genericType == null ? type : genericType, entityStream);
    }

    private Jsonb resolveJsonb(Class<?> type, MediaType mt) {
        // Direct lookup via the ThreadLocal Providers exposed by ParamExtractor —
        // avoids depending on the @Context injection cycle (which may not
        // occur on some code paths, notably writeFromContext).
        Providers providers = ParamExtractor.currentProviders();
        if (providers != null) {
            try {
                ContextResolver<Jsonb> r = providers.getContextResolver(Jsonb.class, mt);
                if (r != null) {
                    Jsonb j = r.getContext(type);
                    if (j != null) return j;
                }
            } catch (RuntimeException ignored) {}
        }
        return DEFAULT;
    }

    private static boolean isJson(MediaType mt) {
        if (mt == null) return false;
        // Reject wildcards (*/* or type/*): §4.2.3 Core Profile, the built-in
        // JSON-B MBR/MBW must only engage on explicit JSON media types so it
        // does not shadow user-provided MBWs without @Produces (= wildcard).
        if (mt.isWildcardType() || mt.isWildcardSubtype()) return false;
        if (mt.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return true;
        // +json suffix (RFC 6839): application/foo+json, etc.
        String sub = mt.getSubtype();
        return sub != null && sub.toLowerCase().endsWith("+json");
    }

    /** Avoid capturing types that have a dedicated MBR/MBW — otherwise the
     *  Cassini scan could prioritize this JSON over built-ins (String, byte[],
     *  InputStream, etc.) due to @Consumes/@Produces matching.
     *  §4.2.4: use isAssignableFrom to also cover subtypes
     *  (e.g. ByteArrayInputStream, FileInputStream...). */
    private static boolean isExcluded(Class<?> type) {
        if (type == null) return true;
        if (type == byte[].class) return true;
        if (CharSequence.class.isAssignableFrom(type)) return true;
        if (InputStream.class.isAssignableFrom(type)) return true;
        if (java.io.Reader.class.isAssignableFrom(type)) return true;
        if (jakarta.ws.rs.core.StreamingOutput.class.isAssignableFrom(type)) return true;
        if (java.io.File.class.isAssignableFrom(type)) return true;
        if (javax.xml.transform.Source.class.isAssignableFrom(type)) return true;
        // §4.2.3: DataSource has its own dedicated MBR/MBW.
        try {
            Class<?> dataSource = Class.forName("jakarta.activation.DataSource");
            if (dataSource.isAssignableFrom(type)) return true;
        } catch (ClassNotFoundException ignored) {}
        return false;
    }
}
