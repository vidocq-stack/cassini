package io.vidocq.cassini.spi.gen;

/**
 * Identifies the JAX-RS parameter annotation kind for a resource parameter.
 * Used by {@link InjectionSupport#param} to dispatch to the correct source.
 */
public enum ParamKind {
    PATH,
    QUERY,
    HEADER,
    COOKIE,
    MATRIX,
    FORM
}
