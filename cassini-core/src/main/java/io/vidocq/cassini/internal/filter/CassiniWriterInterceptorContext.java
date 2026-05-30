package io.vidocq.cassini.internal.filter;

import io.vidocq.cassini.internal.MessageBodyRegistry;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link WriterInterceptor} chain → terminal MBW.writeTo §7.2.
 * Each interceptor receives this context, mutates it as needed, then
 * calls {@link #proceed()} which advances the index. When the index
 * reaches the end of the list, the final {@link MessageBodyWriter}
 * is invoked.
 */
public final class CassiniWriterInterceptorContext implements WriterInterceptorContext {

    private final List<FilterEntry<WriterInterceptor>> interceptors;
    private int index = 0;

    @SuppressWarnings("rawtypes")
    private final MessageBodyWriter terminal;
    private final MessageBodyRegistry registry;
    private final MultivaluedMap<String, Object> headers;
    private final Map<String, Object> properties = new HashMap<>();
    private OutputStream stream;
    private Object entity;
    private Class<?> type;
    private Type genericType;
    private Annotation[] annotations;
    private MediaType mediaType;

    @SuppressWarnings("rawtypes")
    public CassiniWriterInterceptorContext(List<FilterEntry<WriterInterceptor>> interceptors,
                                           MessageBodyWriter terminal, Object entity,
                                           Class<?> type, Type genericType, Annotation[] annotations,
                                           MediaType mediaType, MultivaluedMap<String, Object> headers,
                                           OutputStream stream) {
        this(interceptors, terminal, null, entity, type, genericType, annotations, mediaType, headers, stream);
    }

    @SuppressWarnings("rawtypes")
    public CassiniWriterInterceptorContext(List<FilterEntry<WriterInterceptor>> interceptors,
                                           MessageBodyWriter terminal, MessageBodyRegistry registry,
                                           Object entity, Class<?> type, Type genericType,
                                           Annotation[] annotations, MediaType mediaType,
                                           MultivaluedMap<String, Object> headers, OutputStream stream) {
        this.interceptors = interceptors;
        this.terminal = terminal;
        this.registry = registry;
        this.entity = entity;
        this.type = type;
        this.genericType = genericType;
        this.annotations = annotations == null ? new Annotation[0] : annotations;
        this.mediaType = mediaType;
        this.headers = headers;
        this.stream = stream;
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void proceed() throws IOException {
        if (index < interceptors.size()) {
            WriterInterceptor i = interceptors.get(index++).instance();
            i.aroundWriteTo(this);
        } else {
            // §7.2: setEntity/setType may have changed the type during the
            // chain; re-select a compatible MBW if the initial terminal is
            // no longer suitable.
            MessageBodyWriter w = terminal;
            if (entity != null && registry != null && !w.isWriteable(type, genericType, annotations, mediaType)) {
                w = registry.findWriter(type, genericType, annotations, mediaType).orElse(terminal);
            }
            w.writeTo(entity, type, genericType, annotations, mediaType, headers, stream);
        }
    }

    @Override public Object getProperty(String name) { return properties.get(name); }
    @Override public java.util.Collection<String> getPropertyNames() { return properties.keySet(); }
    @Override public void setProperty(String name, Object value) { properties.put(name, value); }
    @Override public void removeProperty(String name) { properties.remove(name); }

    @Override public Annotation[] getAnnotations() { return annotations; }
    @Override public void setAnnotations(Annotation[] a) {
        // §7.2: setAnnotations(null) must throw NullPointerException.
        if (a == null) throw new NullPointerException("annotations is null");
        this.annotations = a;
    }
    @Override public Class<?> getType() { return type; }
    @Override public void setType(Class<?> t) { this.type = t; }
    @Override public Type getGenericType() { return genericType; }
    @Override public void setGenericType(Type t) { this.genericType = t; }
    @Override public MediaType getMediaType() { return mediaType; }
    @Override public void setMediaType(MediaType m) { this.mediaType = m; }

    @Override public Object getEntity() { return entity; }
    @Override public void setEntity(Object entity) {
        this.entity = entity;
        if (entity != null) {
            this.type = entity.getClass();
            this.genericType = entity.getClass();
        }
    }

    @Override public OutputStream getOutputStream() { return stream; }
    @Override public void setOutputStream(OutputStream os) { this.stream = os; }
    @Override public MultivaluedMap<String, Object> getHeaders() { return headers; }
}
