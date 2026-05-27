package io.vidocq.cassini.spi.gen;

/**
 * Stable façade exposed to generated resource adapters for resolving
 * {@code @Context} values and JAX-RS parameter values with coercion.
 *
 * <p>Generated adapters (emitted by APT or the runtime Class-File generator)
 * must depend only on this interface — never on {@code cassini-core} internals
 * such as {@code MatchResult}. Implementations are provided by
 * {@code cassini-core} and wired by the {@link AdapterRegistry}.</p>
 *
 * <p>All methods may return {@code null} when no value is available for the
 * given name / type (the adapter is responsible for applying {@code @DefaultValue}
 * semantics if needed — or it can pass the {@code defaultValue} parameter here
 * and let the impl handle it consistently).</p>
 */
public interface InjectionSupport {

    /**
     * Resolves a {@code @Context}-injected value for the given JAX-RS context type.
     * Mirrors {@code FieldInjector.resolveContext} which remains the single source of truth.
     *
     * @param <T>  the context type
     * @param type the context interface class (e.g. {@code UriInfo.class}, {@code SecurityContext.class})
     * @return the context instance, or {@code null} if the type is not supported
     */
    <T> T context(Class<T> type);

    /**
     * Resolves one JAX-RS parameter value with full coercion.
     * Covers {@code @PathParam}, {@code @QueryParam}, {@code @HeaderParam},
     * {@code @CookieParam}, {@code @MatrixParam}, and {@code @FormParam}.
     *
     * @param kind         the annotation kind
     * @param name         the parameter name (annotation value)
     * @param encoded      true if {@code @Encoded} is present on the parameter or class
     * @param defaultValue the {@code @DefaultValue} value, or {@code null} if absent
     * @param rawType      the declared parameter type (e.g. {@code String.class}, {@code List.class})
     * @param elementType  the generic element type for collection types, otherwise same as {@code rawType}
     * @return the coerced value, possibly {@code null}
     */
    Object param(ParamKind kind, String name, boolean encoded,
                 String defaultValue, Class<?> rawType, Class<?> elementType);

    /**
     * Instantiates and fully injects a {@code @BeanParam} instance of the given type.
     * Equivalent to {@code new type() + FieldInjector.inject(instance, ...)}.
     *
     * @param type the bean param class
     * @return the populated instance
     */
    Object beanParam(Class<?> type);

    /**
     * Returns the {@code AsyncResponse} already registered for this request
     * (keyed by {@code CassiniAsyncResponseImpl.ATTR_KEY}), or {@code null}.
     * Used by generated code for {@code @Suspended AsyncResponse} parameters.
     */
    Object suspendedAsyncResponse();
}
