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
package io.vidocq.cassini.internal;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.transport.CassiniHttpResponse;
import io.vidocq.cassini.internal.gen.AdapterRegistry;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Parameter;
import java.util.Optional;

/**
 * §3.4.1 dynamic sub-resource dispatch, extracted from {@link Invoker} (which
 * stays the facade and delegates): executes locator chains whose return type
 * is {@code Object}, scans the effective runtime class of each returned
 * instance, routes the remaining path on a temporary mini-router, and invokes
 * the final method on the already-resolved instance — recursing when a
 * sub-route is itself a dynamic locator.
 */
final class DynamicLocatorDispatch {

    private final Invoker host;

    DynamicLocatorDispatch(Invoker host) {
        this.host = host;
    }

    /** §3.4.1: executes a dynamic-locator chain (return Object), scans
     *  the effective class of the returned instance, and delegates sub-routing
     *  to a temporary mini-router. If the sub-method is itself a dynamic
     *  locator (Object → Object → final), recurse. */
    CassiniHttpResponse invokeDynamicLocator(MatchResult match, CassiniHttpExchange request) throws Exception {
        ResourceMethod route = match.method();
        // 1. Instantiate root + invoke the locator chain
        Object root;
        try {
            root = host.resolver.apply(route.rootBeanClass());
        } catch (RuntimeException e) {
            return host.renderWebAppException(
                    new WebApplicationException("Cannot resolve root " + route.rootBeanClass().getName(), 500),
                    route, MediaType.WILDCARD_TYPE, null);
        }
        host.injectFields(route.rootBeanClass(), host.injectionTarget(route.rootBeanClass(), root), match, request, true);
        java.util.List<Object> matched = new java.util.ArrayList<>();
        matched.add(root);
        Invoker.publishCurrent(match, request);
        request.setAttribute(Invoker.ATTR_MATCHED_RESOURCES, matched);
        Object intermediate = root;
        for (java.lang.reflect.Method locStep : route.locatorChain()) {
            Parameter[] lps = locStep.getParameters();
            Object[] lArgs = lps.length == 0 ? new Object[0]
                    : ParamExtractor.resolveConstructorArgs(lps, match, request);
            locStep.setAccessible(true);
            try {
                intermediate = locStep.invoke(intermediate, lArgs);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return host.renderWebAppException(wae, route, MediaType.WILDCARD_TYPE, null);
                }
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }
            if (intermediate == null) {
                return host.renderWebAppException(
                        new WebApplicationException("Sub-resource locator returned null", 404),
                        route, MediaType.WILDCARD_TYPE, null);
            }
            if (intermediate instanceof Class<?> cls) {
                // M6a: prefer generated adapter newInstance() over reflection
                Object created = host.instantiateSubResourceClass(cls, route, MediaType.WILDCARD_TYPE, null);
                if (created instanceof CassiniHttpResponse) return (CassiniHttpResponse) created;
                intermediate = created;
            }
            host.injectFields(intermediate.getClass(), intermediate, match, request, false);
            matched.add(0, intermediate);
        }
        // 2. Compute the remaining path from capture {__rest:.*}
        String rest = "";
        if (match.pathParams().containsKey("__rest")) {
            var vs = match.pathParams().get("__rest");
            if (vs != null && !vs.isEmpty() && vs.get(0) != null) rest = vs.get(0);
        }
        String remaining = rest.isEmpty() ? "/" : "/" + rest;
        return dispatchOnInstance(intermediate, remaining, request, matched);
    }

    /** §3.4.1: dynamically scans {@code instance.getClass()} and resolves the
     *  best route for {@code remaining}+request HTTP method.
     *  Reuses {@code Invoker.invokeInternal} while passing the already-created instance
     *  via an ad-hoc resolver to avoid re-instantiation. */
    private CassiniHttpResponse dispatchOnInstance(Object instance, String remaining, CassiniHttpExchange request,
                                        java.util.List<Object> matchedSoFar) throws Exception {
        Class<?> cls = instance.getClass();
        // §3.6: if the runtime class has no root @Path, simulate it by
        // adding @Path("") through a scan wrapper. ResourceScanner.discover
        // requires @Path on the class — work around that by scanning locators
        // from a fake locator chain.
        java.util.List<ResourceMethod> subRoutes = scanInstanceClass(cls);
        if (subRoutes.isEmpty()) {
            return host.renderWebAppException(new jakarta.ws.rs.NotFoundException(),
                    null, MediaType.WILDCARD_TYPE, null);
        }
        UriRouter subRouter = new UriRouter(subRoutes);
        String httpMethod = request.method() == null ? "GET" : request.method();
        java.util.List<MatchResult> subCandidates = subRouter.matchAll(httpMethod, remaining);
        if (subCandidates.isEmpty()) {
            // 405 if another HTTP method matches the path
            var allowed = subRouter.methodsAllowedFor(remaining);
            if (!allowed.isEmpty()) {
                return host.renderWebAppException(
                        new jakarta.ws.rs.NotAllowedException(allowed.get(0),
                                allowed.subList(1, allowed.size()).toArray(String[]::new)),
                        null, MediaType.WILDCARD_TYPE, null);
            }
            return host.renderWebAppException(new jakarta.ws.rs.NotFoundException(),
                    null, MediaType.WILDCARD_TYPE, null);
        }
        MatchResult subMatch = host.pickBestMatch(subCandidates, request);
        ResourceMethod subRoute = subMatch.method();
        // Recurse if the sub-route is itself a dynamic locator
        if (subRoute.dynamicLocator()) {
            // Move up one level: invoke the sub-chain on the current instance
            return invokeDynamicLocatorWithInstance(subMatch, request, instance, matchedSoFar);
        }
        // Invoke the final method on the current instance through an ad-hoc resolver
        return invokeFinalOnInstance(subMatch, request, instance, matchedSoFar);
    }

    /** Variant of {@link #invokeDynamicLocator} starting from an already
     *  resolved instance (instead of the root class). */
    private CassiniHttpResponse invokeDynamicLocatorWithInstance(MatchResult match, CassiniHttpExchange request,
                                                      Object startInstance,
                                                      java.util.List<Object> matchedSoFar) throws Exception {
        ResourceMethod route = match.method();
        Object intermediate = startInstance;
        Invoker.publishCurrent(match, request);
        request.setAttribute(Invoker.ATTR_MATCHED_RESOURCES, matchedSoFar);
        for (java.lang.reflect.Method locStep : route.locatorChain()) {
            // Skip locators already executed (present at the top of the current
            // instance chain). A locator already run is recognized by being
            // declared on an "ancestor" class; here there are none,
            // because scanning restarted from intermediate.getClass(), so we
            // execute the whole sub-chain.
            Parameter[] lps = locStep.getParameters();
            Object[] lArgs = lps.length == 0 ? new Object[0]
                    : ParamExtractor.resolveConstructorArgs(lps, match, request);
            locStep.setAccessible(true);
            try {
                intermediate = locStep.invoke(intermediate, lArgs);
            } catch (InvocationTargetException ite) {
                Throwable cause = ite.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return host.renderWebAppException(wae, route, MediaType.WILDCARD_TYPE, null);
                }
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            }
            if (intermediate == null) {
                return host.renderWebAppException(
                        new WebApplicationException("Sub-resource locator returned null", 404),
                        route, MediaType.WILDCARD_TYPE, null);
            }
            if (intermediate instanceof Class<?> cls) {
                // M6a: prefer generated adapter newInstance() over reflection
                Object created = host.instantiateSubResourceClass(cls, route, MediaType.WILDCARD_TYPE, null);
                if (created instanceof CassiniHttpResponse) return (CassiniHttpResponse) created;
                intermediate = created;
            }
            host.injectFields(intermediate.getClass(), intermediate, match, request, false);
            matchedSoFar.add(0, intermediate);
        }
        String rest = "";
        if (match.pathParams().containsKey("__rest")) {
            var vs = match.pathParams().get("__rest");
            if (vs != null && !vs.isEmpty() && vs.get(0) != null) rest = vs.get(0);
        }
        String remaining = rest.isEmpty() ? "/" : "/" + rest;
        return dispatchOnInstance(intermediate, remaining, request, matchedSoFar);
    }

    /** Invokes the final method of a sub-route using {@code instance}
     *  as the target (instead of re-instantiating via {@code resolver}). Uses
     *  an ad-hoc resolver that returns the instance for the expected class. */
    private CassiniHttpResponse invokeFinalOnInstance(MatchResult match, CassiniHttpExchange request,
                                           Object instance,
                                           java.util.List<Object> matchedSoFar) throws Exception {
        ResourceMethod route = match.method();
        // Reproduce a subset of the flow here (no filters, no
        // pre/post-matching for this synthetic dynamic-dispatch route).
        // §3.7.2 Accept/Content-Type negotiation applied.
        String ctHeader = request.firstHeader("Content-Type");
        MediaType contentType = MediaTypes.parse(ctHeader);
        java.util.List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        boolean checkConsumes = host.hasRequestBody(request) || ctHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(contentType, consumes)) {
            return host.renderWebAppException(new jakarta.ws.rs.NotSupportedException(), route, null, null);
        }
        java.util.List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
        java.util.List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(accepts, produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return host.renderWebAppException(new jakarta.ws.rs.NotAcceptableException(), route, null, null);
        }
        MediaType chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);

        // Resolve args
        ParamExtractor.ResolvedArgs resolved;
        Object[] args;
        try {
            resolved = ParamExtractor.resolve(route, match, request);
            args = resolved.args();
            if (resolved.bodyIndex() >= 0) {
                Parameter p = route.javaMethod().getParameters()[resolved.bodyIndex()];
                MediaType readMt = (ctHeader == null) ? MediaType.APPLICATION_OCTET_STREAM_TYPE : contentType;
                args[resolved.bodyIndex()] = host.readEntity(p, readMt, request, route);
            }
        } catch (WebApplicationException wae) {
            return host.renderWebAppException(wae, route, chosen, null);
        }

        // §3.4.1 : sub-resource via dynamic locator → pas d'injection @*Param
        host.injectFields(instance.getClass(), instance, match, request, false);
        Object result;
        // P1b: use the bean class of the route (not the dynamic instance class) for adapter lookup
        Class<?> beanClassForLookup = route.beanClass();
        var adapterFinal = AdapterRegistry.lookup(beanClassForLookup);
        int midFinal = adapterFinal.isPresent()
                ? AdapterRegistry.methodId(beanClassForLookup, route.javaMethod()) : -1;
        if (adapterFinal.isPresent() && midFinal >= 0) {
            try {
                result = adapterFinal.get().invoke(midFinal, instance, args);
            } catch (Throwable t) {
                return host.handleResourceThrowable(t, route, chosen, null);
            }
        } else {
            try {
                route.javaMethod().setAccessible(true);
                result = route.javaMethod().invoke(instance, args);
            } catch (InvocationTargetException ite) {
                return host.handleResourceThrowable(ite.getCause(), route, chosen, null);
            }
        }
        if (result instanceof java.util.concurrent.CompletionStage<?> cs) {
            // TODO(M2h): propagate the non-blocking stage all the way to the transport
            // (cf. CassiniHttpAdapter.dispatch returns CompletionStage<Void>).
            // This try/get block is isolated via Async.awaitBlocking in all
            // other occurrences; we keep it inline here because the WAE branch
            // must be handled locally (renderWebAppException).
            try { result = cs.toCompletableFuture().get(); }
            catch (java.util.concurrent.ExecutionException ee) {
                Throwable cause = ee.getCause();
                if (cause instanceof WebApplicationException wae) {
                    return host.renderWebAppException(wae, route, chosen, null);
                }
                if (cause instanceof Exception ex) throw ex;
                throw new RuntimeException(cause);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted", ie);
            }
        }
        return host.marshal(result, route, chosen);
    }

    /** Scans {@code cls} as a resource class (implicit @Path("") added if
     *  missing) to produce local routes. Used in dynamic dispatch when the class
     *  comes from a runtime sub-resource locator, without a root @Path. */
    private static java.util.List<ResourceMethod> scanInstanceClass(Class<?> cls) {
        // ResourceScanner.discover requires @Path on the class; for sub-resource
        // classes without @Path, simulate it via scanLocatorType from basePath="/".
        // But scanLocatorType is private — use the standard workaround:
        // discover() works if the class has @Path. If it does not, we
        // use light reflection to build routes.
        Path p = cls.getAnnotation(Path.class);
        if (p != null) {
            // The class is itself @Path → scan normally and
            // strip the prefix corresponding to the class (routing is done
            // on remaining which does not have the root @Path).
            return ResourceScanner.discover(cls);
        }
        return scanSubResourceClass(cls);
    }

    /** Scans a sub-resource class (without a root @Path) by producing
     *  ResourceMethods whose template is only @Path(method) (resource methods + sub
     *  locators). No recursion for locators returning Object —
     *  they emit dynamic routes in turn. */
    private static java.util.List<ResourceMethod> scanSubResourceClass(Class<?> cls) {
        java.util.List<ResourceMethod> out = new java.util.ArrayList<>();
        for (java.lang.reflect.Method m : cls.getMethods()) {
            if (!java.lang.reflect.Modifier.isPublic(m.getModifiers())) continue;
            if (m.getDeclaringClass() == Object.class) continue;
            String verb = resolveHttpMethodOf(m);
            Path subPath = m.getAnnotation(Path.class);
            String path = subPath == null ? "/" : normalizeFwd(subPath.value());
            java.util.Set<String> mp = setOf(m.getAnnotation(Produces.class));
            java.util.Set<String> mc = setOf(m.getAnnotation(Consumes.class));
            if (verb != null) {
                m.setAccessible(true);
                out.add(new ResourceMethod(cls, m, verb,
                        UriTemplate.compile(path), mp, mc));
                continue;
            }
            if (subPath == null) continue;
            // Sub-resource locator inside a sub-resource class:
            // emits a dynamic route that re-injects runtime dispatch.
            Class<?> ret = m.getReturnType();
            if (ret == void.class || ret == null) continue;
            m.setAccessible(true);
            // chain contains only this method; the current dispatcher
            // will invoke it on the already-resolved instance (not through root).
            java.util.List<java.lang.reflect.Method> chain = java.util.List.of(m);
            out.add(new ResourceMethod(Object.class, m, "*",
                    UriTemplate.compile(path),
                    mp, mc, cls, chain, 0, true));
            String wildcardPath = path.equals("/") ? "/{__rest:.*}" : path + "/{__rest:.*}";
            out.add(new ResourceMethod(Object.class, m, "*",
                    UriTemplate.compile(wildcardPath),
                    mp, mc, cls, chain, 0, true));
        }
        return out;
    }

    private static String resolveHttpMethodOf(java.lang.reflect.Method m) {
        for (java.lang.annotation.Annotation a : m.getAnnotations()) {
            jakarta.ws.rs.HttpMethod meta = a.annotationType().getAnnotation(jakarta.ws.rs.HttpMethod.class);
            if (meta != null) return meta.value();
        }
        return null;
    }

    private static java.util.Set<String> setOf(java.lang.annotation.Annotation ann) {
        if (ann instanceof Produces p) return new java.util.LinkedHashSet<>(java.util.List.of(p.value()));
        if (ann instanceof Consumes c) return new java.util.LinkedHashSet<>(java.util.List.of(c.value()));
        return java.util.Set.of();
    }

    private static String normalizeFwd(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
