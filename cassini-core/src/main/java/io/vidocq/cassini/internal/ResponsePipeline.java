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
package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;
import io.vidocq.cassini.internal.filter.CassiniReaderInterceptorContext;
import io.vidocq.cassini.internal.filter.CassiniRequestContext;
import io.vidocq.cassini.internal.filter.CassiniResponseContext;
import io.vidocq.cassini.internal.filter.CassiniWriterInterceptorContext;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

/**
 * Entity I/O and response-writing pipeline, extracted from {@link Invoker}
 * (which stays the facade and delegates): body reading through
 * {@link MessageBodyReader} + ReaderInterceptors, result marshalling through
 * {@link MessageBodyWriter} + WriterInterceptors, ContainerResponseFilter
 * execution, and the JAX-RS {@code Response} unwrapping rules (§4.2.4,
 * §6.7.4, §7.2).
 */
final class ResponsePipeline {

    private final Invoker host;

    ResponsePipeline(Invoker host) {
        this.host = host;
    }

    CassiniHttpResponse runResponseFiltersAndWrite(CassiniRequestContext rctx,
                                                jakarta.ws.rs.core.Response userResp,
                                                ResourceMethod route, MediaType chosen) throws IOException {
        int status = userResp.getStatus();
        Object entity = userResp.getEntity();
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        for (var e : userResp.getStringHeaders().entrySet()) for (String v : e.getValue()) headers.add(e.getKey(), v);

        CassiniResponseContext rctx2 = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), headers);
        for (var fe : host.filters().responseFilters()) {
            // route null = pre-matching abort/exception §6.5.2: only globally
            // bound filters (without @NameBinding) apply.
            // appliesTo(null,null) returns true for globals and false
            // for NameBound ones — rely on that for filtering.
            if (route == null) {
                if (!fe.appliesTo(null, null)) continue;
            } else {
                if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
            }
            Throwable[] err = new Throwable[1];
            rctx.runDuringResponsePhase(() -> {
                try { fe.instance().filter(rctx, rctx2); }
                catch (java.io.IOException | RuntimeException e) { err[0] = e; }
            });
            if (err[0] instanceof RuntimeException re) throw re;
            if (err[0] != null) throw new RuntimeException(err[0]);
        }
        return writeFromContext(rctx2, route, chosen);
    }

    CassiniHttpResponse runResponseFiltersForResult(CassiniRequestContext rctx, Object result,
                                                  ResourceMethod route, MediaType chosen) throws IOException {
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        int status = (result == null) ? 204 : 200;
        Object entity = (result instanceof jakarta.ws.rs.core.Response jr) ? jr.getEntity() : result;
        java.lang.annotation.Annotation[] entityAnnotations = null;
        if (result instanceof jakarta.ws.rs.core.Response jr2) {
            status = jr2.getStatus();
            for (var e : jr2.getStringHeaders().entrySet()) for (String v : e.getValue()) headers.add(e.getKey(), v);
            // Entity annotations are internal to CassiniResponse
            // (§6.7.4: exposed through ContainerResponseContext.getEntityAnnotations).
            if (jr2 instanceof io.vidocq.cassini.internal.runtime.CassiniResponse cr) {
                entityAnnotations = cr.entityAnnotations();
            }
        }
        // §6.7.4: getEntityAnnotations() returns the resource-method annotations
        // merged with those explicitly passed to
        // ResponseBuilder.entity(Object, Annotation[]).
        if (route.javaMethod() != null) {
            Annotation[] methodAnns = route.javaMethod().getAnnotations();
            if (entityAnnotations == null || entityAnnotations.length == 0) {
                entityAnnotations = methodAnns;
            } else {
                Annotation[] merged = new Annotation[methodAnns.length + entityAnnotations.length];
                System.arraycopy(methodAnns, 0, merged, 0, methodAnns.length);
                System.arraycopy(entityAnnotations, 0, merged, methodAnns.length, entityAnnotations.length);
                entityAnnotations = merged;
            }
        }

        CassiniResponseContext rctx2 = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), entityAnnotations, headers);
        for (var fe : host.filters().responseFilters()) {
            if (!fe.appliesTo(route.javaMethod(), route.beanClass())) continue;
            Throwable[] err = new Throwable[1];
            rctx.runDuringResponsePhase(() -> {
                try { fe.instance().filter(rctx, rctx2); }
                catch (java.io.IOException | RuntimeException e) { err[0] = e; }
            });
            if (err[0] instanceof RuntimeException re) throw re;
            if (err[0] != null) throw new RuntimeException(err[0]);
        }
        return writeFromContext(rctx2, route, chosen);
    }

    private CassiniHttpResponse writeFromContext(CassiniResponseContext rctx, ResourceMethod route, MediaType chosen) throws IOException {
        Object entity = rctx.getEntity();
        int status = rctx.getStatus();
        MultivaluedMap<String, Object> headers = rctx.getHeaders();
        if (entity == null) {
            var b = CassiniHttpResponse.builder().status(status).body(new byte[0]);
            for (var e : headers.entrySet()) for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
            return b.build();
        }
        MediaType mt = rctx.getMediaType() != null ? rctx.getMediaType() : chosen;
        // §6.7.4.2: if a ContainerResponseFilter wrapped the entityStream
        // via setEntityStream(), MBW.writeTo must write into that wrapper —
        // the wrapper forwards to the originalStream collected by the runtime.
        if (rctx.getEntityStream() != rctx.originalStream()) {
            Class<?> type = entity.getClass();
            Type gt = rctx.getEntityType() == null ? type : rctx.getEntityType();
            Annotation[] anns = rctx.getEntityAnnotations() == null
                    ? new Annotation[0] : rctx.getEntityAnnotations();
            @SuppressWarnings({"rawtypes", "unchecked"})
            jakarta.ws.rs.ext.MessageBodyWriter writer = host.registry().findWriter(type, gt, anns, mt)
                    .orElseThrow(() -> new WebApplicationException(
                            "No MessageBodyWriter for " + type.getName() + " / " + MediaTypes.format(mt), 500));
            host.injectProviderContexts(writer, null);
            MultivaluedMap<String, Object> outHeaders = MessageBodyRegistry.outHeaders();
            for (var e : headers.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
                for (Object v : e.getValue()) outHeaders.add(e.getKey(), v);
            }
            MessageBodyRegistry.writeTo(writer, entity, type, gt, anns, mt, outHeaders, rctx.getEntityStream());
            try { rctx.getEntityStream().close(); } catch (IOException ignored) {}
            byte[] body = rctx.originalStream().toByteArray();
            var b = CassiniHttpResponse.builder().status(status).body(body);
            b.header("Content-Type", MediaTypes.format(mt));
            for (var e : outHeaders.entrySet()) {
                if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
                for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
            }
            return b.build();
        }
        // Strip Content-Type from headers map (re-added by writeEntity)
        java.util.Map<String, java.util.List<String>> extra = new java.util.LinkedHashMap<>();
        for (var e : headers.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            java.util.List<String> vs = new java.util.ArrayList<>();
            for (Object v : e.getValue()) vs.add(String.valueOf(v));
            extra.put(e.getKey(), vs);
        }
        return writeEntity(entity, rctx.getEntityType() == null ? entity.getClass() : rctx.getEntityType(),
                rctx.getEntityAnnotations(), mt, status, extra, route);
    }

    Object readEntity(Parameter p, MediaType ct, CassiniHttpExchange request, ResourceMethod route) throws IOException {
        Class<?> type = p.getType();
        Type genericType = p.getParameterizedType();
        Annotation[] anns = p.getAnnotations();
        MultivaluedMap<String, String> headers = MessageBodyRegistry.adaptExchangeHeaders(request.requestHeaders());

        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyReader reader = host.registry().findReader(type, genericType, anns, ct).orElse(null);
        // §7.2: if no MBR matches initially but ReaderInterceptors are
        // registered, delay selection — an interceptor may rewrite
        // type/mediaType (setType, setMediaType) to match a different MBR.
        var rInterceptorsForChoice = route == null ? host.filters().readerInterceptorsFor(null, null)
                : host.filters().readerInterceptorsFor(route.javaMethod(), route.beanClass());
        if (reader == null && rInterceptorsForChoice.isEmpty()) {
            throw new WebApplicationException(
                    "No MessageBodyReader for " + type.getName() + " / " + MediaTypes.format(ct), 415);
        }
        // §9.2: @Context fields of user-level providers (singletons) are
        // re-injected on each call to expose the current context.
        if (reader != null) host.injectProviderContexts(reader, request);
        // If @FormParam already consumed the body, replay it from cache (exchange attribute, M2h).
        byte[] cached = (byte[]) request.getAttribute(FieldInjector.ATTR_BODY_CACHE);
        InputStream src;
        if (cached != null) {
            src = new java.io.ByteArrayInputStream(cached);
        } else {
            byte[] all = request.requestBody() == null ? new byte[0] : request.requestBody().readAllBytes();
            request.setAttribute(FieldInjector.ATTR_BODY_CACHE, all);
            src = new java.io.ByteArrayInputStream(all);
        }
        var rInterceptors = rInterceptorsForChoice;
        try (InputStream in = src) {
            if (rInterceptors.isEmpty()) {
                return reader.readFrom(type, genericType, anns, ct, headers, in);
            }
            return new CassiniReaderInterceptorContext(rInterceptors,
                    reader, host.registry(), type, genericType, anns, ct, headers, in).proceed();
        }
    }

    CassiniHttpResponse marshal(Object result, ResourceMethod route, MediaType chosen) throws IOException {
        if (result == null) {
            return CassiniHttpResponse.status(204);
        }
        if (result instanceof jakarta.ws.rs.core.Response jr) {
            return fromJaxRs(jr, route, chosen);
        }
        return writeEntity(result, route.javaMethod().getGenericReturnType(),
                route.javaMethod().getAnnotations(), chosen, 200, Map.of(), route);
    }

    private CassiniHttpResponse writeEntity(Object entity, Type genericType, Annotation[] anns,
                                 MediaType chosen, int status,
                                 Map<String, List<String>> extraHeaders, ResourceMethod route) throws IOException {
        // §4.2.4: GenericEntity describes a parameterized type; unwrap it and
        // use the effective "raw"/"genericType" for the MBW.
        if (entity instanceof jakarta.ws.rs.core.GenericEntity<?> ge) {
            genericType = ge.getType();
            entity = ge.getEntity();
        }
        Class<?> type = entity.getClass();
        MediaType mtSelect = defaultFor(chosen, type);
        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyWriter writer = host.registry().findWriter(type, genericType, anns, mtSelect)
                .orElseThrow(() -> new WebApplicationException(
                        "No MessageBodyWriter for " + type.getName() + " / " + MediaTypes.format(mtSelect), 500));
        // §4.2.4: if the method did not specify Content-Type, inherit it from
        // the selected MBW's @Produces (first declared concrete media type).
        MediaType mt = mtSelect;
        if (mt.isWildcardType()) {
            jakarta.ws.rs.Produces wp = writer.getClass().getAnnotation(jakarta.ws.rs.Produces.class);
            if (wp != null && wp.value().length > 0) {
                MediaType inferred = MediaType.valueOf(wp.value()[0]);
                if (!inferred.isWildcardType()) mt = inferred;
            }
        }
        final MediaType finalMt = mt;
        host.injectProviderContexts(writer, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MultivaluedMap<String, Object> outHeaders = MessageBodyRegistry.outHeaders();
        // Populate outHeaders with extraHeaders BEFORE the interceptor chain
        // so WriterInterceptor.aroundWriteTo.getHeaders() sees what the
        // ResourceMethod/ResponseBuilder produced. Interceptors
        // may still mutate it; read it back afterwards for the build.
        for (var e : extraHeaders.entrySet())
            for (String v : e.getValue()) outHeaders.add(e.getKey(), v);
        // §6.5.2: if route==null (pre-matching abort/exception), only globally
        // bound writer interceptors apply.
        var wInterceptors = route == null ? host.filters().writerInterceptorsFor(null, null)
                : host.filters().writerInterceptorsFor(route.javaMethod(), route.beanClass());
        if (wInterceptors.isEmpty()) {
            MessageBodyRegistry.writeTo(writer, entity, type, genericType, anns, finalMt, outHeaders, bos);
        } else {
            new CassiniWriterInterceptorContext(wInterceptors, writer, host.registry(),
                    entity, type, genericType, anns, finalMt, outHeaders, bos).proceed();
        }

        var b = CassiniHttpResponse.builder().status(status).body(bos.toByteArray());
        b.header("Content-Type", MediaTypes.format(finalMt));
        for (var e : outHeaders.entrySet()) {
            if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
            for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
        }
        return b.build();
    }

    CassiniHttpResponse fromJaxRs(jakarta.ws.rs.core.Response jr, ResourceMethod route,
                               MediaType fallback) throws IOException {
        int status =jr.getStatus();
        Object entity = jr.getEntity();
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : jr.getStringHeaders().entrySet()) {
            if (!"Content-Type".equalsIgnoreCase(e.getKey())) {
                headers.put(e.getKey(), e.getValue());
            }
        }
        MediaType chosen = jr.getMediaType() != null ? jr.getMediaType() : fallback;

        if (entity == null) {
            var b = CassiniHttpResponse.builder().status(status).body(new byte[0]);
            for (var e : headers.entrySet()) for (String v : e.getValue()) b.header(e.getKey(), v);
            // User Content-Type: re-emit it explicitly (filtered above).
            if (jr.getMediaType() != null) b.header("Content-Type", MediaTypes.format(jr.getMediaType()));
            return b.build();
        }
        // §7.2 / §4.2.4: when the method declares a Response return (wrapper),
        // the genericType passed to MBW / WriterInterceptorContext is that of
        // the real entity, not Response.class.
        Type gt;
        if (route == null) {
            gt = entity.getClass();
        } else {
            Type ret = route.javaMethod().getGenericReturnType();
            gt = (ret == jakarta.ws.rs.core.Response.class) ? entity.getClass() : ret;
        }
        // §4.2.4: if the user passed annotations through
        // ResponseBuilder.entity(Object, Annotation[]), they take precedence over
        // method annotations for MessageBodyWriter.isWriteable / writeTo.
        Annotation[] anns = null;
        if (jr instanceof io.vidocq.cassini.internal.runtime.CassiniResponse cr) {
            Annotation[] entAnns = cr.entityAnnotations();
            if (entAnns != null && entAnns.length > 0) anns = entAnns;
        }
        if (anns == null) {
            anns = route == null ? new Annotation[0] : route.javaMethod().getAnnotations();
        }
        return writeEntity(entity, gt, anns, chosen, status, headers, route);
    }

    /**
     * If {@code chosen} is a wildcard (e.g. {@literal *}{@literal /}{@literal *}
     * coming from an implicit Accept) and the entity type has a natural
     * Content-Type, substitute it. Otherwise respect the negotiated one.
     */
    private static MediaType defaultFor(MediaType chosen, Class<?> entityType) {
        if (chosen == null) chosen = MediaType.WILDCARD_TYPE;
        if (!chosen.isWildcardType()) return chosen;
        if (CharSequence.class.isAssignableFrom(entityType)) return MediaType.TEXT_PLAIN_TYPE;
        if (byte[].class == entityType || InputStream.class.isAssignableFrom(entityType)
                || java.io.File.class.isAssignableFrom(entityType)) {
            return MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return chosen;
    }
}
