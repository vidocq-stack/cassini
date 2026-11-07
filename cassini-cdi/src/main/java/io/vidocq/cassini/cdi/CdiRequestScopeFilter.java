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
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Activates the CDI request context around each Cassini request, through the standard
 * {@link RequestContextController}, and deactivates it on the way out — so {@code @RequestScoped}
 * resources and beans work under any CDI container.
 *
 * <p>The controller that activated the context deactivates it: a {@code RequestContextController}
 * must be deactivated by the instance that activated it. When the context is already active (an
 * embedding runtime or an application server activated it), {@code activate()} returns {@code false}
 * and nothing is deactivated here.</p>
 *
 * <p>First among the request filters ({@code @PreMatching}, {@code Integer.MIN_VALUE}). <strong>The
 * context ends with the request, not with the response filters</strong>: the filter hands the request
 * an end-of-request action ({@value #REQUEST_COMPLETED}), and Cassini runs it once the dispatch is
 * over, on the thread that activated the context — after the response filters, after the entity is
 * written (a {@code MessageBodyWriter}, a {@code WriterInterceptor} or a {@code StreamingOutput} can
 * still use a {@code @RequestScoped} bean), and on every path: a match, a 404 or 405, a mapped or
 * unmapped failure, a failed write, an async response resumed from another thread. The context is
 * therefore always deactivated, and a test on Weld proves it (cassini#116).</p>
 */
@Provider
@PreMatching
@Priority(Integer.MIN_VALUE)
public final class CdiRequestScopeFilter implements ContainerRequestFilter {

    /** The request property Cassini reads at the end of the dispatch: a {@link Runnable}. */
    static final String REQUEST_COMPLETED = "io.vidocq.cassini.requestCompleted";

    private final CDI<Object> cdi;

    CdiRequestScopeFilter(CDI<Object> cdi) {
        this.cdi = cdi;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        RequestContextController controller = cdi.select(RequestContextController.class).get();
        if (controller.activate()) {
            Runnable end = controller::deactivate;
            Object previous = requestContext.getProperty(REQUEST_COMPLETED);
            requestContext.setProperty(REQUEST_COMPLETED, previous instanceof Runnable other ? after(end, other) : end);
        }
    }

    /** Both actions run, the context first, even when one of them fails. */
    private static Runnable after(Runnable first, Runnable second) {
        return () -> {
            try {
                first.run();
            } finally {
                second.run();
            }
        };
    }
}
