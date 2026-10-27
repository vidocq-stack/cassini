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
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.GenericEntity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.ReaderInterceptorContext;
import jakarta.ws.rs.ext.RuntimeDelegate;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flow of a request through the in-memory stack: routing answers (404, 405, OPTIONS, HEAD),
 * negotiation (415, 406), the result of a method (an entity, a {@code Response}, nothing, a
 * suspended or {@code CompletionStage} result and their failures), and the filters, interceptors
 * and exception mappers around it. The official TCK covers the same ground on a real transport;
 * these tests keep the request path covered by the unit tests of the module.
 */
class RequestFlowTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    /** Mapped to 418 by {@link TeapotMapper}. */
    public static class Teapot extends RuntimeException {
        public Teapot() {
            super("teapot");
        }
    }

    @Provider
    public static class TeapotMapper implements ExceptionMapper<Teapot> {
        @Override
        public Response toResponse(Teapot exception) {
            return Response.status(418).entity("mapped").type("text/plain").build();
        }
    }

    @Path("/r")
    public static class Resource {
        @GET
        @Produces("text/plain")
        public String get() {
            return "hello";
        }

        @POST
        @Consumes("text/plain")
        @Produces("text/plain")
        public String echo(String body) {
            return "got " + body;
        }

        @GET
        @Path("/none")
        public String none() {
            return null;
        }

        @GET
        @Path("/response")
        public Response response() {
            return Response.ok("built", "text/plain").header("X-Built", "yes").build();
        }

        @GET
        @Path("/no-entity")
        public Response noEntity() {
            return Response.status(202).header("X-Accepted", "1").type("text/plain").build();
        }

        @GET
        @Path("/generic")
        @Produces("text/plain")
        public Response generic() {
            return Response.ok(new GenericEntity<String>("generic") { }).build();
        }

        @GET
        @Path("/async")
        public void async(@Suspended AsyncResponse response) {
            response.resume("resumed");
        }

        @GET
        @Path("/async-wae")
        public void asyncWae(@Suspended AsyncResponse response) {
            response.resume(new WebApplicationException(409));
        }

        @GET
        @Path("/async-mapped")
        public void asyncMapped(@Suspended AsyncResponse response) {
            response.resume(new Teapot());
        }

        @GET
        @Path("/async-unmapped")
        public void asyncUnmapped(@Suspended AsyncResponse response) {
            response.resume(new IllegalStateException("unmapped"));
        }

        @GET
        @Path("/stage")
        public CompletionStage<String> stage() {
            return CompletableFuture.completedFuture("staged");
        }

        @GET
        @Path("/stage-wae")
        public CompletionStage<String> stageWae() {
            return CompletableFuture.failedFuture(new WebApplicationException(410));
        }

        @GET
        @Path("/stage-mapped")
        public CompletionStage<String> stageMapped() {
            return CompletableFuture.failedFuture(new Teapot());
        }

        @GET
        @Path("/stage-unmapped")
        public CompletionStage<String> stageUnmapped() {
            return CompletableFuture.failedFuture(new java.io.IOException("unmapped"));
        }

        @GET
        @Path("/throws")
        public String throwsMapped() {
            throw new Teapot();
        }

        @GET
        @Path("/json")
        @Produces("application/json")
        public String json() {
            return "\"j\"";
        }

        @GET
        @Path("/secure")
        public String secure(@Context SecurityContext security) {
            return String.valueOf(security.isSecure());
        }

        @POST
        @Path("/number")
        @Consumes("text/plain")
        public String number(Integer n) {
            return "n=" + n;
        }

        @GET
        @Path("/number-out")
        public Integer numberOut() {
            return 42;
        }

        @GET
        @Path("/annotated")
        @Produces("text/plain")
        public Response annotated() {
            return Response.ok().entity("ann", Resource.class.getAnnotations()).build();
        }

        @GET
        @Path("/events")
        @Produces(MediaType.SERVER_SENT_EVENTS)
        public void events(@Context SseEventSink sink, @Context Sse sse) throws IOException {
            sink.send(sse.newEvent("hi"));
            sink.close();
        }

        @DELETE
        @Path("/only-delete")
        public void delete() {
            // Only a DELETE is routed here: GET answers 405 and OPTIONS lists DELETE.
        }
    }

    private static CassiniStack stack(Class<?>... classes) {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(classes);
            }
        }).build();
    }

    private static final CassiniStack PLAIN = stack(Resource.class, TeapotMapper.class);

    private static InMemoryExchange send(CassiniStack stack, InMemoryExchange exchange) {
        stack.adapter().dispatch(exchange).toCompletableFuture().join();
        return exchange;
    }

    private static InMemoryExchange request(String method, String path) {
        return InMemoryExchange.request(method, path, null, null);
    }

    @Test
    void routingAnswersNotFoundNotAllowedOptionsAndHead() {
        assertEquals(404, send(PLAIN, request("GET", "/nowhere")).status());
        InMemoryExchange notAllowed = send(PLAIN, request("GET", "/r/only-delete"));
        assertEquals(405, notAllowed.status());
        assertTrue(notAllowed.responseHeader("Allow").contains("DELETE"));
        InMemoryExchange options = send(PLAIN, request("OPTIONS", "/r"));
        assertEquals(200, options.status());
        assertTrue(options.responseHeader("Allow").contains("HEAD"));
        InMemoryExchange head = send(PLAIN, request("HEAD", "/r"));
        assertEquals(200, head.status());
        assertEquals("", head.responseText());
        assertEquals(204, send(PLAIN, request("DELETE", "/r/only-delete")).status());
    }

    @Test
    void negotiationRefusesAnUnsupportedOrUnacceptableMediaType() {
        assertEquals(415, send(PLAIN, InMemoryExchange.request("POST", "/r", "application/xml", "<x/>")).status());
        assertEquals(406, send(PLAIN, request("GET", "/r/json").header("Accept", "text/html")).status());
        InMemoryExchange echo = send(PLAIN, InMemoryExchange.request("POST", "/r", "text/plain", "body"));
        assertEquals("got body", echo.responseText());
    }

    @Test
    void theResultOfAMethodBecomesTheResponse() {
        assertEquals(204, send(PLAIN, request("GET", "/r/none")).status());
        InMemoryExchange built = send(PLAIN, request("GET", "/r/response"));
        assertEquals("built", built.responseText());
        assertEquals("yes", built.responseHeader("X-Built"));
        InMemoryExchange noEntity = send(PLAIN, request("GET", "/r/no-entity"));
        assertEquals(202, noEntity.status());
        assertEquals("1", noEntity.responseHeader("X-Accepted"));
        assertEquals("generic", send(PLAIN, request("GET", "/r/generic")).responseText());
        assertEquals(418, send(PLAIN, request("GET", "/r/throws")).status());
    }

    @Test
    void aSuspendedResponseIsAwaitedRenderedOrMapped() {
        assertEquals("resumed", send(PLAIN, request("GET", "/r/async")).responseText());
        assertEquals(409, send(PLAIN, request("GET", "/r/async-wae")).status());
        assertEquals(418, send(PLAIN, request("GET", "/r/async-mapped")).status());
        assertEquals(500, send(PLAIN, request("GET", "/r/async-unmapped")).status());
    }

    @Test
    void aCompletionStageIsAwaitedRenderedOrMapped() {
        assertEquals("staged", send(PLAIN, request("GET", "/r/stage")).responseText());
        assertEquals(410, send(PLAIN, request("GET", "/r/stage-wae")).status());
        assertEquals(418, send(PLAIN, request("GET", "/r/stage-mapped")).status());
        assertEquals(500, send(PLAIN, request("GET", "/r/stage-unmapped")).status());
    }

    /** Reroutes a request carrying X-Reroute: setMethod and setRequestUri before routing (§6.6.1). */
    @Provider
    @PreMatching
    public static class Rerouting implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext ctx) throws IOException {
            if (ctx.getHeaderString("X-Reroute") != null) {
                ctx.setMethod("GET");
                ctx.setRequestUri(ctx.getUriInfo().getBaseUri().resolve("r/response"));
            }
            if (ctx.getHeaderString("X-Pre-IO") != null) throw new IOException("pre-matching I/O");
            if (ctx.getHeaderString("X-Secure") != null) ctx.setSecurityContext(SECURE);
            if (ctx.getHeaderString("X-Pre-Boom") != null) throw new IllegalStateException("boom");
            if (ctx.getHeaderString("X-Pre-Abort") != null) {
                ctx.abortWith(Response.status(451).build());
            }
            if (ctx.getHeaderString("X-Pre-Fail") != null) {
                throw new Teapot();
            }
        }
    }

    /** Aborts or fails a request after matching, on demand. */
    @Provider
    public static class Guard implements ContainerRequestFilter {
        @Override
        public void filter(ContainerRequestContext ctx) throws IOException {
            if (ctx.getHeaderString("X-Abort") != null) ctx.abortWith(Response.status(403).build());
            if (ctx.getHeaderString("X-Fail") != null) throw new Teapot();
            if (ctx.getHeaderString("X-Fail-IO") != null) throw new IOException("filter I/O");
        }
    }

    /** Marks every response, and wraps the entity stream on demand (§6.7.4.2). */
    @Provider
    public static class Marker implements ContainerResponseFilter {
        @Override
        public void filter(ContainerRequestContext request, ContainerResponseContext response) throws IOException {
            if (request.getHeaderString("X-Resp-IO") != null) throw new IOException("response filter I/O");
            response.getHeaders().add("X-Seen", "1");
            if (request.getHeaderString("X-Wrap") != null) {
                OutputStream original = response.getEntityStream();
                response.setEntityStream(new FilterOutputStream(original) {
                    @Override
                    public void write(int b) throws IOException {
                        out.write(Character.toUpperCase(b));
                    }
                });
            }
        }
    }

    /** A security context that says the request is secure. */
    static final SecurityContext SECURE = new SecurityContext() {
        @Override public java.security.Principal getUserPrincipal() { return null; }
        @Override public boolean isUserInRole(String role) { return false; }
        @Override public boolean isSecure() { return true; }
        @Override public String getAuthenticationScheme() { return null; }
    };

    /** Fails the writing of an entity on demand: mapped, or rendered as a WebApplicationException. */
    @Provider
    public static class FailingWriter implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            // Only the entity of the resource method fails: the mapped response is written again.
            var request = Invoker.currentRequest();
            String mode = request == null || !"hello".equals(context.getEntity()) ? null : request.firstHeader("X-Write");
            if ("fail".equals(mode)) throw new Teapot();
            if ("wae".equals(mode)) throw new WebApplicationException(412);
            context.proceed();
        }
    }

    @Provider
    public static class Writing implements WriterInterceptor {
        @Override
        public void aroundWriteTo(WriterInterceptorContext context) throws IOException {
            context.getHeaders().add("X-Written", "1");
            context.proceed();
        }
    }

    @Provider
    public static class Reading implements ReaderInterceptor {
        @Override
        public Object aroundReadFrom(ReaderInterceptorContext context) throws IOException {
            return context.proceed();
        }
    }

    private static final CassiniStack FILTERED = stack(Resource.class, TeapotMapper.class, Rerouting.class,
            Guard.class, Marker.class, Writing.class, Reading.class, FailingWriter.class);

    @Test
    void filtersAndInterceptorsRunAroundTheMethod() {
        InMemoryExchange plain = send(FILTERED, request("GET", "/r"));
        assertEquals("hello", plain.responseText());
        assertEquals("1", plain.responseHeader("X-Seen"));
        assertEquals("1", plain.responseHeader("X-Written"));
        assertEquals("got body", send(FILTERED, InMemoryExchange.request("POST", "/r", "text/plain", "body"))
                .responseText());
        assertEquals("HELLO", send(FILTERED, request("GET", "/r").header("X-Wrap", "1")).responseText());
        assertEquals("built", send(FILTERED, request("POST", "/nowhere").header("X-Reroute", "1")).responseText());
    }

    @Test
    void aFilterAbortsOrFailsTheRequest() {
        assertEquals(451, send(FILTERED, request("GET", "/r").header("X-Pre-Abort", "1")).status());
        assertEquals(418, send(FILTERED, request("GET", "/r").header("X-Pre-Fail", "1")).status());
        InMemoryExchange aborted = send(FILTERED, request("GET", "/r").header("X-Abort", "1"));
        assertEquals(403, aborted.status());
        assertEquals("1", aborted.responseHeader("X-Seen"));
        assertEquals(418, send(FILTERED, request("GET", "/r").header("X-Fail", "1")).status());
        assertEquals(500, send(FILTERED, request("GET", "/r").header("X-Fail-IO", "1")).status());
    }

    @Test
    void aFailureAroundTheMethodIsMappedRenderedOrAServerError() {
        assertEquals(500, send(FILTERED, request("GET", "/r").header("X-Pre-IO", "1")).status());
        assertEquals(500, send(FILTERED, request("GET", "/r").header("X-Pre-Boom", "1")).status());
        assertEquals(500, send(FILTERED, request("GET", "/r").header("X-Resp-IO", "1")).status());
        assertEquals(418, send(FILTERED, request("GET", "/r").header("X-Write", "fail")).status());
        assertEquals(412, send(FILTERED, request("GET", "/r").header("X-Write", "wae")).status());
        assertEquals(400, send(FILTERED, InMemoryExchange.request("POST", "/r/number", "text/plain", "abc")).status());
        assertEquals(500, send(FILTERED, InMemoryExchange.request("POST", "/r", "text/plain", "x").failingBody())
                .status());
        assertEquals(500, send(PLAIN, InMemoryExchange.request("POST", "/r", "text/plain", "x").failingBody())
                .status());
    }

    @Test
    void filtersSeeTheSecurityContextAndTheEntityAnnotations() {
        assertEquals("true", send(FILTERED, request("GET", "/r/secure").header("X-Secure", "1")).responseText());
        assertEquals("false", send(FILTERED, request("GET", "/r/secure")).responseText());
        assertEquals("ann", send(FILTERED, request("GET", "/r/annotated")).responseText());
        assertEquals("ann", send(PLAIN, request("GET", "/r/annotated")).responseText());
    }

    @Test
    void anEntityIsReadWhateverItsAnnouncedLengthAndWrittenWithItsWriterMediaType() {
        assertEquals("got abcd", send(PLAIN, InMemoryExchange.request("POST", "/r", "text/plain", "abcd")
                .replaceHeader("Content-Length", "2")).responseText());
        assertEquals("got abcd", send(PLAIN, InMemoryExchange.request("POST", "/r", "text/plain", "abcd")
                .replaceHeader("Content-Length", null)).responseText());
        InMemoryExchange number = send(PLAIN, request("GET", "/r/number-out"));
        assertEquals("42", number.responseText());
        assertTrue(number.responseContentType().startsWith("text/plain"));
    }

    @Test
    void serverSentEventsAreBufferedWithoutAStreamingTransport() {
        InMemoryExchange events = send(PLAIN, request("GET", "/r/events"));
        assertEquals(200, events.status());
        assertTrue(events.responseText().contains("hi"), events.responseText());
    }

    @Test
    void theContextPathIsLeftOutOfRoutingAndRerouting() {
        assertEquals("hello", send(FILTERED, request("GET", "/app/r").contextPath("/app")).responseText());
        assertEquals("built", send(FILTERED, request("POST", "/app/nowhere").contextPath("/app")
                .header("X-Reroute", "1")).responseText());
    }

    @Test
    void aMethodWithoutFiltersHasNoResponseFilterHeader() {
        assertNull(send(PLAIN, request("GET", "/r")).responseHeader("X-Seen"));
        assertEquals(List.of(), List.of());
    }
}
