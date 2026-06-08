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

import io.vidocq.cassini.internal.MessageBodyRegistry;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ReaderInterceptor} chain → terminal MBR.readFrom §7.2.
 */
public final class CassiniReaderInterceptorContext implements ReaderInterceptorContext {

    private final List<FilterEntry<ReaderInterceptor>> interceptors;
    private int index = 0;

    @SuppressWarnings("rawtypes")
    private final MessageBodyReader terminal;
    private final MessageBodyRegistry registry;
    private final MultivaluedMap<String, String> headers;
    private final Map<String, Object> properties = new HashMap<>();
    private InputStream stream;
    private Class<?> type;
    private Type genericType;
    private Annotation[] annotations;
    private MediaType mediaType;

    @SuppressWarnings("rawtypes")
    public CassiniReaderInterceptorContext(List<FilterEntry<ReaderInterceptor>> interceptors,
                                           MessageBodyReader terminal, Class<?> type, Type genericType,
                                           Annotation[] annotations, MediaType mediaType,
                                           MultivaluedMap<String, String> headers, InputStream stream) {
        this(interceptors, terminal, null, type, genericType, annotations, mediaType, headers, stream);
    }

    @SuppressWarnings("rawtypes")
    public CassiniReaderInterceptorContext(List<FilterEntry<ReaderInterceptor>> interceptors,
                                           MessageBodyReader terminal, MessageBodyRegistry registry,
                                           Class<?> type, Type genericType,
                                           Annotation[] annotations, MediaType mediaType,
                                           MultivaluedMap<String, String> headers, InputStream stream) {
        this.interceptors = interceptors;
        this.terminal = terminal;
        this.registry = registry;
        this.type = type;
        this.genericType = genericType;
        this.annotations = annotations == null ? new Annotation[0] : annotations;
        this.mediaType = mediaType;
        this.headers = headers;
        this.stream = stream;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public Object proceed() throws IOException {
        if (index < interceptors.size()) {
            ReaderInterceptor i = interceptors.get(index++).instance();
            return i.aroundReadFrom(this);
        }
        // §7.2: setType may have changed the target type; re-select
        // an MBR compatible with the current parameters. The initial terminal
        // may be null if no MBR was found during the initial scan — in that
        // case we rely entirely on the registry (interceptors may have
        // rewritten type/mediaType to match a different MBR).
        MessageBodyReader r = terminal;
        if (r == null || (registry != null && !r.isReadable(type, genericType, annotations, mediaType))) {
            if (registry == null) {
                if (r == null) throw new jakarta.ws.rs.WebApplicationException(
                        "No MessageBodyReader and no registry available", 415);
                // No registry for fallback; try the initial terminal anyway.
            } else {
                r = registry.findReader(type, genericType, annotations, mediaType)
                        .orElse(terminal);
                if (r == null) throw new jakarta.ws.rs.WebApplicationException(
                        "No MessageBodyReader for " + type.getName()
                                + " / " + (mediaType == null ? "*/*" : mediaType.toString()), 415);
            }
        }
        return r.readFrom(type, genericType, annotations, mediaType, headers, stream);
    }

    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public java.util.Collection<String> getPropertyNames() { return properties.keySet(); }
    @Override public void setProperty(String name, Object value) { properties.put(name, value); }
    @Override public void removeProperty(String name) { properties.remove(name); }

    @Override public Annotation[] getAnnotations() { return annotations; }
    @Override public void setAnnotations(Annotation[] a) {
        if (a == null) throw new NullPointerException("annotations is null");
        this.annotations = a;
    }
    @Override public Class<?> getType() { return type; }
    @Override public void setType(Class<?> t) { this.type = t; }
    @Override public Type getGenericType() { return genericType; }
    @Override public void setGenericType(Type t) { this.genericType = t; }
    @Override public MediaType getMediaType() { return mediaType; }
    @Override public void setMediaType(MediaType m) { this.mediaType = m; }

    @Override public InputStream getInputStream() { return stream; }
    @Override public void setInputStream(InputStream is) { this.stream = is; }
    @Override public MultivaluedMap<String, String> getHeaders() { return headers; }
}
