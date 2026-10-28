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
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §7.2 {@code ReaderInterceptorContext.proceed()}: interceptors run first, then the terminal
 * reader, re-selected from the registry when the interceptors made it unsuitable.
 */
class CassiniReaderInterceptorContextTest {

    private static final String FROM_MARKER_READER = "from-marker-reader";
    private static final String HELLO = "hello";

    /** A reader that only accepts {@link Marker} and returns a fixed value. */
    static final class MarkerReader implements MessageBodyReader<Object> {
        @Override public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations,
                                            MediaType mediaType) {
            return type == Marker.class;
        }

        @Override public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations,
                                         MediaType mediaType,
                                         jakarta.ws.rs.core.MultivaluedMap<String, String> httpHeaders,
                                         InputStream entityStream) {
            return FROM_MARKER_READER;
        }
    }

    static final class Marker {
    }

    static final class Unknown {
    }

    @BeforeAll
    static void installRuntimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static InputStream body() {
        return new ByteArrayInputStream(HELLO.getBytes(StandardCharsets.UTF_8));
    }

    private static CassiniReaderInterceptorContext context(List<FilterEntry<ReaderInterceptor>> interceptors,
                                                           MessageBodyReader<?> terminal,
                                                           MessageBodyRegistry registry, Class<?> type) {
        return new CassiniReaderInterceptorContext(interceptors, terminal, registry, type, type,
                null, MediaType.TEXT_PLAIN_TYPE, new MultivaluedHashMap<>(), body());
    }

    @Test
    void readableTerminalIsUsed() throws IOException {
        var ctx = context(List.of(), new MarkerReader(), new MessageBodyRegistry(), Marker.class);

        assertEquals(FROM_MARKER_READER, ctx.proceed());
    }

    @Test
    void interceptorChangingTheTypeMakesTheRegistryPickAnotherReader() throws IOException {
        ReaderInterceptor toString = c -> {
            c.setType(String.class);
            c.setGenericType(String.class);
            return c.proceed();
        };
        var ctx = context(List.of(FilterEntry.of(toString)), new MarkerReader(), new MessageBodyRegistry(),
                Marker.class);

        assertEquals(HELLO, ctx.proceed());
    }

    @Test
    void withoutRegistryTheTerminalIsUsedEvenWhenNotReadable() throws IOException {
        var ctx = context(List.of(), new MarkerReader(), null, String.class);

        assertEquals(FROM_MARKER_READER, ctx.proceed());
    }

    @Test
    void withoutTerminalNorRegistryTheEntityIsUnsupported() {
        var ctx = context(List.of(), null, null, String.class);

        var e = assertThrows(WebApplicationException.class, ctx::proceed);
        assertEquals(415, e.getResponse().getStatus());
    }

    @Test
    void withoutTerminalTheRegistryReaderIsUsed() throws IOException {
        var ctx = context(List.of(), null, new MessageBodyRegistry(), String.class);

        assertEquals(HELLO, ctx.proceed());
    }

    @Test
    void noReaderAnywhereIsUnsupported() {
        var ctx = context(List.of(), null, new MessageBodyRegistry(), Unknown.class);

        var e = assertThrows(WebApplicationException.class, ctx::proceed);
        assertEquals(415, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains(Unknown.class.getName()));
    }
}
