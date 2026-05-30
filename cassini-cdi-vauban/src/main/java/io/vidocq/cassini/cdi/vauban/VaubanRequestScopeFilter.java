/*
 * Copyright (c) 2026 Vidocq contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */
package io.vidocq.cassini.cdi.vauban;

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.annotation.Priority;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

/**
 * Activates the Vauban {@link io.vidocq.vauban.core.context.RequestContext} around
 * each Cassini HTTP request and deactivates it on response. Required for JAX-RS
 * {@code @RequestScoped} resources (= every {@code @Path} class after the
 * {@link CassiniScopeExtension} BCE runs) to be instantiable — otherwise the CDI
 * proxy throws {@code ContextNotActiveException: RequestScope is not active}.
 *
 * <p>Auto-registered by {@link VaubanBeanProvider#getResourceClasses()} and
 * instantiated as a singleton via {@link VaubanBeanProvider#getBean(Class)} —
 * transparent for the user: every {@code CassiniStack} built on top of a current
 * Vauban container has this filter active.</p>
 *
 * <p>Priorities:</p>
 * <ul>
 *   <li>{@link ContainerRequestFilter} {@code @PreMatching} + {@code Integer.MIN_VALUE}:
 *       runs before every other filter (auth, tracing, etc.) — the scope must be
 *       active BEFORE other filters can inject {@code @RequestScoped} beans.</li>
 *   <li>{@link ContainerResponseFilter} {@code Integer.MAX_VALUE}: runs last —
 *       deactivates the scope once every response filter had the chance to access
 *       request-scoped beans.</li>
 * </ul>
 *
 * <p><strong>Flush exception note</strong>: if an exception escapes the resource
 * AND is not mapped by an {@code ExceptionMapper} returning a Response, the response
 * filter may not be invoked depending on the container. Cassini's Invoker guarantees
 * response filters are called even via ExceptionMapper (cf. M6d.6 /
 * HumboldtSpanFinalizer), so {@code deactivate()} is always executed in practice.
 * Should that contract ever be violated, the RequestContext remains "active" on the
 * current virtual thread — since VTs are per-request, the leak is bounded to the
 * VT's lifetime (terminated after the request).</p>
 */
@Provider
@PreMatching
@Priority(Integer.MIN_VALUE)
public final class VaubanRequestScopeFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String SCOPE_ACTIVE_PROPERTY = "io.vidocq.cassini.cdi.vauban.requestScopeActive";

    private final VaubanContainer container;

    /**
     * Singleton — instantiated by {@link VaubanBeanProvider#getBean(Class)} which
     * passes the current {@code VaubanContainer}. The public constructor lets test
     * harnesses (cf. humboldt-tck/CassiniHarness) that do not use
     * {@code CassiniStackBuilder.beanProvider(...)} register the filter manually
     * via {@code .provider(new VaubanRequestScopeFilter(container))}.
     * Vauban does not treat it as a managed bean (constructor with a non-default
     * argument → not instantiable by the standard BCE pipeline).
     */
    public VaubanRequestScopeFilter(VaubanContainer container) {
        this.container = container;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        container.requestContext().activate();
        requestContext.setProperty(SCOPE_ACTIVE_PROPERTY, Boolean.TRUE);
    }

    @Override
    public void filter(ContainerRequestContext requestContext, ContainerResponseContext responseContext) {
        if (Boolean.TRUE.equals(requestContext.getProperty(SCOPE_ACTIVE_PROPERTY))) {
            container.requestContext().deactivate();
            requestContext.removeProperty(SCOPE_ACTIVE_PROPERTY);
        }
    }
}
