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
package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.context.CassiniProviders;
import io.vidocq.cassini.internal.filter.FilterEntry;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.gen.ProviderDescriptor;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.ParamConverter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What Cassini reads on a provider comes from its generated {@link ProviderDescriptor} when it has one, and from
 * reflection, unchanged, when it has none (CASSINI-040). Each provider here carries annotations that disagree with
 * its descriptor, so the result tells which one was read.
 */
class ProviderDescriptorsTest {

    @Provider
    @Priority(7)
    public static class Described implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
        }
    }

    /** What a CDI client proxy of a filter looks like: a subclass without {@code @Provider}. */
    public static class DescribedProxy extends Described {
    }

    @Provider
    @Priority(7)
    public static class NotDescribed implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext requestContext) {
        }
    }

    @Provider
    @Produces("text/a")
    public static class AWriter implements MessageBodyWriter<String> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return true;
        }

        @Override
        public void writeTo(String s, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                            MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) {
        }
    }

    @Provider
    @Priority(1)
    public static class Mapper implements ExceptionMapper<IllegalStateException> {
        @Override
        public Response toResponse(IllegalStateException exception) {
            return Response.ok().build();
        }
    }

    @Provider
    @Produces("text/a")
    public static class Resolver implements ContextResolver<Long> {
        @Override
        public Long getContext(Class<?> type) {
            return 1L;
        }
    }

    @Provider
    @Priority(9000)
    public static class LowConverters implements ParamConverterProvider {
        @Override
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            return null;
        }
    }

    @Provider
    @Priority(1)
    public static class HighConverters implements ParamConverterProvider {
        @Override
        public <T> ParamConverter<T> getConverter(Class<T> rawType, Type genericType, Annotation[] annotations) {
            return null;
        }
    }

    private record Descriptor(Class<?> providerClass, Integer priority, String[] produces, String[] consumes,
                              boolean preMatching, String constrainedTo,
                              List<Class<? extends Annotation>> nameBindings,
                              Map<Class<?>, Class<?>> typeArguments) implements ProviderDescriptor {
    }

    private static Descriptor descriptor(Class<?> type, Integer priority, String[] produces, boolean preMatching,
                                         Map<Class<?>, Class<?>> typeArguments) {
        return new Descriptor(type, priority, produces, null, preMatching, null, List.of(), typeArguments);
    }

    @BeforeAll
    static void runtimeDelegate() {
        // cassini-core registers no RuntimeDelegate, the transports do; media types need one.
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @AfterEach
    void forget() {
        for (Class<?> c : List.of(Described.class, AWriter.class, Mapper.class, Resolver.class, LowConverters.class)) {
            ProviderDescriptors.unregister(c);
        }
    }

    @Test
    void aFilterIsReadFromItsDescriptorAlsoBehindAProxy() {
        ProviderDescriptors.register(descriptor(Described.class, 42, null, true, Map.of()));

        FilterEntry<?> entry = FilterEntry.of(new Described());
        FilterEntry<?> proxied = FilterEntry.of(new DescribedProxy());

        assertEquals(42, entry.priority());
        assertTrue(entry.preMatching());
        assertEquals(42, proxied.priority());
        assertSame(Described.class, proxied.implClass());
    }

    @Test
    void aFilterWithoutDescriptorKeepsItsReflectiveReading() {
        assertNull(ProviderDescriptors.of(NotDescribed.class));

        FilterEntry<?> entry = FilterEntry.of(new NotDescribed());

        assertEquals(7, entry.priority());
        assertFalse(entry.preMatching());
    }

    @Test
    void aWriterIsSelectedOnItsDescriptorsMediaTypeAndEntityType() {
        ProviderDescriptors.register(descriptor(AWriter.class, null, new String[] {"text/b"}, false,
                Map.of(MessageBodyWriter.class, Integer.class)));
        MessageBodyRegistry registry = new MessageBodyRegistry();
        registry.addWriter(new AWriter());

        assertTrue(registry.findWriter(Integer.class, Integer.class, new Annotation[0], MediaType.valueOf("text/b"))
                .filter(AWriter.class::isInstance).isPresent());
        assertFalse(registry.findWriter(String.class, String.class, new Annotation[0], MediaType.valueOf("text/a"))
                .filter(AWriter.class::isInstance).isPresent());
    }

    @Test
    void aWriterWithoutDescriptorKeepsItsAnnotations() {
        MessageBodyRegistry registry = new MessageBodyRegistry();
        registry.addWriter(new AWriter());

        assertTrue(registry.findWriter(String.class, String.class, new Annotation[0], MediaType.valueOf("text/a"))
                .filter(AWriter.class::isInstance).isPresent());
    }

    @Test
    void anExceptionMapperTakesItsTypeFromItsDescriptor() {
        ProviderDescriptors.register(descriptor(Mapper.class, 3, null, false,
                Map.of(ExceptionMapper.class, IllegalArgumentException.class)));
        ExceptionMapperRegistry registry = new ExceptionMapperRegistry();
        registry.register(new Mapper());

        Object mapper = registry.findMapper(IllegalArgumentException.class);

        assertTrue(mapper instanceof Mapper);
    }

    @Test
    void aContextResolverTakesItsTypeFromItsDescriptor() {
        ProviderDescriptors.register(descriptor(Resolver.class, null, new String[] {"text/b"}, false,
                Map.of(ContextResolver.class, Integer.class)));
        ExceptionMapperRegistry mappers = new ExceptionMapperRegistry();
        CassiniProviders providers = new CassiniProviders(new MessageBodyRegistry(), mappers,
                java.util.List.of(new Resolver()));

        assertTrue(providers.getContextResolver(Integer.class, MediaType.valueOf("text/b")) != null);
    }

    @Test
    void paramConverterProvidersAreOrderedOnTheirDescriptorsPriority() {
        ProviderDescriptors.register(descriptor(LowConverters.class, 0, null, false, Map.of()));
        FilterRegistry registry = new FilterRegistry();
        registry.addParamConverterProvider(new HighConverters());
        registry.addParamConverterProvider(new LowConverters());

        assertTrue(registry.paramConverterProviders().getFirst() instanceof LowConverters);
    }
}
