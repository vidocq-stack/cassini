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
package io.vidocq.cassini.cdi;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Activates the CDI request context around each Cassini request, through the standard
 * {@link RequestContextController}, and deactivates it on the way out — so {@code @RequestScoped}
 * resources and beans work under any CDI container.
 *
 * <p>The controller that activated the context is kept on the request and deactivates it: a
 * {@code RequestContextController} must be deactivated by the instance that activated it. When the
 * context is already active (an embedding runtime activated it), {@code activate()} returns
 * {@code false} and nothing is deactivated here.</p>
 *
 * <p>Priorities as for the Vauban filter: first among the request filters, last among the response
 * filters. Cassini runs the response filters after an {@code ExceptionMapper} too, so the context
 * is always deactivated.</p>
 */
@Provider
@PreMatching
@Priority(Integer.MIN_VALUE)
public final class CdiRequestScopeFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String CONTROLLER_PROPERTY = "io.vidocq.cassini.cdi.requestContextController";

    private final CDI<Object> cdi;

    CdiRequestScopeFilter(CDI<Object> cdi) {
        this.cdi = cdi;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        RequestContextController controller = cdi.select(RequestContextController.class).get();
        if (controller.activate()) {
            requestContext.setProperty(CONTROLLER_PROPERTY, controller);
        }
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (requestContext.getProperty(CONTROLLER_PROPERTY) instanceof RequestContextController controller) {
            requestContext.removeProperty(CONTROLLER_PROPERTY);
            controller.deactivate();
        }
    }
}
