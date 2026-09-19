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
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbException;
import jakarta.json.stream.JsonParsingException;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.NoContentException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * cassini#39: what the server does when the request entity cannot be read —
 * driven end to end through {@link DefaultCassiniHttpAdapter#dispatch} on an
 * {@link InMemoryExchange}, so no socket is opened.
 */
class EntityReadFailureTest {

    /** Logger that reports rejected request entities; documented in usage.adoc. */
    private static final String ENTITY_LOGGER = "io.vidocq.cassini.entity";
    private static final String DISPATCH_LOGGER = DefaultCassiniHttpAdapter.class.getName();
    private static final String JSON = MediaType.APPLICATION_JSON;

    @BeforeAll
    static void setUp() {
        // cassini-core does not register a RuntimeDelegate (the transports do);
        // Response.status(..) and the WebApplicationException constructors need one.
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
        // These fixtures live in cassini-core's own (patched) module. Export their
        // package to Champollion, as an application exports its DTO package, so that
        // a valid body can be bound. No-op when the tests run on the class path.
        Module self = EntityReadFailureTest.class.getModule();
        ModuleLayer.boot().findModule("io.vidocq.champollion.jsonb")
                .ifPresent(jsonb -> self.addExports(EntityReadFailureTest.class.getPackageName(), jsonb));
    }

    // ---------------------------------------------------------------- fixtures

    public record Order(String item, List<Long> ids) {}

    @Path("/orders")
    public static class OrderResource {
        @POST
        @Consumes(JSON)
        @Produces(MediaType.TEXT_PLAIN)
        public String create(Order order) {
            return "ok item=" + order.item() + " ids=" + order.ids();
        }

        @GET
        @Path("/count")
        @Produces(MediaType.TEXT_PLAIN)
        public String count(@QueryParam("n") int n) {
            return "n=" + n;
        }
    }

    /** §3.4.1: a locator returning {@code Object} takes the DynamicLocatorDispatch path. */
    @Path("/dyn")
    public static class DynamicRoot {
        @Path("orders")
        public Object orders() {
            return new OrderSub();
        }
    }

    public static class OrderSub {
        @POST
        @Consumes(JSON)
        @Produces(MediaType.TEXT_PLAIN)
        public String create(Order order) {
            return "ok item=" + order.item();
        }
    }

    public static final class Thing {
        final String text;
        Thing(String text) { this.text = text; }
    }

    /** An application reader that follows the readFrom contract for an empty stream. */
    @Provider
    @Consumes("application/x-thing")
    public static final class ThingReader implements MessageBodyReader<Thing> {
        @Override
        public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Thing.class;
        }

        @Override
        public Thing readFrom(Class<Thing> type, Type genericType, Annotation[] annotations, MediaType mediaType,
                              MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException {
            byte[] bytes = entityStream.readAllBytes();
            if (bytes.length == 0) throw new NoContentException("no Thing in an empty body");
            return new Thing(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    @Path("/things")
    public static class ThingResource {
        @POST
        @Consumes("application/x-thing")
        @Produces(MediaType.TEXT_PLAIN)
        public String take(Thing thing) {
            return "ok " + thing.text;
        }
    }

    @Provider
    public static final class BadRequestMapper implements ExceptionMapper<BadRequestException> {
        final List<BadRequestException> seen = new CopyOnWriteArrayList<>();

        @Override
        public Response toResponse(BadRequestException e) {
            seen.add(e);
            return Response.status(400).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity("{\"error\":\"bad_request\"}").build();
        }
    }

    @Provider
    public static final class JsonbExceptionMapper implements ExceptionMapper<JsonbException> {
        final List<JsonbException> seen = new CopyOnWriteArrayList<>();

        @Override
        public Response toResponse(JsonbException e) {
            seen.add(e);
            return Response.status(422).build();
        }
    }

    @Provider
    public static final class CatchAllMapper implements ExceptionMapper<RuntimeException> {
        @Override
        public Response toResponse(RuntimeException e) {
            return Response.status(418).build();
        }
    }

    @Provider
    public static final class FailingInterceptor implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) {
            throw new IllegalStateException("bug in an application ReaderInterceptor");
        }
    }

    /** Stands in for any JSON-B provider: its fromJson fails with the given exception. */
    @Provider
    public static final class ThrowingJsonbResolver implements ContextResolver<Jsonb> {
        private final RuntimeException failure;

        ThrowingJsonbResolver(RuntimeException failure) { this.failure = failure; }

        @Override
        public Jsonb getContext(Class<?> type) {
            return (Jsonb) Proxy.newProxyInstance(Jsonb.class.getClassLoader(), new Class<?>[] {Jsonb.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("fromJson")) throw failure;
                        throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static InMemoryExchange dispatch(InMemoryExchange exchange, Object... providers) {
        CassiniStack.Builder builder = new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(OrderResource.class, DynamicRoot.class, ThingResource.class, ThingReader.class);
            }
        });
        for (Object p : providers) builder.provider(p);
        builder.build().adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    private static void assertNothingLoggedAboveDebug(LogCapture logs) {
        assertEquals(List.of(), logs.atOrAbove(Level.INFO).stream().map(r -> r.getLevel() + " " + r.getMessage()).toList(),
                "a client's malformed entity must not be logged above DEBUG");
    }

    // ------------------------------------------------------------------- tests

    @Test
    void aValidJsonEntityIsStillBound() {
        InMemoryExchange ex = dispatch(InMemoryExchange.post("/orders", JSON, "{\"item\":\"a\",\"ids\":[1]}"));
        assertEquals(200, ex.status());
        assertEquals("ok item=a ids=[1]", ex.responseText());
    }

    @Test
    void noContentExceptionFromAnApplicationReaderBecomesA400() {
        try (LogCapture logs = LogCapture.of(ENTITY_LOGGER, DISPATCH_LOGGER)) {
            InMemoryExchange ex = dispatch(InMemoryExchange.post("/things", "application/x-thing", ""));
            assertEquals(400, ex.status());
            assertEquals("", ex.responseText());
            assertNothingLoggedAboveDebug(logs);
        }
    }

    @Test
    void noContentExceptionReachesMappersWrappedInABadRequestException() {
        BadRequestMapper mapper = new BadRequestMapper();
        InMemoryExchange ex = dispatch(InMemoryExchange.post("/things", "application/x-thing", ""), mapper);
        assertEquals(400, ex.status());
        assertEquals("{\"error\":\"bad_request\"}", ex.responseText());
        assertEquals(1, mapper.seen.size());
        assertInstanceOf(NoContentException.class, mapper.seen.get(0).getCause());
    }
}
