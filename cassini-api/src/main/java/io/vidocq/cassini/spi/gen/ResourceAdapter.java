package io.vidocq.cassini.spi.gen;

/**
 * Generated adapter for a single resource class, replacing per-request reflection
 * for field injection and (in P1+) method invocation.
 *
 * <p>One implementation is emitted per JAX-RS resource class — either at compile
 * time by the APT processor ({@code cassini-processor}, P2) or at runtime by the
 * Class-File API generator ({@code cassini-core}, P1). The naming convention is:
 * {@code <OriginalClass>$$CassiniAdapter} in the same package.</p>
 *
 * <p>Implementations are discovered by {@code AdapterRegistry} and must be either
 * loadable by name (APT path) or registered programmatically (runtime path).</p>
 */
public interface ResourceAdapter {

    /**
     * Injects {@code @Context} fields (always) and {@code @*Param} / {@code @BeanParam}
     * fields (only when {@code injectParams} is {@code true}) into {@code target}.
     *
     * <p>This mirrors the contract of {@code FieldInjector.inject(target, match, request, injectParams)}.
     * For sub-resource locator roots, the Invoker calls this with {@code injectParams=false}
     * per §3.4.1 of the JAX-RS spec.</p>
     *
     * @param target       the resource instance (already unwrapped from CDI proxy by {@code injectionTarget})
     * @param support      the per-request {@link InjectionSupport} façade
     * @param injectParams whether to inject {@code @*Param} fields (false for sub-resource roots)
     */
    void injectFields(Object target, InjectionSupport support, boolean injectParams);

    /**
     * Invokes the resource method identified by {@code methodId} on {@code target},
     * using the pre-resolved argument array {@code args}.
     *
     * <p><b>P1b design:</b> argument resolution (ParamExtractor, readEntity, @Suspended,
     * SseEventSink) is performed by the Invoker BEFORE this call, producing an {@code Object[]}
     * that exactly matches the method's parameter list. The adapter merely performs the typed
     * direct call — no reflection, no InjectionSupport needed here.</p>
     *
     * <p>{@code methodId} maps a stable per-class integer index to a specific resource method;
     * the mapping is determined by the generator via {@code AdapterRegistry.methodId()}.</p>
     *
     * @param methodId a stable index identifying the resource method within this adapter's class
     * @param target   the resource instance (may be a CDI proxy — virtual dispatch handles it)
     * @param args     the pre-resolved argument array, in the same order as the method parameters
     * @return the return value of the invoked method (boxed for primitives; {@code null} for void)
     * @throws Throwable if the resource method throws (propagated raw, without wrapping)
     */
    Object invoke(int methodId, Object target, Object[] args) throws Throwable;
}
