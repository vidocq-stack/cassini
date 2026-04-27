package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;

import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * Implémentation {@link Providers} §10.2 — façade sur les registries
 * internes Cassini pour les providers MBR/MBW et ExceptionMapper.
 * Les ContextResolver ne sont pas encore enregistrés (getContextResolver
 * retourne null).
 */
public final class CassiniProviders implements Providers {

    private final MessageBodyRegistry bodies;
    private final ExceptionMapperRegistry exceptionMappers;
    private final java.util.List<ContextResolver<?>> contextResolvers;

    public CassiniProviders(MessageBodyRegistry bodies, ExceptionMapperRegistry exceptionMappers) {
        this(bodies, exceptionMappers, java.util.List.of());
    }

    public CassiniProviders(MessageBodyRegistry bodies, ExceptionMapperRegistry exceptionMappers,
                            java.util.List<ContextResolver<?>> contextResolvers) {
        this.bodies = bodies;
        this.exceptionMappers = exceptionMappers;
        this.contextResolvers = contextResolvers;
    }

    @Override
    public <T> MessageBodyReader<T> getMessageBodyReader(Class<T> type, Type genericType,
                                                         Annotation[] annotations, MediaType mediaType) {
        return bodies.<T>findReader(type, genericType, annotations, mediaType).orElse(null);
    }

    @Override
    public <T> MessageBodyWriter<T> getMessageBodyWriter(Class<T> type, Type genericType,
                                                         Annotation[] annotations, MediaType mediaType) {
        return bodies.<T>findWriter(type, genericType, annotations, mediaType).orElse(null);
    }

    @Override
    public <T extends Throwable> ExceptionMapper<T> getExceptionMapper(Class<T> type) {
        return exceptionMappers.findMapper(type);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public <T> ContextResolver<T> getContextResolver(Class<T> contextType, MediaType mediaType) {
        for (ContextResolver<?> cr : contextResolvers) {
            Class<?> param = resolveContextType(cr.getClass());
            if (param == null) continue;
            if (!contextType.isAssignableFrom(param)) continue;
            // Vérifie mediaType compat via @Produces sur cr
            jakarta.ws.rs.Produces prod = cr.getClass().getAnnotation(jakarta.ws.rs.Produces.class);
            if (prod != null && mediaType != null) {
                boolean ok = false;
                for (String mt : prod.value()) {
                    MediaType declared = io.vidocq.cassini.internal.MediaTypes.parse(mt);
                    if (io.vidocq.cassini.internal.MediaTypes.matches(declared, mediaType)) {
                        ok = true; break;
                    }
                }
                if (!ok) continue;
            }
            return (ContextResolver<T>) cr;
        }
        return null;
    }

    private static Class<?> resolveContextType(Class<?> cls) {
        for (java.lang.reflect.Type iface : cls.getGenericInterfaces()) {
            if (iface instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getRawType() == ContextResolver.class
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c) {
                return c;
            }
        }
        Class<?> sup = cls.getSuperclass();
        return sup == null || sup == Object.class ? null : resolveContextType(sup);
    }
}
