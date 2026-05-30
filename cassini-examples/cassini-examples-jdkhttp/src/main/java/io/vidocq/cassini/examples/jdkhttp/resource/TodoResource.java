package io.vidocq.cassini.examples.jdkhttp.resource;

import io.vidocq.cassini.examples.jdkhttp.model.Todo;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CRUD Todos resource — static store for the standalone example.
 */
@Path("/todos")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class TodoResource {

    static final Map<Long, Todo> STORE = new ConcurrentHashMap<>();
    static final AtomicLong COUNTER = new AtomicLong(0);

    public static void reset() {
        STORE.clear();
        COUNTER.set(0);
    }

    @GET
    public List<Todo> list() {
        return new ArrayList<>(STORE.values());
    }

    @POST
    public Response create(Todo input) {
        long id = COUNTER.incrementAndGet();
        var todo = new Todo(id, input.title(), input.done());
        STORE.put(id, todo);
        return Response.status(Response.Status.CREATED).entity(todo).build();
    }

    @GET
    @Path("/{id}")
    public Response get(@PathParam("id") long id) {
        var todo = STORE.get(id);
        if (todo == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(todo).build();
    }

    @PUT
    @Path("/{id}")
    public Response update(@PathParam("id") long id, Todo input) {
        var existing = STORE.get(id);
        if (existing == null) return Response.status(Response.Status.NOT_FOUND).build();
        var updated = existing.withUpdate(input.title(), input.done());
        STORE.put(id, updated);
        return Response.ok(updated).build();
    }

    @DELETE
    @Path("/{id}")
    public Response delete(@PathParam("id") long id) {
        if (STORE.remove(id) == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.noContent().build();
    }
}
