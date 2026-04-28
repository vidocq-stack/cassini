package io.vidocq.cassini.internal.filter;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
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
 * Registre des {@link ContainerRequestFilter} / {@link ContainerResponseFilter}
 * découverts par scan CDI. Expose les listes déjà triées par priorité.
 *
 * <p>§6.2 : les request filters s'exécutent dans l'ordre de priorité croissant ;
 * les response filters dans l'ordre décroissant.</p>
 */
public final class FilterRegistry {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private final List<FilterEntry<ContainerRequestFilter>> requestFilters = new ArrayList<>();
    private final List<FilterEntry<ContainerResponseFilter>> responseFilters = new ArrayList<>();
    private final List<FilterEntry<ReaderInterceptor>> readerInterceptors = new ArrayList<>();
    private final List<FilterEntry<WriterInterceptor>> writerInterceptors = new ArrayList<>();
    private final List<ContextResolver<?>> contextResolvers = new ArrayList<>();
    private final List<ParamConverterProvider> paramConverterProviders = new ArrayList<>();
    private final List<jakarta.ws.rs.container.DynamicFeature> dynamicFeatures = new ArrayList<>();

    public void addDynamicFeature(jakarta.ws.rs.container.DynamicFeature df) { dynamicFeatures.add(df); }
    public List<jakarta.ws.rs.container.DynamicFeature> dynamicFeatures() { return dynamicFeatures; }

    /** §6.5.5 : enregistre une instance comme filter/interceptor lié à
     *  une méthode resource précise (binding dynamique). */
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

    /** Enregistre un filtre d'après son type, sans distinction request/response.
     *  Si l'instance implémente les deux, elle est ajoutée aux deux listes. */
    public void register(Object instance) {
        if (instance instanceof ContainerRequestFilter r) addRequest(r);
        if (instance instanceof ContainerResponseFilter r) addResponse(r);
        if (instance instanceof ReaderInterceptor r) addReaderInterceptor(r);
        if (instance instanceof WriterInterceptor r) addWriterInterceptor(r);
        if (instance instanceof ContextResolver<?> r) addContextResolver(r);
        if (instance instanceof ParamConverterProvider p) addParamConverterProvider(p);
        if (instance instanceof jakarta.ws.rs.container.DynamicFeature df) addDynamicFeature(df);
    }

    /** §6.5.5 : exécute toutes les DynamicFeatures pour chaque resource method
     *  donnée. Les filtres/interceptors enregistrés via featureContext.register
     *  seront liés à cette méthode (binding dynamique). */
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
        // §4.1.4 : tri par @Priority croissant (priorité haute = valeur basse).
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

    public static FilterRegistry discover(BeanManager bm) {
        FilterRegistry reg = new FilterRegistry();
        if (bm == null) return reg;
        Map<Class<?>, Boolean> seen = new HashMap<>();
        for (Bean<?> bean : bm.getBeans(Object.class, ANY)) {
            Class<?> cls = bean.getBeanClass();
            if (seen.putIfAbsent(cls, Boolean.TRUE) != null) continue;
            if (cls.getAnnotation(Provider.class) == null) continue;
            Object instance = null;
            if (ContainerRequestFilter.class.isAssignableFrom(cls)) {
                instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addRequest((ContainerRequestFilter) instance);
            }
            if (ContainerResponseFilter.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addResponse((ContainerResponseFilter) instance);
            }
            if (ReaderInterceptor.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addReaderInterceptor((ReaderInterceptor) instance);
            }
            if (WriterInterceptor.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addWriterInterceptor((WriterInterceptor) instance);
            }
            if (ContextResolver.class.isAssignableFrom(cls)) {
                if (instance == null) instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
                reg.addContextResolver((ContextResolver<?>) instance);
            }
        }
        return reg;
    }
}
