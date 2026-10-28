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
package io.vidocq.cassini.testing;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.MessageBodyWriter;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The harness other bricks drive their TCK runners with: it mounts the resources, providers and
 * application it is given in front of a real Chappe server, under an optional context path.
 */
class CassiniTestHarnessTest {

    private static final String TEXT = "text/plain";
    private static final String GREETING_PATH = "/greeting/";

    /** A singleton resource handed over as an instance. */
    @Path("/singleton")
    public static class SingletonResource {
        private int calls;

        @GET
        @Produces(TEXT)
        public String count() {
            return "call " + (++calls);
        }
    }

    /** A per-request resource built through its constructor with the most injectable parameters. */
    @Path("/greeting/{name}")
    public static class GreetingResource {
        private final String name;

        public GreetingResource() {
            this("nobody");
        }

        public GreetingResource(@PathParam("name") String name) {
            this.name = name;
        }

        @GET
        @Produces(TEXT)
        public String greet(@QueryParam("punctuation") String punctuation,
                            @Context Application application) {
            String suffix = punctuation == null ? "" : punctuation;
            return "hello " + name + suffix + " from " + application.getProperties().get("owner");
        }
    }

    /** A per-request resource with only a no-argument constructor. */
    @Path("/plain")
    public static class PlainResource {
        @GET
        @Produces(TEXT)
        public String plain() {
            return "plain";
        }

        @GET
        @Path("/fail")
        @Produces(TEXT)
        public String fail() {
            throw new IllegalStateException("broken");
        }

        @GET
        @Path("/point")
        @Produces("application/x-point")
        public Point point() {
            return new Point(3, 4);
        }
    }

    /** No public constructor whose parameters can all be injected: not a root resource. */
    @Path("/uninjectable")
    public static class UninjectableResource {
        public UninjectableResource(String unannotated) {
            // unreachable: the harness never instantiates this class
        }

        @GET
        public String get() {
            return "unreachable";
        }
    }

    /** Abstract: not a root resource (§3.1.1). */
    @Path("/abstract")
    public abstract static class AbstractResource {
        @GET
        public String get() {
            return "unreachable";
        }
    }

    /** Package-private: not a root resource (§3.1.1). */
    @Path("/hidden")
    static class HiddenResource {
        @GET
        public String get() {
            return "unreachable";
        }
    }

    public record Point(int x, int y) { }

    /** Maps the failure of {@code /plain/fail} to a 418. */
    public static class BrokenMapper implements ExceptionMapper<IllegalStateException> {
        @Override
        public Response toResponse(IllegalStateException exception) {
            return Response.status(418).entity("mapped " + exception.getMessage()).type(TEXT).build();
        }
    }

    /** Stamps every response. */
    public static class StampFilter implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) {
            response.getHeaders().add("X-Stamp", "harness");
        }
    }

    /** Writes a {@link Point} as {@code x,y}. */
    public static class PointWriter implements MessageBodyWriter<Point> {
        @Override
        public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
            return type == Point.class;
        }

        @Override
        public void writeTo(Point point, Class<?> type, Type genericType, Annotation[] annotations,
                            MediaType mediaType, MultivaluedMap<String, Object> headers, OutputStream out)
                throws IOException {
            out.write((point.x() + "," + point.y()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final Application APPLICATION = new Application() {
        @Override
        public Map<String, Object> getProperties() {
            return Map.of("owner", "the harness");
        }
    };

    private static CassiniTestHarness.Builder fullBuilder() {
        return CassiniTestHarness.builder()
                .application(APPLICATION)
                .resource(new SingletonResource())
                .resourceClass(GreetingResource.class)
                .resourceClass(PlainResource.class)
                .resourceClass(UninjectableResource.class)
                .resourceClass(AbstractResource.class)
                .resourceClass(HiddenResource.class)
                .provider(new BrokenMapper())
                .provider(new StampFilter())
                .provider(new PointWriter());
    }

    @Test
    void servesResourcesProvidersAndTheApplicationAtTheRoot() throws IOException {
        try (var harness = fullBuilder().contextPath("").start()) {
            assertEquals("http://127.0.0.1:" + harness.port(), harness.baseUrl());

            assertEquals("call 1", get(harness, "/singleton").body());
            assertEquals("call 2", get(harness, "/singleton").body(), "a resource instance is a singleton");

            var greeting = get(harness, GREETING_PATH + "ada?punctuation=!");
            assertEquals(200, greeting.status());
            assertEquals("hello ada! from the harness", greeting.body());
            assertEquals("harness", greeting.stamp());

            assertEquals("plain", get(harness, "/plain").body());
            var mapped = get(harness, "/plain/fail");
            assertEquals(418, mapped.status());
            assertEquals("mapped broken", mapped.body());
            assertEquals("3,4", get(harness, "/plain/point").body());
        }
    }

    @Test
    void classesThatCannotBeRootResourcesAreIgnored() throws IOException {
        try (var harness = fullBuilder().start()) {
            assertEquals(404, get(harness, "/uninjectable").status());
            assertEquals(404, get(harness, "/abstract").status());
            assertEquals(404, get(harness, "/hidden").status());
        }
    }

    @Test
    void aContextPathIsStrippedBeforeRoutingAndAnythingOutsideItIsNotFound() throws IOException {
        try (var harness = fullBuilder().contextPath("/app").start()) {
            assertEquals("http://127.0.0.1:" + harness.port() + "/app", harness.baseUrl());
            assertEquals("plain", get(harness, "/app/plain").body());
            assertEquals("hello bob from the harness", get(harness, "/app" + GREETING_PATH + "bob").body());
            assertEquals(404, get(harness, "/elsewhere/plain").status());
            assertEquals(404, get(harness, "/app").status(), "the context root maps to /, which has no resource");
        }
    }

    @Test
    void aFixedPortIsUsedAsGiven() throws IOException {
        int port;
        try (var probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        try (var harness = CassiniTestHarness.builder().resourceClass(PlainResource.class).port(port).start()) {
            assertEquals(port, harness.port());
            assertEquals("plain", get(harness, "/plain").body());
        }
    }

    @Test
    void aFixedPortThatStaysBusyFailsTheStart() throws IOException {
        try (var busy = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            var builder = CassiniTestHarness.builder().resourceClass(PlainResource.class).port(busy.getLocalPort());
            assertThrows(RuntimeException.class, builder::start);
        }
    }

    @Test
    void buildHandlerExposesTheBridgeAndTheContextPrefix() {
        var atRoot = CassiniTestHarness.builder().resourceClass(PlainResource.class).contextPath(null).buildHandler();
        assertEquals("", atRoot.prefix());
        assertTrue(atRoot.bridgeHandler() != null);

        var underApp = CassiniTestHarness.builder().resourceClass(PlainResource.class).contextPath("/app").buildHandler();
        assertEquals("/app", underApp.prefix());
    }

    @Test
    void closingStopsTheServerAndClosingTwiceIsHarmless() {
        var harness = CassiniTestHarness.builder().resourceClass(PlainResource.class).start();
        harness.close();
        harness.close();
        assertThrows(IOException.class, () -> get(harness, "/plain"));
    }

    private record Reply(int status, String body, String stamp) { }

    private static Reply get(CassiniTestHarness harness, String path) throws IOException {
        var connection = (HttpURLConnection) URI.create("http://127.0.0.1:" + harness.port() + path)
                .toURL().openConnection();
        try {
            int status = connection.getResponseCode();
            var stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            return new Reply(status, body, connection.getHeaderField("X-Stamp"));
        } finally {
            connection.disconnect();
        }
    }
}
