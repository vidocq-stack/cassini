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

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * §3.4.1 dynamic sub-resource dispatch, extracted from {@link Invoker} (which
 * stays the facade and delegates): executes locator chains whose return type
 * is {@code Object}, scans the effective runtime class of each returned
 * instance, routes the remaining path on a temporary mini-router, and invokes
 * the final method on the already-resolved instance — recursing when a
 * sub-route is itself a dynamic locator.
 */
final class DynamicLocatorDispatch {

    /** The capture a dynamic locator route ends with: what is left of the path. */
    private static final String REST = "__rest";
    private static final String CONTENT_TYPE = "Content-Type";

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
        } catch (RuntimeException _) {
            return host.renderWebAppException(
                    new WebApplicationException("Cannot resolve root " + route.rootBeanClass().getName(), 500),
                    route, MediaType.WILDCARD_TYPE, null);
        }
        host.injectResourceFields(route.rootBeanClass(), root, match, request);
        List<Object> matched = new ArrayList<>();
        matched.add(root);
        return walkThenDispatch(match, request, root, matched);
    }

    /** Walks the locator chain of the route from {@code start}, then routes what is left of the
     *  path ({@code {__rest:.*}}) on the last object. */
    private CassiniHttpResponse walkThenDispatch(MatchResult match, CassiniHttpExchange request, Object start,
                                                 List<Object> matched) throws Exception {
        ResourceMethod route = match.method();
        Invoker.publishCurrent(match, request);
        request.setAttribute(Invoker.ATTR_MATCHED_RESOURCES, matched);
        Object last;
        try {
            last = walkLocators(match, request, start, matched);
        } catch (InvocationTargetException ite) {
            if (ite.getCause() instanceof WebApplicationException wae) {
                return host.renderWebAppException(wae, route, MediaType.WILDCARD_TYPE, null);
            }
            throw asException(ite.getCause());
        }
        if (last instanceof CassiniHttpResponse ended) return ended;
        return dispatchOnInstance(last, remainingPath(match), request, matched);
    }

    /** Invokes each locator of the chain on the object the previous one returned, injecting the
     *  fields of each; returns the last object, or the response that ends the request. */
    private Object walkLocators(MatchResult match, CassiniHttpExchange request, Object start, List<Object> matched)
            throws IOException, IllegalAccessException, InvocationTargetException {
        ResourceMethod route = match.method();
        Object intermediate = start;
        for (Method locStep : route.locatorChain()) {
            Object[] lArgs = ParamExtractor.resolveConstructorArgs(locStep.getParameters(), match, request);
            intermediate = accessible(locStep).invoke(intermediate, lArgs);
            if (intermediate == null) {
                return host.renderWebAppException(
                        new WebApplicationException("Sub-resource locator returned null", 404),
                        route, MediaType.WILDCARD_TYPE, null);
            }
            if (intermediate instanceof Class<?> cls) {
                // M6a: prefer generated adapter newInstance() over reflection
                Object created = host.instantiateSubResourceClass(cls, route, MediaType.WILDCARD_TYPE, null);
                if (created instanceof CassiniHttpResponse failed) return failed;
                intermediate = created;
            }
            host.injectFields(intermediate.getClass(), intermediate, match, request, false);
            matched.add(0, intermediate);
        }
        return intermediate;
    }

    /** The part of the path a dynamic locator leaves to the object it returns: the
     *  {@code {__rest:.*}} capture, as an absolute path. */
    private static String remainingPath(MatchResult match) {
        List<String> values = match.pathParams().get(REST);
        String rest = values == null || values.isEmpty() || values.get(0) == null ? "" : values.get(0);
        return rest.isEmpty() ? "/" : "/" + rest;
    }

    /** The failure to throw for {@code cause}: itself when it is an Exception, wrapped otherwise. */
    private static Exception asException(Throwable cause) {
        return cause instanceof Exception ex ? ex : new IllegalStateException(cause);
    }

    /** §3.4.1: methods of resource classes the application did not open to reflection beyond
     *  their public members; a locator or a sub-resource method of a non-public class still runs. */
    private static Method accessible(Method m) {
        m.setAccessible(true); // NOSONAR java:S3011 - resource methods of non-public resource classes (§3.4.1)
        return m;
    }

    /** §3.4.1: dynamically scans {@code instance.getClass()} and resolves the
     *  best route for {@code remaining}+request HTTP method.
     *  Reuses {@code Invoker.invokeInternal} while passing the already-created instance
     *  via an ad-hoc resolver to avoid re-instantiation. */
    private CassiniHttpResponse dispatchOnInstance(Object instance, String remaining, CassiniHttpExchange request,
                                                   List<Object> matchedSoFar) throws Exception {
        Class<?> cls = instance.getClass();
        // §3.6: if the runtime class has no root @Path, simulate it by
        // adding @Path("") through a scan wrapper. ResourceScanner.discover
        // requires @Path on the class — work around that by scanning locators
        // from a fake locator chain.
        List<ResourceMethod> subRoutes = scanInstanceClass(cls);
        if (subRoutes.isEmpty()) {
            return host.renderWebAppException(new jakarta.ws.rs.NotFoundException(),
                    null, MediaType.WILDCARD_TYPE, null);
        }
        UriRouter subRouter = new UriRouter(subRoutes);
        String httpMethod = request.method() == null ? "GET" : request.method();
        List<MatchResult> subCandidates = subRouter.matchAll(httpMethod, remaining);
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
        // Recurse if the sub-route is itself a dynamic locator
        if (subMatch.method().dynamicLocator()) {
            // Move up one level: invoke the sub-chain on the current instance. Scanning
            // restarted from its class, so the whole sub-chain runs.
            return walkThenDispatch(subMatch, request, instance, matchedSoFar);
        }
        // Invoke the final method on the current instance through an ad-hoc resolver
        return invokeFinalOnInstance(subMatch, request, instance);
    }

    /** Invokes the final method of a sub-route using {@code instance}
     *  as the target (instead of re-instantiating via {@code resolver}): a subset of the flow of
     *  {@code Invoker}, without filters for this synthetic dynamic-dispatch route, with the
     *  §3.7.2 Accept/Content-Type negotiation. */
    private CassiniHttpResponse invokeFinalOnInstance(MatchResult match, CassiniHttpExchange request,
                                                      Object instance) throws Exception {
        ResourceMethod route = match.method();
        String ctHeader = request.firstHeader(CONTENT_TYPE);
        MediaType contentType = MediaTypes.parse(ctHeader);
        List<MediaType> consumes = MediaTypes.fromSet(route.consumes());
        boolean checkConsumes = host.hasRequestBody(request) || ctHeader != null;
        if (checkConsumes && !consumes.isEmpty() && !MediaTypes.consumesMatches(contentType, consumes)) {
            return host.renderWebAppException(new jakarta.ws.rs.NotSupportedException(), route, null, null);
        }
        List<MediaType> produces = MediaTypes.fromSet(route.produces());
        Optional<MediaType> negotiated = MediaTypes.pickProduced(
                MediaTypes.parseList(request.firstHeader("Accept")), produces);
        if (negotiated.isEmpty() && !produces.isEmpty()) {
            return host.renderWebAppException(new jakarta.ws.rs.NotAcceptableException(), route, null, null);
        }
        MediaType chosen = negotiated.orElse(MediaType.WILDCARD_TYPE);

        Object[] args;
        try {
            args = resolveArguments(match, request, ctHeader == null
                    ? MediaType.APPLICATION_OCTET_STREAM_TYPE : contentType);
        } catch (WebApplicationException wae) {
            return host.renderWebAppException(wae, route, chosen, null);
        } catch (EntityReadException ere) {
            // cassini#39: same rules as the static route (Invoker.invokeInternal).
            return host.renderEntityReadFailure(ere.getCause(), route, chosen, null, request);
        }

        // §3.4.1: a sub-resource reached through a dynamic locator gets no @*Param injection.
        host.injectFields(instance.getClass(), instance, match, request, false);
        Object result = invokeOn(instance, route, args, chosen);
        if (result instanceof CassiniHttpResponse ended) return ended;
        if (result instanceof CompletionStage<?> stage) {
            Object awaited = await(stage, route, chosen);
            if (awaited instanceof CassiniHttpResponse ended) return ended;
            if (awaited instanceof Failed(Exception cause)) throw cause;
            result = awaited;
        }
        return host.marshal(result, route, chosen);
    }

    /** The arguments of the method, its entity read as {@code readMt}. */
    private Object[] resolveArguments(MatchResult match, CassiniHttpExchange request, MediaType readMt)
            throws IOException {
        ResourceMethod route = match.method();
        ParamExtractor.ResolvedArgs resolved = ParamExtractor.resolve(route, match, request);
        Object[] args = resolved.args();
        int body = resolved.bodyIndex();
        if (body >= 0) {
            args[body] = host.readEntity(MethodParameters.of(route.javaMethod()).get(body), readMt, request, route);
        }
        return args;
    }

    /** Runs the method on {@code instance}: through the generated adapter of the route's bean
     *  class when there is one (P1b), by reflection otherwise. Returns its result, or the response
     *  its failure was rendered or mapped to. */
    private Object invokeOn(Object instance, ResourceMethod route, Object[] args, MediaType chosen) throws Exception {
        // P1b: use the bean class of the route (not the dynamic instance class) for adapter lookup
        Class<?> beanClass = route.beanClass();
        var adapter = AdapterRegistry.lookup(beanClass);
        int mid = adapter.isPresent() ? AdapterRegistry.methodId(beanClass, route.javaMethod()) : -1;
        if (adapter.isPresent() && mid >= 0) {
            try {
                return adapter.get().invoke(mid, instance, args);
            } catch (Throwable t) {
                return host.handleResourceThrowable(t, route, chosen, null);
            }
        }
        try {
            return accessible(route.javaMethod()).invoke(instance, args);
        } catch (InvocationTargetException ite) {
            return host.handleResourceThrowable(ite.getCause(), route, chosen, null);
        }
    }

    /** The failure of an awaited CompletionStage, for the caller to throw as it is. */
    private record Failed(Exception cause) {}

    /** The value of a CompletionStage the method returned: the virtual thread waits for it (M2h),
     *  Cassini dispatches synchronously. A WebApplicationException is rendered; another failure
     *  comes back as {@link Failed}. */
    private Object await(CompletionStage<?> stage, ResourceMethod route, MediaType chosen) throws IOException {
        try {
            return stage.toCompletableFuture().get();
        } catch (ExecutionException ee) {
            if (ee.getCause() instanceof WebApplicationException wae) {
                return host.renderWebAppException(wae, route, chosen, null);
            }
            return new Failed(asException(ee.getCause()));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted", ie);
        }
    }

    /** Scans {@code cls} as a resource class (implicit @Path("") added if
     *  missing) to produce local routes. Used in dynamic dispatch when the class
     *  comes from a runtime sub-resource locator, without a root @Path. */
    private static List<ResourceMethod> scanInstanceClass(Class<?> cls) {
        // A class that is itself @Path is scanned normally (routing is done on the remaining
        // path, which does not carry the root @Path); otherwise its methods are read directly.
        if (cls.getAnnotation(Path.class) != null) return ResourceScanner.discover(cls);
        return scanSubResourceClass(cls);
    }

    /** Scans a sub-resource class (without a root @Path) by producing
     *  ResourceMethods whose template is only @Path(method) (resource methods + sub
     *  locators). No recursion for locators returning Object —
     *  they emit dynamic routes in turn. */
    private static List<ResourceMethod> scanSubResourceClass(Class<?> cls) {
        List<ResourceMethod> out = new ArrayList<>();
        for (Method m : cls.getMethods()) {
            if (Modifier.isPublic(m.getModifiers()) && m.getDeclaringClass() != Object.class) {
                addRoutes(cls, m, out);
            }
        }
        return out;
    }

    /** The routes of one public method of a sub-resource class: a resource method, or a
     *  sub-resource locator that re-enters the dynamic dispatch at run time. */
    private static void addRoutes(Class<?> cls, Method m, List<ResourceMethod> out) {
        String verb = resolveHttpMethodOf(m);
        Path subPath = m.getAnnotation(Path.class);
        String path = subPath == null ? "/" : normalizeFwd(subPath.value());
        Set<String> mp = setOf(m.getAnnotation(Produces.class));
        Set<String> mc = setOf(m.getAnnotation(Consumes.class));
        if (verb != null) {
            out.add(new ResourceMethod(cls, accessible(m), verb, UriTemplate.compile(path), mp, mc));
            return;
        }
        if (subPath == null || m.getReturnType() == void.class) return;
        // The chain holds this method only: the dispatcher invokes it on the instance
        // already resolved, not through the root.
        List<Method> chain = List.of(accessible(m));
        out.add(new ResourceMethod(Object.class, m, "*", UriTemplate.compile(path), mp, mc, cls, chain, 0, true));
        String wildcardPath = path.equals("/") ? "/{__rest:.*}" : path + "/{__rest:.*}";
        out.add(new ResourceMethod(Object.class, m, "*", UriTemplate.compile(wildcardPath),
                mp, mc, cls, chain, 0, true));
    }

    private static String resolveHttpMethodOf(Method m) {
        for (java.lang.annotation.Annotation a : m.getAnnotations()) {
            jakarta.ws.rs.HttpMethod meta = a.annotationType().getAnnotation(jakarta.ws.rs.HttpMethod.class);
            if (meta != null) return meta.value();
        }
        return null;
    }

    private static Set<String> setOf(java.lang.annotation.Annotation ann) {
        if (ann instanceof Produces p) return new java.util.LinkedHashSet<>(List.of(p.value()));
        if (ann instanceof Consumes c) return new java.util.LinkedHashSet<>(List.of(c.value()));
        return Set.of();
    }

    private static String normalizeFwd(String raw) {
        if (raw == null || raw.isEmpty() || "/".equals(raw)) return "/";
        String s = raw.startsWith("/") ? raw : "/" + raw;
        if (s.length() > 1 && s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}
