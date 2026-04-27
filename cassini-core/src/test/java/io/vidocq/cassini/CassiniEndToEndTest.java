package io.vidocq.cassini;

import fr.vidocq.chappe.api.Server;
import io.vidocq.cassini.internal.CassiniRestBridge;
import io.vidocq.cassini.internal.ExceptionMapperRegistry;
import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MessageBodyRegistry;
import io.vidocq.cassini.internal.ResourceMethod;
import io.vidocq.cassini.internal.ResourceScanner;
import io.vidocq.cassini.internal.UriRouter;
import io.vidocq.cassini.internal.filter.FilterRegistry;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.FormParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests end-to-end M1 : démarre Chappe + CassiniRestBridge sans CDI (resolver
 * de beans injecté manuellement) et valide routing + marshalling minimal.
 */
class CassiniEndToEndTest {

    private Server server;
    private int port;

    @AfterEach
    void tearDown() { if (server != null) server.stop(); }

    @Path("/hello")
    public static class HelloResource {
        @GET
        public String hello() { return "Hello from Cassini!"; }
    }

    @Path("/api")
    public static class ApiResource {
        @GET
        @Path("/ping")
        @Produces("application/json")
        public String ping() { return "{\"pong\":true}"; }

        @POST
        @Path("/ping")
        public void log() { /* void → 204 */ }
    }

    @Path("/users")
    public static class UserResource {
        @GET @Path("/me")     public String me()      { return "current-user"; }
        @GET @Path("/{id}")   public String byId()    { return "by-id"; }
    }

    @Path("/resp")
    public static class ResponseResource {
        @GET @Path("/created")
        public Response created() {
            return Response.status(201).entity("new").header("Location", "/resp/42").build();
        }

        @GET @Path("/notfound")
        public Response notFound() {
            return Response.status(Response.Status.NOT_FOUND).entity("missing").build();
        }

        @GET @Path("/boom")
        public String boom() { throw new MyDomainException("kaboom"); }
    }

    public static class MyDomainException extends RuntimeException {
        public MyDomainException(String m) { super(m); }
    }

    public static final class MyDomainExceptionMapper implements ExceptionMapper<MyDomainException> {
        @Override public Response toResponse(MyDomainException e) {
            return Response.status(418).entity("mapped:" + e.getMessage()).build();
        }
    }

    @Path("/ctx")
    public static class ContextResource {
        @GET @Path("/uri/{id}")
        public String uri(@PathParam("id") String id, @Context UriInfo info) {
            return "path=" + info.getPath()
                    + ";params=" + info.getPathParameters().getFirst("id")
                    + ";queryFoo=" + info.getQueryParameters().getFirst("foo");
        }

        @GET @Path("/headers")
        public String headers(@Context HttpHeaders h) {
            String xUser = h.getHeaderString("X-User");
            return "xUser=" + xUser + ";accepts=" + h.getAcceptableMediaTypes().size();
        }

        @GET @Path("/method")
        public String method(@Context jakarta.ws.rs.core.Request r) {
            return "m=" + r.getMethod();
        }

        @GET @Path("/sec")
        public String sec(@Context SecurityContext s) {
            return "secure=" + s.isSecure() + ";principal=" + s.getUserPrincipal();
        }
    }

    @Path("/body")
    public static class BodyResource {
        @POST @Path("/echo-string")
        @Consumes("text/plain") @Produces("text/plain")
        public String echoString(String body) { return "echo:" + body; }

        @POST @Path("/echo-bytes")
        @Consumes("application/octet-stream")
        @Produces("application/octet-stream")
        public byte[] echoBytes(byte[] body) { return body; }

        @PUT @Path("/json-only")
        @Consumes("application/json")
        public String jsonOnly(String body) { return "got:" + body; }

        @GET @Path("/json")
        @Produces("application/json")
        public String json() { return "{\"k\":1}"; }

        @GET @Path("/xml")
        @Produces("application/xml")
        public String xml() { return "<k>1</k>"; }
    }

    @Path("/params")
    public static class ParamResource {
        @GET @Path("/path/{id}")
        public String path(@PathParam("id") int id) {
            return "id=" + id;
        }

        @GET @Path("/query")
        public String query(@QueryParam("q") String q,
                            @QueryParam("n") @DefaultValue("5") int n) {
            return "q=" + q + ";n=" + n;
        }

        @GET @Path("/tags")
        public String tags(@QueryParam("tag") List<String> tags) {
            return "tags=" + String.join(",", tags);
        }

        @GET @Path("/header")
        public String header(@HeaderParam("X-User") String user) {
            return "user=" + user;
        }

        @GET @Path("/cookie")
        public String cookie(@CookieParam("session") String sid) {
            return "sid=" + sid;
        }

        @POST @Path("/form")
        public String form(@FormParam("name") String name,
                           @FormParam("age") int age) {
            return "name=" + name + ";age=" + age;
        }
    }

    @Test
    void getHelloReturnsPlainText() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = get("/hello");
        assertEquals(200, r.statusCode());
        assertEquals("Hello from Cassini!", r.body());
        assertTrue(r.headers().firstValue("Content-Type").orElse("")
                .startsWith("text/plain"));
    }

    @Test
    void getApiPingReturnsJson() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = get("/api/ping");
        assertEquals(200, r.statusCode());
        assertEquals("{\"pong\":true}", r.body());
        assertEquals("application/json",
                r.headers().firstValue("Content-Type").orElse(""));
    }

    @Test
    void voidReturnYields204() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = post("/api/ping");
        assertEquals(204, r.statusCode());
    }

    @Test
    void unknownPathYields404() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = get("/nope");
        assertEquals(404, r.statusCode());
    }

    @Test
    void wrongMethodYields405WithAllow() throws Exception {
        start(new HelloResource(), new ApiResource());

        HttpResponse<String> r = post("/hello");
        assertEquals(405, r.statusCode());
        assertEquals(Optional.of("GET"), r.headers().firstValue("Allow"));
    }

    @Test
    void literalBeatsTemplate() throws Exception {
        start(new UserResource());

        assertEquals("current-user", get("/users/me").body());
        assertEquals("by-id", get("/users/42").body());
    }

    @Test
    void pathParamCoercesToInt() throws Exception {
        start(new ParamResource());
        assertEquals("id=42", get("/params/path/42").body());
    }

    @Test
    void queryParamWithDefault() throws Exception {
        start(new ParamResource());
        assertEquals("q=hi;n=5", get("/params/query?q=hi").body());
        assertEquals("q=hi;n=12", get("/params/query?q=hi&n=12").body());
    }

    @Test
    void queryParamAsList() throws Exception {
        start(new ParamResource());
        assertEquals("tags=a,b,c", get("/params/tags?tag=a&tag=b&tag=c").body());
    }

    @Test
    void headerParam() throws Exception {
        start(new ParamResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/params/header"))
                .header("X-User", "claude").GET().build());
        assertEquals("user=claude", r.body());
    }

    @Test
    void cookieParam() throws Exception {
        start(new ParamResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/params/cookie"))
                .header("Cookie", "session=xyz; tracker=1").GET().build());
        assertEquals("sid=xyz", r.body());
    }

    @Test
    void formParam() throws Exception {
        start(new ParamResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/params/form"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("name=yann&age=42"))
                .build());
        assertEquals("name=yann;age=42", r.body());
    }

    @Test
    void bodyStringRoundTrip() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/echo-string"))
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString("hello"))
                .build());
        assertEquals(200, r.statusCode());
        assertEquals("echo:hello", r.body());
    }

    @Test
    void bodyByteArrayRoundTrip() throws Exception {
        start(new BodyResource());
        byte[] payload = {1, 2, 3, 4, 5};
        HttpResponse<byte[]> r = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/echo-bytes"))
                        .header("Content-Type", "application/octet-stream")
                        .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, r.statusCode());
        assertEquals("application/octet-stream",
                r.headers().firstValue("Content-Type").orElse(""));
        org.junit.jupiter.api.Assertions.assertArrayEquals(payload, r.body());
    }

    @Test
    void consumesMismatchYields415() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/json-only"))
                .header("Content-Type", "text/plain")
                .PUT(HttpRequest.BodyPublishers.ofString("x"))
                .build());
        assertEquals(415, r.statusCode());
    }

    @Test
    void producesMismatchYields406() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/json"))
                .header("Accept", "image/png").GET().build());
        assertEquals(406, r.statusCode());
    }

    @Test
    void acceptNegotiationPicksMatchingProduces() throws Exception {
        start(new BodyResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/body/json"))
                .header("Accept", "application/xml;q=0.1, application/json;q=0.9").GET().build());
        assertEquals(200, r.statusCode());
        assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
    }

    @Test
    void contextUriInfoExposesPathAndQueryParams() throws Exception {
        start(new ContextResource());
        HttpResponse<String> r = get("/ctx/uri/42?foo=bar");
        assertEquals(200, r.statusCode());
        assertTrue(r.body().contains("params=42"));
        assertTrue(r.body().contains("queryFoo=bar"));
    }

    @Test
    void contextHttpHeaders() throws Exception {
        start(new ContextResource());
        HttpResponse<String> r = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/ctx/headers"))
                .header("Accept", "application/json, text/plain")
                .header("X-User", "claude").GET().build());
        assertEquals(200, r.statusCode());
        assertEquals("xUser=claude;accepts=2", r.body());
    }

    @Test
    void contextRequestProvidesMethod() throws Exception {
        start(new ContextResource());
        HttpResponse<String> r = get("/ctx/method");
        assertEquals(200, r.statusCode());
        assertEquals("m=GET", r.body());
    }

    @Test
    void contextSecurityContextIsAnonymous() throws Exception {
        start(new ContextResource());
        HttpResponse<String> r = get("/ctx/sec");
        assertEquals(200, r.statusCode());
        assertEquals("secure=false;principal=null", r.body());
    }

    @Test
    void responseBuilderPropagatesStatusEntityAndHeaders() throws Exception {
        start(new ResponseResource());
        HttpResponse<String> r = get("/resp/created");
        assertEquals(201, r.statusCode());
        assertEquals("new", r.body());
        assertEquals(Optional.of("/resp/42"), r.headers().firstValue("Location"));
    }

    @Test
    void responseStatusFromEnum() throws Exception {
        start(new ResponseResource());
        HttpResponse<String> r = get("/resp/notfound");
        assertEquals(404, r.statusCode());
        assertEquals("missing", r.body());
    }

    @Test
    void exceptionMapperRendersTeapot() throws Exception {
        ExceptionMapperRegistry mappers = new ExceptionMapperRegistry();
        mappers.register(MyDomainException.class, new MyDomainExceptionMapper());
        startWith(mappers, new ResponseResource());

        HttpResponse<String> r = get("/resp/boom");
        assertEquals(418, r.statusCode());
        assertEquals("mapped:kaboom", r.body());
    }

    @Test
    void responseFilterAddsHeader() throws Exception {
        FilterRegistry fr = new FilterRegistry();
        fr.addResponse((ContainerRequestContext req, ContainerResponseContext resp) -> {
            resp.getHeaders().putSingle("X-Trace", "cassini");
        });
        startWith(fr, new HelloResource());

        HttpResponse<String> r = get("/hello");
        assertEquals(200, r.statusCode());
        assertEquals(Optional.of("cassini"), r.headers().firstValue("X-Trace"));
    }

    @Test
    void requestFilterAborts() throws Exception {
        FilterRegistry fr = new FilterRegistry();
        fr.addRequest((ContainerRequestContext req) -> {
            String tok = req.getHeaderString("X-Token");
            if (!"ok".equals(tok)) {
                req.abortWith(Response.status(401).entity("unauthorized").build());
            }
        });
        startWith(fr, new HelloResource());

        HttpResponse<String> r1 = get("/hello");
        assertEquals(401, r1.statusCode());
        assertEquals("unauthorized", r1.body());

        HttpResponse<String> r2 = send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/hello"))
                .header("X-Token", "ok").GET().build());
        assertEquals(200, r2.statusCode());
        assertEquals("Hello from Cassini!", r2.body());
    }

    @Test
    void requestFilterSetsProperty() throws Exception {
        FilterRegistry fr = new FilterRegistry();
        fr.addRequest(req -> req.setProperty("cassini.trace", "yes"));
        fr.addResponse((req, resp) -> resp.getHeaders().putSingle("X-Trace",
                String.valueOf(req.getProperty("cassini.trace"))));
        startWith(fr, new HelloResource());

        HttpResponse<String> r = get("/hello");
        assertEquals(200, r.statusCode());
        assertEquals(Optional.of("yes"), r.headers().firstValue("X-Trace"));
    }

    private void start(Object... resources) {
        startWith(new ExceptionMapperRegistry(), new FilterRegistry(), resources);
    }

    private void startWith(ExceptionMapperRegistry mappers, Object... resources) {
        startWith(mappers, new FilterRegistry(), resources);
    }

    private void startWith(FilterRegistry filters, Object... resources) {
        startWith(new ExceptionMapperRegistry(), filters, resources);
    }

    private void startWith(ExceptionMapperRegistry mappers, FilterRegistry filters, Object... resources) {
        Map<Class<?>, Object> beans = new HashMap<>();
        Class<?>[] classes = new Class<?>[resources.length];
        for (int i = 0; i < resources.length; i++) {
            beans.put(resources[i].getClass(), resources[i]);
            classes[i] = resources[i].getClass();
        }
        List<ResourceMethod> routes = ResourceScanner.discover(classes);
        UriRouter router = new UriRouter(routes);
        Invoker invoker = new Invoker(beans::get, new MessageBodyRegistry(), mappers);
        invoker.setFilters(filters);
        CassiniRestBridge bridge = new CassiniRestBridge(router, invoker);
        var res = TestServerLauncher.start(bridge);
        this.server = res.server;
        this.port = res.port;
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5)).GET().build());
    }

    private HttpResponse<String> post(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .POST(HttpRequest.BodyPublishers.noBody()).build());
    }

    private HttpResponse<String> send(HttpRequest req) throws Exception {
        return HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                .send(req, HttpResponse.BodyHandlers.ofString());
    }
}
