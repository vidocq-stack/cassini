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

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.bean.BeanProvider;
import jakarta.annotation.Priority;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NameBinding;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * A provider a bean provider hands out may be a client proxy that does not extend the provider class: Vauban's, past
 * a final method of the class, extends a class above it, or {@code Object}, and implements the class's interfaces
 * (Vauban BUG-20261118-20). Its {@code @Provider}, {@code @PreMatching}, {@code @Priority}, name bindings, media types
 * and type arguments are those of the class Cassini asked for, not of the proxy (CASSINI-066).
 */
class ProviderBehindAClientProxyTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @NameBinding
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.TYPE, ElementType.METHOD})
    public @interface Stamped {
    }

    /** What {@link ShoutWriter} writes. */
    public record Shout(String text) {
    }

    /** What {@link Low} and {@link High} convert a query parameter to. */
    public record Word(String text) {
    }

    @Path("/")
    public static class Resource {
        @GET
        @Path("word")
        public String word(@QueryParam("w") Word word) {
            return word.text();
        }

        @GET
        @Path("plain")
        public String plain() {
            return "plain";
        }

        @GET
        @Path("stamped")
        @Stamped
        public String stamped() {
            return "stamped";
        }

        @GET
        @Path("boom")
        public String boom() {
            throw new IllegalStateException("boom");
        }

        @GET
        @Path("shout")
        @Produces(MediaType.TEXT_PLAIN)
        public Shout shout() {
            return new Shout("hey");
        }
    }

    /** Refuses a request that carries {@code X-Teapot}, before matching: on a path no resource has too. */
    @Provider
    @PreMatching
    @Priority(10)
    public static class Teapot implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) {
            if (request.getHeaderString("X-Teapot") != null) {
                request.abortWith(Response.status(418).build());
            }
        }
    }

    /** Stamps the responses of the methods bound to {@link Stamped} only. */
    @Provider
    @Stamped
    public static class Stamper implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().putSingle("X-Stamped", "yes");
        }
    }

    /** Maps an {@link IllegalStateException}, and that only, to 409. */
    @Provider
    public static class Conflicts implements ExceptionMapper<IllegalStateException> {
        @Override
        public Response toResponse(IllegalStateException failure) {
            return Response.status(409).build();
        }
    }

    /** Writes a {@link Shout}, and that only, in capitals. */
    @Provider
    @Produces(MediaType.TEXT_PLAIN)
    public static class ShoutWriter implements MessageBodyWriter<Shout> {
        @Override
        public boolean isWriteable(Class<?> type, Type generic, Annotation[] annotations, MediaType mediaType) {
            return true;
        }

        @Override
        public void writeTo(Shout shout, Class<?> type, Type generic, Annotation[] annotations, MediaType mediaType,
                MultivaluedMap<String, Object> headers, OutputStream out) throws IOException {
            out.write(shout.text().toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Converts a {@link Word}, after {@link High}: its priority is the lowest. */
    @Provider
    @Priority(9000)
    public static class Low implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> raw, Type generic, Annotation[] annotations) {
            return raw == Word.class ? (ParamConverter<T>) new WordConverter("low") : null;
        }
    }

    /** Converts a {@link Word} first: its priority is the highest. */
    @Provider
    @Priority(1)
    public static class High implements ParamConverterProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> ParamConverter<T> getConverter(Class<T> raw, Type generic, Annotation[] annotations) {
            return raw == Word.class ? (ParamConverter<T>) new WordConverter("high") : null;
        }
    }

    record WordConverter(String by) implements ParamConverter<Word> {
        @Override
        public Word fromString(String value) {
            return new Word(value + " by " + by);
        }

        @Override
        public String toString(Word word) {
            return word.text();
        }
    }

    record ConverterProxy(ParamConverterProvider target) implements ParamConverterProvider {
        @Override
        public <T> ParamConverter<T> getConverter(Class<T> raw, Type generic, Annotation[] annotations) {
            return target.getConverter(raw, generic, annotations);
        }
    }

    /** As a client proxy past a final method: no subclass of the provider class, its interfaces only. */
    record RequestFilterProxy(ContainerRequestFilter target) implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext request) throws IOException {
            target.filter(request);
        }
    }

    record ResponseFilterProxy(ContainerResponseFilter target) implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
            target.filter(request, response);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    record MapperProxy(ExceptionMapper target) implements ExceptionMapper {
        @Override
        public Response toResponse(Throwable failure) {
            return target.toResponse(failure);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    record WriterProxy(MessageBodyWriter target) implements MessageBodyWriter {
        @Override
        public boolean isWriteable(Class type, Type generic, Annotation[] annotations, MediaType mediaType) {
            return target.isWriteable(type, generic, annotations, mediaType);
        }

        @Override
        public void writeTo(Object entity, Class type, Type generic, Annotation[] annotations, MediaType mediaType,
                MultivaluedMap headers, OutputStream out) throws IOException, WebApplicationException {
            target.writeTo(entity, type, generic, annotations, mediaType, headers, out);
        }
    }

    /** Hands out each provider behind its proxy, as a CDI container whose proxies do not extend the class. */
    static final class ProxyingProvider implements BeanProvider {
        @Override
        @SuppressWarnings("unchecked")
        public <T> T getBean(Class<T> type) {
            Object bean;
            if (type == Teapot.class) bean = new RequestFilterProxy(new Teapot());
            else if (type == Stamper.class) bean = new ResponseFilterProxy(new Stamper());
            else if (type == Conflicts.class) bean = new MapperProxy(new Conflicts());
            else if (type == ShoutWriter.class) bean = new WriterProxy(new ShoutWriter());
            else if (type == Low.class) bean = new ConverterProxy(new Low());
            else if (type == High.class) bean = new ConverterProxy(new High());
            else if (type == Resource.class) bean = new Resource();
            else throw new IllegalArgumentException("Not managed: " + type);
            return (T) bean;
        }

        @Override
        public Set<Class<?>> getResourceClasses() {
            return new LinkedHashSet<>(List.of(Resource.class, Teapot.class, Stamper.class, Conflicts.class,
                    ShoutWriter.class, Low.class, High.class));
        }
    }

    private static InMemoryExchange dispatch(InMemoryExchange exchange) {
        new CassiniStackBuilderImpl().beanProvider(new ProxyingProvider()).build().adapter().dispatch(exchange)
                .toCompletableFuture().join();
        return exchange;
    }

    @Test
    void aPreMatchingFilterRunsBeforeMatching() {
        assertEquals(418, dispatch(InMemoryExchange.get("/nowhere").header("X-Teapot", "1")).status());
    }

    @Test
    void aNameBoundFilterRunsForTheBoundMethodsOnly() {
        assertEquals("yes", dispatch(InMemoryExchange.get("/stamped")).responseHeader("X-Stamped"));
        assertNull(dispatch(InMemoryExchange.get("/plain")).responseHeader("X-Stamped"));
    }

    @Test
    void anExceptionMapperMapsItsExceptionType() {
        assertEquals(409, dispatch(InMemoryExchange.get("/boom")).status());
    }

    @Test
    void theConverterOfTheHighestPriorityConverts() {
        assertEquals("x by high", dispatch(InMemoryExchange.get("/word?w=x")).responseText());
    }

    @Test
    void aWriterWritesItsEntityTypeOnly() {
        assertEquals("plain", dispatch(InMemoryExchange.get("/plain")).responseText());
        assertEquals("HEY", dispatch(InMemoryExchange.get("/shout")).responseText());
    }
}
