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
