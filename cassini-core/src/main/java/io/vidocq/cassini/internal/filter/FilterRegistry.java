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
package io.vidocq.cassini.internal.filter;


import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.ext.ParamConverterProvider;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.ReaderInterceptor;
import jakarta.ws.rs.ext.WriterInterceptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of {@link ContainerRequestFilter} / {@link ContainerResponseFilter}
 * registered manually or via scan. Exposes lists sorted by priority.
 *
 * <p>§6.2: request filters execute in ascending priority order;
 * response filters in descending order.</p>
 */
public final class FilterRegistry {

    private final List<FilterEntry<ContainerRequestFilter>> requestFilters = new ArrayList<>();
    private final List<FilterEntry<ContainerResponseFilter>> responseFilters = new ArrayList<>();
    private final List<FilterEntry<ReaderInterceptor>> readerInterceptors = new ArrayList<>();
    private final List<FilterEntry<WriterInterceptor>> writerInterceptors = new ArrayList<>();
    private final List<ContextResolver<?>> contextResolvers = new ArrayList<>();
    private final List<ParamConverterProvider> paramConverterProviders = new ArrayList<>();
    private final List<jakarta.ws.rs.container.DynamicFeature> dynamicFeatures = new ArrayList<>();

    public void addDynamicFeature(jakarta.ws.rs.container.DynamicFeature df) { dynamicFeatures.add(df); }
    public List<jakarta.ws.rs.container.DynamicFeature> dynamicFeatures() { return dynamicFeatures; }

    /** §6.5.5: registers an instance as a filter/interceptor bound to
     *  a specific resource method (dynamic binding). */
    public void registerDynamic(Object instance, java.lang.reflect.Method target) {
        if (instance instanceof ContainerRequestFilter r) {
            requestFilters.add(FilterEntry.dynamicFor(r, target));
            requestFilters.sort(Comparator.comparingInt(FilterEntry::priority));
        }
        if (instance instanceof ContainerResponseFilter r) {
            responseFilters.add(FilterEntry.dynamicFor(r, target));
            responseFilters.sort(Comparator.comparingInt(FilterEntry<ContainerResponseFilter>::priority).reversed());
        }
        if (instance instanceof ReaderInterceptor r) {
            readerInterceptors.add(FilterEntry.dynamicFor(r, target));
            readerInterceptors.sort(Comparator.comparingInt(FilterEntry::priority));
        }
        if (instance instanceof WriterInterceptor r) {
            writerInterceptors.add(FilterEntry.dynamicFor(r, target));
            writerInterceptors.sort(Comparator.comparingInt(FilterEntry::priority));
        }
    }

    public void addRequest(ContainerRequestFilter filter) {
        requestFilters.add(FilterEntry.of(filter));
        requestFilters.sort(Comparator.comparingInt(FilterEntry::priority));
    }

    public void addResponse(ContainerResponseFilter filter) {
        responseFilters.add(FilterEntry.of(filter));
        responseFilters.sort(Comparator.comparingInt(FilterEntry<ContainerResponseFilter>::priority).reversed());
    }

    /** Registers a filter by its type, regardless of request/response.
     *  If the instance implements both, it is added to both lists. */
    public void register(Object instance) {
        if (instance instanceof ContainerRequestFilter r) addRequest(r);
        if (instance instanceof ContainerResponseFilter r) addResponse(r);
        if (instance instanceof ReaderInterceptor r) addReaderInterceptor(r);
        if (instance instanceof WriterInterceptor r) addWriterInterceptor(r);
        if (instance instanceof ContextResolver<?> r) addContextResolver(r);
        if (instance instanceof ParamConverterProvider p) addParamConverterProvider(p);
        if (instance instanceof jakarta.ws.rs.container.DynamicFeature df) addDynamicFeature(df);
    }

    /** §6.5.5: executes all DynamicFeatures for each given resource method.
     *  Filters/interceptors registered via featureContext.register
     *  will be bound to this method (dynamic binding). */
    public void applyDynamicFeatures(java.util.Collection<io.vidocq.cassini.internal.ResourceMethod> routes) {
        if (dynamicFeatures.isEmpty() || routes.isEmpty()) return;
        for (var route : routes) {
            java.lang.reflect.Method m = route.javaMethod();
            Class<?> c = route.beanClass();
            jakarta.ws.rs.container.ResourceInfo ri = new jakarta.ws.rs.container.ResourceInfo() {
                @Override public java.lang.reflect.Method getResourceMethod() { return m; }
                @Override public Class<?> getResourceClass() { return c; }
            };
            for (var df : dynamicFeatures) {
                try { df.configure(ri, new CassiniDynamicFeatureContext(this, m)); }
                catch (RuntimeException ignored) {}
            }
        }
    }

    public void addContextResolver(ContextResolver<?> r) { contextResolvers.add(r); }
    public List<ContextResolver<?>> contextResolvers() { return contextResolvers; }

    public void addParamConverterProvider(ParamConverterProvider p) {
        paramConverterProviders.add(p);
        // §4.1.4 : sort by @Priority ascending (high priority = low value).
        paramConverterProviders.sort(Comparator.comparingInt(FilterRegistry::priorityOf));
    }
    public List<ParamConverterProvider> paramConverterProviders() { return paramConverterProviders; }

    private static int priorityOf(Object instance) {
        jakarta.annotation.Priority p = instance.getClass().getAnnotation(jakarta.annotation.Priority.class);
        return p == null ? jakarta.ws.rs.Priorities.USER : p.value();
    }

    public void addReaderInterceptor(ReaderInterceptor i) {
        readerInterceptors.add(FilterEntry.of(i));
        readerInterceptors.sort(Comparator.comparingInt(FilterEntry::priority));
    }

    public void addWriterInterceptor(WriterInterceptor i) {
        writerInterceptors.add(FilterEntry.of(i));
        writerInterceptors.sort(Comparator.comparingInt(FilterEntry::priority));
    }

    public List<FilterEntry<ReaderInterceptor>> readerInterceptors() { return readerInterceptors; }
    public List<FilterEntry<WriterInterceptor>> writerInterceptors() { return writerInterceptors; }

    /** §6.5.3 : filtre les reader interceptors par @NameBinding sur la méthode/classe cible. */
    public List<FilterEntry<ReaderInterceptor>> readerInterceptorsFor(java.lang.reflect.Method m, Class<?> cls) {
        if (readerInterceptors.isEmpty()) return readerInterceptors;
        return readerInterceptors.stream().filter(e -> e.appliesTo(m, cls)).toList();
    }

    /** §6.5.3 : filtre les writer interceptors par @NameBinding sur la méthode/classe cible. */
    public List<FilterEntry<WriterInterceptor>> writerInterceptorsFor(java.lang.reflect.Method m, Class<?> cls) {
        if (writerInterceptors.isEmpty()) return writerInterceptors;
        return writerInterceptors.stream().filter(e -> e.appliesTo(m, cls)).toList();
    }

    public List<FilterEntry<ContainerRequestFilter>> requestFilters() { return requestFilters; }
    public List<FilterEntry<ContainerResponseFilter>> responseFilters() { return responseFilters; }

    public List<FilterEntry<ContainerRequestFilter>> preMatching() {
        return requestFilters.stream().filter(FilterEntry::preMatching).toList();
    }

    public List<FilterEntry<ContainerRequestFilter>> postMatching() {
        return requestFilters.stream().filter(f -> !f.preMatching()).toList();
    }

}
