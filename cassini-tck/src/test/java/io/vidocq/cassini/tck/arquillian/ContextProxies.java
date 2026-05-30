package io.vidocq.cassini.tck.arquillian;

import io.vidocq.cassini.internal.Invoker;
import io.vidocq.cassini.internal.MatchResult;
import io.vidocq.cassini.internal.ParamExtractor;
import io.vidocq.cassini.internal.context.CassiniHttpHeaders;
import io.vidocq.cassini.internal.context.CassiniRequest;
import io.vidocq.cassini.internal.context.CassiniSecurityContext;
import io.vidocq.cassini.internal.context.CassiniUriInfo;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * §4.5 / §9.2: dynamic proxies for @Context constructor parameters of a
 * singleton provider. The provider is instantiated at deploy time but its
 * methods (writeTo, getContext, toResponse...) are only called at request
 * time. Each proxy therefore delegates to the current request (ThreadLocal
 * Invoker.CURRENT_REQUEST + CURRENT_MATCH) to resolve the effective value
 * at invocation time.
 */
final class ContextProxies {

    private ContextProxies() {}

    static Object proxy(Class<?> type) {
        if (!type.isInterface()) {
            // Application (concrete class): return an empty instance
            // — getProperties()/getClasses() return defaults.
            if (type == jakarta.ws.rs.core.Application.class) {
                return new jakarta.ws.rs.core.Application();
            }
            return null;
        }
        return Proxy.newProxyInstance(type.getClassLoader(),
                new Class<?>[]{type}, new ContextHandler(type));
    }

    private static final class ContextHandler implements InvocationHandler {
        private final Class<?> type;
        ContextHandler(Class<?> type) { this.type = type; }

        @Override public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object target = resolve(type);
            if (target == null) {
                if (method.getReturnType() == boolean.class) return false;
                if (method.getReturnType().isPrimitive()) return 0;
                return null;
            }
            return method.invoke(target, args);
        }
    }

    private static Object resolve(Class<?> type) {
        var request = Invoker.CURRENT_REQUEST.get();
        if (request == null) return null;
        if (type == jakarta.ws.rs.core.HttpHeaders.class) return new CassiniHttpHeaders(request);
        if (type == jakarta.ws.rs.core.Request.class) return new CassiniRequest(request);
        if (type == jakarta.ws.rs.core.SecurityContext.class) return new CassiniSecurityContext(request);
        if (type == jakarta.ws.rs.ext.Providers.class) return ParamExtractor.currentProviders();
        MatchResult match = Invoker.CURRENT_MATCH.get();
        if (type == jakarta.ws.rs.core.UriInfo.class) {
            return new CassiniUriInfo(request, request.contextPath(),
                    match == null ? java.util.Map.of() : match.pathParams());
        }
        if (type == jakarta.ws.rs.container.ResourceInfo.class) {
            if (match == null) return null;
            var route = match.method();
            return new jakarta.ws.rs.container.ResourceInfo() {
                @Override public java.lang.reflect.Method getResourceMethod() { return route.javaMethod(); }
                @Override public Class<?> getResourceClass() { return route.beanClass(); }
            };
        }
        return null;
    }
}
