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
 * each Cassini HTTP request, then deactivates it on the way out. This is required so
 * that {@code @RequestScoped} JAX-RS resources (= every {@code @Path} class after the
 * {@link CassiniScopeExtension} BCE) can be instantiated — without it, the CDI proxy
 * throws {@code ContextNotActiveException: RequestScope is not active}.
 *
 * <p>Automatically registered by {@link VaubanBeanProvider#getResourceClasses()}
 * and instantiated as a singleton via {@link VaubanBeanProvider#getBean(Class)} —
 * transparent to the user: any {@code CassiniStack} built on top of a current
 * Vauban container has the filter active.</p>
 *
 * <p>Priorities:</p>
 * <ul>
 *   <li>{@link ContainerRequestFilter} {@code @PreMatching} + {@code Integer.MIN_VALUE}:
 *       runs before any other filter (auth, tracing, etc.) — the scope must be
 *       active BEFORE other filters can inject {@code @RequestScoped} beans.</li>
 *   <li>{@link ContainerResponseFilter} {@code Integer.MAX_VALUE}: runs last —
 *       deactivates the scope once all response filters have had a chance to
 *       access request-scoped beans.</li>
 * </ul>
 *
 * <p><strong>Flush exception note</strong>: if an exception escapes the resource AND
 * is not mapped by an {@code ExceptionMapper} returning a Response, the response
 * filter may not be called depending on the container.
 * Cassini Invoker guarantees that response filters are called even through an
 * ExceptionMapper (cf. M6d.6 / HumboldtSpanFinalizer), so {@code deactivate()} is
 * always executed in practice. If that contract is ever violated, the RequestContext
 * remains "active" on the current virtual thread — since VTs are created per request,
 * the leak is bounded to the VT lifetime (ended after the request).</p>
 */
@Provider
@PreMatching
@Priority(Integer.MIN_VALUE)
public final class VaubanRequestScopeFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String SCOPE_ACTIVE_PROPERTY = "io.vidocq.cassini.cdi.vauban.requestScopeActive";

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
