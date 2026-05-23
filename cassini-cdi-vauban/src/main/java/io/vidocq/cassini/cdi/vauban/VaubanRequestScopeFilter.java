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
 * Active le {@link io.vidocq.vauban.core.context.RequestContext} Vauban autour de
 * chaque requête HTTP Cassini, puis le désactive en réponse. Indispensable pour que
 * les ressources JAX-RS {@code @RequestScoped} (= toute classe {@code @Path} après la
 * BCE {@link CassiniScopeExtension}) soient instanciables — sans ça, le proxy CDI
 * throws {@code ContextNotActiveException: RequestScope is not active}.
 *
 * <p>Enregistré automatiquement par {@link VaubanBeanProvider#getResourceClasses()}
 * et instancié comme singleton via {@link VaubanBeanProvider#getBean(Class)} —
 * transparent pour l'utilisateur : tout {@code CassiniStack} construit au-dessus
 * d'un container Vauban courant a le filter actif.</p>
 *
 * <p>Priorités :</p>
 * <ul>
 *   <li>{@link ContainerRequestFilter} {@code @PreMatching} + {@code Integer.MIN_VALUE} :
 *       s'exécute avant tout autre filter (auth, tracing, etc.) — le scope doit être
 *       actif AVANT que les autres filters puissent injecter des beans
 *       {@code @RequestScoped}.</li>
 *   <li>{@link ContainerResponseFilter} {@code Integer.MAX_VALUE} : s'exécute en
 *       dernier — désactive le scope une fois que tous les response filters ont
 *       eu l'occasion d'accéder aux beans request-scoped.</li>
 * </ul>
 *
 * <p><strong>Note flush exception</strong> : si une exception remonte hors de la
 * resource ET n'est pas mappée par un {@code ExceptionMapper} qui retourne une
 * Response, le response filter peut ne pas être appelé selon le container.
 * Cassini Invoker garantit l'appel des response filters même via ExceptionMapper
 * (cf. M6d.6 / HumboldtSpanFinalizer), donc le {@code deactivate()} est toujours
 * exécuté en pratique. Si jamais ce contrat est violé, le RequestContext reste
 * "actif" sur le virtual thread courant — comme les VT sont créés par requête, le
 * leak est borné à la durée du VT (terminé après la requête).</p>
 */
@Provider
@PreMatching
@Priority(Integer.MIN_VALUE)
public final class VaubanRequestScopeFilter implements ContainerRequestFilter, ContainerResponseFilter {

    private static final String SCOPE_ACTIVE_PROPERTY = "io.vidocq.cassini.cdi.vauban.requestScopeActive";

    private final VaubanContainer container;

    /**
     * Singleton — instancié par {@link VaubanBeanProvider#getBean(Class)} en passant
     * le {@code VaubanContainer} courant. Constructor public pour permettre aux
     * harness de test (cf. humboldt-tck/CassiniHarness) qui n'utilisent pas
     * {@code CassiniStackBuilder.beanProvider(...)} d'enregistrer manuellement le
     * filtre via {@code .provider(new VaubanRequestScopeFilter(container))}.
     * Vauban ne le considère pas comme bean managé (constructor avec argument
     * non-default → pas instanciable par le BCE pipeline standard).
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
