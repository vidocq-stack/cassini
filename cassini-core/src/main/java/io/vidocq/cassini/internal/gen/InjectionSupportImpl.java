package io.vidocq.cassini.internal.gen;

import io.vidocq.cassini.internal.FieldInjector;
import io.vidocq.cassini.internal.MatchResult;
import io.vidocq.cassini.internal.ParamValueConverter;
import io.vidocq.cassini.spi.gen.InjectionSupport;
import io.vidocq.cassini.spi.gen.ParamKind;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import jakarta.ws.rs.WebApplicationException;

import java.util.List;
import java.util.Map;

/**
 * Per-request implementation of {@link InjectionSupport}.
 *
 * <p>Wraps a {@code (MatchResult, CassiniHttpExchange)} pair and delegates to
 * the existing cassini-core helpers to keep a single source of truth:</p>
 * <ul>
 *   <li>{@code context(...)} delegates to {@link FieldInjector#resolveContext} (package-visible).</li>
 *   <li>{@code param(...)} uses the same source helpers as {@code FieldInjector} /
 *       {@code ParamExtractor} (query, cookie, matrix, form caches from exchange attributes).</li>
 *   <li>{@code beanParam(...)} mirrors {@code ParamExtractor.instantiateBeanParam}.</li>
 * </ul>
 */
public final class InjectionSupportImpl implements InjectionSupport {

    private final MatchResult match;
    private final CassiniHttpExchange request;

    public InjectionSupportImpl(MatchResult match, CassiniHttpExchange request) {
        this.match = match;
        this.request = request;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> T context(Class<T> type) {
        return (T) FieldInjector.resolveContext(type, match, request);
    }

    @Override
    public Object param(ParamKind kind, String name, boolean encoded,
                        String defaultValue, Class<?> rawType, Class<?> elementType) {
        List<String> values = sourceValues(kind, name, encoded);
        List<String> effective = values.isEmpty()
                ? (defaultValue != null ? List.of(defaultValue) : List.of())
                : values;
        if (effective.isEmpty()) {
            return ParamValueConverter.defaultForType(rawType);
        }
        try {
            return ParamValueConverter.coerce(rawType, elementType, effective);
        } catch (WebApplicationException wae) {
            // §3.2: @PathParam/@QueryParam/@MatrixParam conversion failure → 404
            boolean notFound = kind == ParamKind.PATH || kind == ParamKind.QUERY || kind == ParamKind.MATRIX;
            if (notFound && wae.getResponse() != null && wae.getResponse().getStatus() == 400) {
                throw new WebApplicationException(wae.getMessage(), wae.getCause(),
                        jakarta.ws.rs.core.Response.status(404).build());
            }
            throw wae;
        } catch (RuntimeException e) {
            boolean notFound = kind == ParamKind.PATH || kind == ParamKind.QUERY || kind == ParamKind.MATRIX;
            throw new WebApplicationException("Invalid value for param "
                    + name + ": " + e.getMessage(), e,
                    jakarta.ws.rs.core.Response.status(notFound ? 404 : 400).build());
        }
    }

    private List<String> sourceValues(ParamKind kind, String name, boolean encoded) {
        return switch (kind) {
            case PATH -> {
                List<String> raws = match.pathParams().getOrDefault(name, List.of());
                if (raws.isEmpty()) yield List.of();
                yield encoded ? raws : raws.stream().map(InjectionSupportImpl::decodePath).toList();
            }
            case QUERY -> {
                Map<String, List<String>> q = FieldInjector.parsedQueryParams(request, encoded);
                yield q.getOrDefault(name, List.of());
            }
            case HEADER -> request.headers(name);
            case COOKIE -> {
                String v = FieldInjector.cookie(request, name);
                yield v == null ? List.of() : List.of(v);
            }
            case MATRIX -> FieldInjector.matrix(request, name, encoded);
            case FORM -> {
                Map<String, List<String>> form = FieldInjector.readForm(request, encoded);
                yield form.getOrDefault(name, List.of());
            }
        };
    }

    @Override
    public Object beanParam(Class<?> type) {
        try {
            Object instance = type.getDeclaredConstructor().newInstance();
            FieldInjector.inject(instance, match, request);
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new WebApplicationException("Failed to instantiate @BeanParam "
                    + type.getName() + ": " + e.getMessage(), 500);
        }
    }

    @Override
    public Object suspendedAsyncResponse() {
        return request.getAttribute(
                io.vidocq.cassini.internal.CassiniAsyncResponseImpl.ATTR_KEY);
    }

    private static String decodePath(String s) {
        if (s == null || s.indexOf('%') < 0) return s;
        try {
            return java.net.URLDecoder.decode(s.replace("+", "%2B"), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }
}
