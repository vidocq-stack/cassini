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
package io.vidocq.cassini.tck;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * §3.5.4 / RFC 7578: when a CLIENT request has Content-Type=multipart/form-data
 * without a boundary parameter, generate one and inject it into the Content-Type
 * BEFORE the MBW is invoked. Without this, the server cannot parse the body
 * (unknown boundary).
 */
@Provider
public final class CassiniMultipartBoundaryFilter implements ClientRequestFilter {

    @Override
    public void filter(ClientRequestContext requestContext) {
        MediaType mt = requestContext.getMediaType();
        if (mt == null) return;
        if (!mt.isCompatible(MediaType.MULTIPART_FORM_DATA_TYPE)) return;
        if (mt.getParameters().get("boundary") != null) return;
        String boundary = "Boundary_" + UUID.randomUUID().toString().replace("-", "");
        java.util.Map<String, String> params = new java.util.LinkedHashMap<>(mt.getParameters());
        params.put("boundary", boundary);
        MediaType withBoundary = new MediaType(mt.getType(), mt.getSubtype(), params);
        // Update both: the MediaType that the MBW will see and the HTTP header
        // that Jersey will send on the wire.
        requestContext.getHeaders().putSingle("Content-Type", withBoundary);
        if (requestContext.hasEntity()) {
            requestContext.setEntity(requestContext.getEntity(),
                    requestContext.getEntityAnnotations(), withBoundary);
        }
    }
}
