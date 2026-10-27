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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final String CONTENT_TYPE = "Content-Type";

    private final Invoker host;

    ResponsePipeline(Invoker host) {
        this.host = host;
    }

    CassiniHttpResponse runResponseFiltersAndWrite(CassiniRequestContext rctx,
                                                jakarta.ws.rs.core.Response userResp,
                                                ResourceMethod route, MediaType chosen) throws IOException {
        Object entity = userResp.getEntity();
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        copyStringHeaders(userResp, headers);
        CassiniResponseContext response = new CassiniResponseContext(userResp.getStatus(), entity,
                entity == null ? null : entity.getClass(), headers);
        runResponseFilters(rctx, response, route);
        return writeFromContext(response, route, chosen);
    }

    CassiniHttpResponse runResponseFiltersForResult(CassiniRequestContext rctx, Object result,
                                                  ResourceMethod route, MediaType chosen) throws IOException {
        MultivaluedMap<String, Object> headers = MessageBodyRegistry.outHeaders();
        int status = result == null ? 204 : 200;
        Object entity = result;
        if (result instanceof jakarta.ws.rs.core.Response jr) {
            status = jr.getStatus();
            entity = jr.getEntity();
            copyStringHeaders(jr, headers);
        }
        CassiniResponseContext response = new CassiniResponseContext(status, entity,
                entity == null ? null : entity.getClass(), entityAnnotations(result, route), headers);
        runResponseFilters(rctx, response, route);
        return writeFromContext(response, route, chosen);
    }

    private static void copyStringHeaders(jakarta.ws.rs.core.Response from, MultivaluedMap<String, Object> to) {
        for (var e : from.getStringHeaders().entrySet()) {
            for (String v : e.getValue()) to.add(e.getKey(), v);
        }
    }

    /**
     * §6.7.4: what {@code ContainerResponseContext.getEntityAnnotations()} returns: the
     * annotations of the resource method, merged with those passed to
     * {@code ResponseBuilder.entity(Object, Annotation[])}, which CassiniResponse keeps.
     */
    private static Annotation[] entityAnnotations(Object result, ResourceMethod route) {
        Annotation[] explicit = result instanceof io.vidocq.cassini.internal.runtime.CassiniResponse cr
                ? cr.entityAnnotations() : null;
        if (route.javaMethod() == null) return explicit;
        Annotation[] methodAnns = route.javaMethod().getAnnotations();
        if (explicit == null || explicit.length == 0) return methodAnns;
        Annotation[] merged = java.util.Arrays.copyOf(methodAnns, methodAnns.length + explicit.length);
        System.arraycopy(explicit, 0, merged, methodAnns.length, explicit.length);
        return merged;
    }

    /** Runs the response filters bound to {@code route}. §6.5.2: with no route (a pre-matching
     *  abort or failure), only the globally bound ones, for which appliesTo(null, null) is true. */
    private void runResponseFilters(CassiniRequestContext rctx, CassiniResponseContext response,
                                    ResourceMethod route) {
        for (var fe : host.filters().responseFilters()) {
            boolean applies = route == null
                    ? fe.appliesTo(null, null)
                    : fe.appliesTo(route.javaMethod(), route.beanClass());
            if (applies) runResponseFilter(fe.instance(), rctx, response);
        }
    }

    private static void runResponseFilter(ContainerResponseFilter filter, CassiniRequestContext rctx,
                                          CassiniResponseContext response) {
        Throwable[] err = new Throwable[1];
        rctx.runDuringResponsePhase(() -> {
            try {
                filter.filter(rctx, response);
            } catch (IOException | RuntimeException e) {
                err[0] = e;
            }
        });
        if (err[0] instanceof RuntimeException re) throw re;
        if (err[0] instanceof IOException io) throw new UncheckedIOException(io);
    }

    private CassiniHttpResponse writeFromContext(CassiniResponseContext rctx, ResourceMethod route, MediaType chosen) throws IOException {
        Object entity = rctx.getEntity();
        if (entity == null) return withoutEntity(rctx.getStatus(), rctx.getHeaders());
        MediaType mt = rctx.getMediaType() != null ? rctx.getMediaType() : chosen;
        // §6.7.4.2: if a ContainerResponseFilter wrapped the entityStream
        // via setEntityStream(), MBW.writeTo must write into that wrapper —
        // the wrapper forwards to the originalStream collected by the runtime.
        if (rctx.getEntityStream() != rctx.originalStream()) return writeThroughFilterStream(rctx, entity, mt);
        // Strip Content-Type from headers map (re-added by writeEntity)
        Map<String, List<String>> extra = new LinkedHashMap<>();
        for (var e : rctx.getHeaders().entrySet()) {
            if (!CONTENT_TYPE.equalsIgnoreCase(e.getKey())) {
                List<String> vs = new ArrayList<>();
                for (Object v : e.getValue()) vs.add(String.valueOf(v));
                extra.put(e.getKey(), vs);
            }
        }
        return writeEntity(entity, rctx.getEntityType() == null ? entity.getClass() : rctx.getEntityType(),
                rctx.getEntityAnnotations(), mt, rctx.getStatus(), extra, route);
    }

    private static CassiniHttpResponse withoutEntity(int status, MultivaluedMap<String, Object> headers) {
        var b = CassiniHttpResponse.builder().status(status).body(new byte[0]);
        for (var e : headers.entrySet()) {
            for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
        }
        return b.build();
    }

    /** §6.7.4.2: the entity written into the stream a response filter set, which forwards to the
     *  original stream the response body is read from. */
    private CassiniHttpResponse writeThroughFilterStream(CassiniResponseContext rctx, Object entity, MediaType mt)
            throws IOException {
        Class<?> type = entity.getClass();
        Type gt = rctx.getEntityType() == null ? type : rctx.getEntityType();
        Annotation[] anns = rctx.getEntityAnnotations() == null ? new Annotation[0] : rctx.getEntityAnnotations();
        @SuppressWarnings({"rawtypes", "unchecked"})
        MessageBodyWriter writer = host.registry().findWriter(type, gt, anns, mt)
                .orElseThrow(() -> new WebApplicationException(
                        "No MessageBodyWriter for " + type.getName() + " / " + MediaTypes.format(mt), 500));
        host.injectProviderContexts(writer, null);
        MultivaluedMap<String, Object> outHeaders = MessageBodyRegistry.outHeaders();
        for (var e : rctx.getHeaders().entrySet()) {
            if (!CONTENT_TYPE.equalsIgnoreCase(e.getKey())) {
                for (Object v : e.getValue()) outHeaders.add(e.getKey(), v);
            }
        }
        MessageBodyRegistry.writeTo(writer, entity, type, gt, anns, mt, outHeaders, rctx.getEntityStream());
        try {
            rctx.getEntityStream().close();
        } catch (IOException _) {
            // The bytes are already in the original stream; a filter stream that fails to close loses nothing.
        }
        return response(rctx.getStatus(), mt, outHeaders, rctx.originalStream().toByteArray());
    }

    /** A response with the body, its Content-Type, and the other headers of {@code headers}. */
    private static CassiniHttpResponse response(int status, MediaType mt, MultivaluedMap<String, Object> headers,
                                                byte[] body) {
        var b = CassiniHttpResponse.builder().status(status).body(body);
        b.header(CONTENT_TYPE, MediaTypes.format(mt));
        for (var e : headers.entrySet()) {
            if (!CONTENT_TYPE.equalsIgnoreCase(e.getKey())) {
                for (Object v : e.getValue()) b.header(e.getKey(), String.valueOf(v));
            }
        }
        return b.build();
    }

    Object readEntity(MethodParameters.Param p, MediaType ct, CassiniHttpExchange request, ResourceMethod route) throws IOException {
        Class<?> type = p.type();
        Type genericType = p.genericType();
        Annotation[] anns = p.annotations();
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
            byte[] all = readWhole(request);
            request.setAttribute(FieldInjector.ATTR_BODY_CACHE, all);
            src = new java.io.ByteArrayInputStream(all);
        }
        var rInterceptors = rInterceptorsForChoice;
        try (InputStream in = src) {
            if (rInterceptors.isEmpty()) {
                // Not null: without reader interceptors, a missing reader threw above.
                return java.util.Objects.requireNonNull(reader).readFrom(type, genericType, anns, ct, headers, in);
            }
            return new CassiniReaderInterceptorContext(rInterceptors,
                    reader, host.registry(), type, genericType, anns, ct, headers, in).proceed();
        } catch (NoContentException nce) {
            // §4.2.4: a NoContentException thrown while reading a server request
            // entity MUST be translated into a BadRequestException wrapping it and
            // re-thrown to the exception mappers — Invoker renders it like any
            // other WebApplicationException. Whatever reader threw it.
            throw new BadRequestException(nce);
        } catch (WebApplicationException wae) {
            throw wae;
        } catch (RuntimeException | IOException failure) {
            // cassini#39: any other failure of the reader, or of the
            // ReaderInterceptor chain around it. Marked so that Invoker tells it
            // apart from the rest of argument resolution (parameter conversion,
            // body buffering above), which keeps its own rules.
            throw new EntityReadException(failure);
        }
    }

    /**
     * The whole entity, read with a buffer of its {@code Content-Length} when the transport knows
     * it: {@code readAllBytes} asks for 16 KB chunks and allocates them, for bodies of a few dozen
     * bytes. A transport that announces a length shorter than what it delivers (a chunked body
     * behind a stale header) still hands over everything: the stream is read to its end.
     */
    private static byte[] readWhole(CassiniHttpExchange request) throws IOException {
        InputStream body = request.requestBody();
        if (body == null) return new byte[0];
        long length = request.contentLength();
        if (length < 0 || length > Integer.MAX_VALUE - 16) return body.readAllBytes();
        byte[] sized = body.readNBytes((int) length);
        int more = body.read();
        if (more < 0) return sized;
        byte[] rest = body.readAllBytes();
        byte[] all = java.util.Arrays.copyOf(sized, sized.length + 1 + rest.length);
        all[sized.length] = (byte) more;
        System.arraycopy(rest, 0, all, sized.length + 1, rest.length);
        return all;
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
        final MediaType finalMt = producedMediaType(mtSelect, writer);
        host.injectProviderContexts(writer, null);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        MultivaluedMap<String, Object> outHeaders = MessageBodyRegistry.outHeaders();
        // Populate outHeaders with extraHeaders BEFORE the interceptor chain
        // so WriterInterceptor.aroundWriteTo.getHeaders() sees what the
        // ResourceMethod/ResponseBuilder produced. Interceptors
        // may still mutate it; read it back afterwards for the build.
        for (var e : extraHeaders.entrySet()) {
            for (String v : e.getValue()) outHeaders.add(e.getKey(), v);
        }
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

        return response(status, finalMt, outHeaders, bos.toByteArray());
    }

    /** §4.2.4: when the method did not specify a Content-Type, the first concrete media type the
     *  selected writer declares in its @Produces. */
    private static MediaType producedMediaType(MediaType selected, MessageBodyWriter<?> writer) {
        if (!selected.isWildcardType()) return selected;
        jakarta.ws.rs.Produces wp = writer.getClass().getAnnotation(jakarta.ws.rs.Produces.class);
        if (wp == null || wp.value().length == 0) return selected;
        MediaType inferred = MediaType.valueOf(wp.value()[0]);
        return inferred.isWildcardType() ? selected : inferred;
    }

    CassiniHttpResponse fromJaxRs(jakarta.ws.rs.core.Response jr, ResourceMethod route,
                               MediaType fallback) throws IOException {
        Object entity = jr.getEntity();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> e : jr.getStringHeaders().entrySet()) {
            if (!CONTENT_TYPE.equalsIgnoreCase(e.getKey())) headers.put(e.getKey(), e.getValue());
        }
        if (entity == null) {
            var b = CassiniHttpResponse.builder().status(jr.getStatus()).body(new byte[0]);
            for (var e : headers.entrySet()) {
                for (String v : e.getValue()) b.header(e.getKey(), v);
            }
            // User Content-Type: re-emit it explicitly (filtered above).
            if (jr.getMediaType() != null) b.header(CONTENT_TYPE, MediaTypes.format(jr.getMediaType()));
            return b.build();
        }
        MediaType chosen = jr.getMediaType() != null ? jr.getMediaType() : fallback;
        return writeEntity(entity, entityGenericType(entity, route), writerAnnotations(jr, route),
                chosen, jr.getStatus(), headers, route);
    }

    /** §7.2 / §4.2.4: when the method declares a Response return (wrapper), the genericType passed
     *  to MBW / WriterInterceptorContext is that of the real entity, not Response.class. */
    private static Type entityGenericType(Object entity, ResourceMethod route) {
        if (route == null) return entity.getClass();
        Type ret = route.javaMethod().getGenericReturnType();
        return ret == jakarta.ws.rs.core.Response.class ? entity.getClass() : ret;
    }

    /** §4.2.4: the annotations passed to ResponseBuilder.entity(Object, Annotation[]) take
     *  precedence over those of the method for MessageBodyWriter.isWriteable / writeTo. */
    private static Annotation[] writerAnnotations(jakarta.ws.rs.core.Response jr, ResourceMethod route) {
        if (jr instanceof io.vidocq.cassini.internal.runtime.CassiniResponse cr) {
            Annotation[] entAnns = cr.entityAnnotations();
            if (entAnns != null && entAnns.length > 0) return entAnns;
        }
        return route == null ? new Annotation[0] : route.javaMethod().getAnnotations();
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
