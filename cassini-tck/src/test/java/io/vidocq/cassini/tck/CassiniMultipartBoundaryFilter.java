package io.vidocq.cassini.tck;

import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.Provider;

import java.util.UUID;

/**
 * §3.5.4 / RFC 7578 : si une requête CLIENT a Content-Type=multipart/form-data
 * sans paramètre boundary, on en génère un et on le met dans le Content-Type
 * AVANT que le MBW ne soit appelé. Sans cela, le serveur ne peut pas parser
 * le body (boundary inconnu).
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
