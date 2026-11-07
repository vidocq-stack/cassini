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
package io.vidocq.cassini.it.weld;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.AsyncResponse;
import jakarta.ws.rs.container.Suspended;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.StreamingOutput;
import java.nio.charset.StandardCharsets;

/** Resources that touch a request-scoped bean late: while the entity is written, or after a resume. */
@RequestScoped
@Path("/lifetime")
@Produces(MediaType.TEXT_PLAIN)
public class LifetimeResource {

    @Inject
    RequestToken token;

    /** The bean is touched while the entity is written, after every response filter. */
    @GET
    @Path("/stream")
    public Response stream() {
        StreamingOutput body = out -> out.write(token.value().getBytes(StandardCharsets.UTF_8));
        return Response.ok(body).build();
    }

    @GET
    @Path("/boom")
    public String boom() {
        throw new IllegalStateException("not mapped");
    }

    /** The token is read on the dispatch thread, then the response is resumed from another thread. */
    @GET
    @Path("/later")
    public void later(@Suspended AsyncResponse response) {
        String value = token.value();
        Thread.ofVirtual().start(() -> response.resume(value));
    }
}
