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
package io.vidocq.cassini.cdi.vauban;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Activates the Vauban {@link io.vidocq.vauban.core.context.RequestContext} around
 * each Cassini HTTP request, then deactivates it on the way out. This is required so
 * that {@code @RequestScoped} JAX-RS resources (= every {@code @Path} class after the
 * {@link io.vidocq.cassini.cdi.CassiniScopeExtension} BCE) can be instantiated — without it, the CDI proxy
 * throws {@code ContextNotActiveException: RequestScope is not active}.
 *
 * <p>Automatically registered by {@link VaubanBeanProvider#getResourceClasses()}
 * and instantiated as a singleton via {@link VaubanBeanProvider#getBean(Class)} —
 * transparent to the user: any {@code CassiniStack} built on top of a current
 * Vauban container has the filter active.</p>
 *
 * <p>A {@link ContainerRequestFilter} {@code @PreMatching} + {@code Integer.MIN_VALUE}: it runs
 * before any other filter (auth, tracing, etc.) — the scope must be active BEFORE other filters
 * can inject {@code @RequestScoped} beans.</p>
 *
 * <p><strong>The scope ends with the request, not with the response filters.</strong> The filter
 * hands the request an end-of-request action ({@value #REQUEST_COMPLETED}), and Cassini runs it
 * once the dispatch is over: after the response filters, after the entity is written (a
 * {@code MessageBodyWriter}, a {@code WriterInterceptor} or a {@code StreamingOutput} can still
 * use a {@code @RequestScoped} bean), and on every path — a match, a 404 or 405, a mapped or
 * unmapped failure, a failed write, an async response resumed from another thread. The scope is
 * therefore always deactivated, and a test proves it (cassini#116).</p>
 *
 * <p>The filter is inert when the scope is already active on the thread — the Vidocq runtime
 * binds one around each request ({@code RequestContext#runInScope}) and ends it itself: it
 * neither activates a second time nor deactivates a scope that is not its own.</p>
 */
@Provider
@PreMatching
@Priority(Integer.MIN_VALUE)
public final class VaubanRequestScopeFilter implements ContainerRequestFilter {

    /** The request property Cassini reads at the end of the dispatch: a {@link Runnable}. */
    static final String REQUEST_COMPLETED = "io.vidocq.cassini.requestCompleted";

    private final VaubanContainer container;

    /**
     * Singleton — instantiated by {@link VaubanBeanProvider#getBean(Class)} with
     * the current {@code VaubanContainer}. Public constructor so that test
     * harnesses (cf. humboldt-tck/CassiniHarness) that do not use
     * {@code CassiniStackBuilder.beanProvider(...)} can register the
     * filter manually via {@code .provider(new VaubanRequestScopeFilter(container))}.
     * Vauban does not consider it a managed bean (constructor with a non-default
     * argument → not instantiable by the standard BCE pipeline).
     */
    public VaubanRequestScopeFilter(VaubanContainer container) {
        this.container = container;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        var scope = container.requestContext();
        if (scope.isActive()) {
            return;
        }
        scope.activate();
        Runnable end = scope::deactivate;
        Object previous = requestContext.getProperty(REQUEST_COMPLETED);
        requestContext.setProperty(REQUEST_COMPLETED, previous instanceof Runnable other ? after(end, other) : end);
    }

    /** Both actions run, the scope first, even when one of them fails. */
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
