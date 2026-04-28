package io.vidocq.cassini.internal;

import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.enterprise.inject.spi.BeanManager;
import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Registre des {@link ExceptionMapper} découverts parmi les beans CDI
 * annotés {@code @Provider}.
 *
 * <p>Sélection du mapper (§4.4) : on préfère le mapper dont le type
 * paramétré d'exception est le plus spécifique (plus proche de la
 * classe concrète) parmi ceux qui matchent.</p>
 */
public final class ExceptionMapperRegistry {

    private static final AnnotationLiteral<Any> ANY = new AnnotationLiteral<Any>() {};

    private final List<Registration<?>> mappers = new ArrayList<>();

    public record Registration<T extends Throwable>(Class<T> exceptionType, ExceptionMapper<T> mapper) {}

    public <T extends Throwable> void register(Class<T> exceptionType, ExceptionMapper<T> mapper) {
        mappers.add(new Registration<>(exceptionType, mapper));
    }

    /** Scanne le BeanManager à la recherche de beans {@code @Provider} implémentant ExceptionMapper. */
    public static ExceptionMapperRegistry discover(BeanManager bm) {
        ExceptionMapperRegistry reg = new ExceptionMapperRegistry();
        if (bm == null) return reg;
        Map<Class<?>, Boolean> seen = new HashMap<>();
        for (Bean<?> bean : bm.getBeans(Object.class, ANY)) {
            Class<?> cls = bean.getBeanClass();
            if (seen.putIfAbsent(cls, Boolean.TRUE) != null) continue;
            if (cls.getAnnotation(Provider.class) == null) continue;
            if (!ExceptionMapper.class.isAssignableFrom(cls)) continue;
            Class<? extends Throwable> excType = resolveExceptionType(cls);
            if (excType == null) continue;
            Object instance = bm.getReference(bean, cls, bm.createCreationalContext(bean));
            @SuppressWarnings({"unchecked", "rawtypes"})
            Registration<? extends Throwable> r = new Registration(excType, (ExceptionMapper) instance);
            reg.mappers.add(r);
        }
        return reg;
    }

    /** §4.4 : si un ExceptionMapper lève lui-même une exception pendant
     *  sa propre exécution, celle-ci ne doit pas être mappée à nouveau —
     *  elle doit remonter en 500. Ce flag per-thread empêche la récursion. */
    private static final ThreadLocal<Boolean> MAPPING = ThreadLocal.withInitial(() -> false);

    public Optional<Response> map(Throwable t) {
        if (MAPPING.get()) return Optional.empty();
        Registration<?> best = null;
        for (Registration<?> r : mappers) {
            if (r.exceptionType().isInstance(t)) {
                if (best == null) {
                    best = r;
                } else if (best.exceptionType() == r.exceptionType()) {
                    // §4.4 / §4.1.4 : même type d'exception → priority basse gagne.
                    if (priorityOf(r.mapper()) < priorityOf(best.mapper())) best = r;
                } else if (best.exceptionType().isAssignableFrom(r.exceptionType())) {
                    best = r;
                }
            }
        }
        if (best == null) return Optional.empty();
        MAPPING.set(true);
        try {
            @SuppressWarnings({"rawtypes", "unchecked"})
            Response r = ((ExceptionMapper) best.mapper()).toResponse(t);
            return Optional.ofNullable(r);
        } finally {
            MAPPING.set(false);
        }
    }

    /** §10.2 : retourne le mapper le plus spécifique pour {@code type} sans l'exécuter. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public <T extends Throwable> ExceptionMapper<T> findMapper(Class<T> type) {
        Registration<?> best = null;
        for (Registration<?> r : mappers) {
            if (r.exceptionType().isAssignableFrom(type)) {
                if (best == null || best.exceptionType().isAssignableFrom(r.exceptionType())) {
                    best = r;
                }
            }
        }
        return best == null ? null : (ExceptionMapper<T>) best.mapper();
    }

    public int size() { return mappers.size(); }

    private static int priorityOf(Object o) {
        jakarta.annotation.Priority p = o.getClass().getAnnotation(jakarta.annotation.Priority.class);
        return p == null ? jakarta.ws.rs.Priorities.USER : p.value();
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Throwable> resolveExceptionType(Class<?> mapperClass) {
        for (Type iface : mapperClass.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt
                    && pt.getRawType() == ExceptionMapper.class
                    && pt.getActualTypeArguments().length == 1
                    && pt.getActualTypeArguments()[0] instanceof Class<?> c
                    && Throwable.class.isAssignableFrom(c)) {
                return (Class<? extends Throwable>) c;
            }
        }
        Class<?> sup = mapperClass.getSuperclass();
        return sup == null || sup == Object.class ? null : resolveExceptionType(sup);
    }
}
