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
     * using {@code support} to resolve method parameters.
     *
     * <p><b>P0 note:</b> this method is declared for interface completeness but is not
     * called in P0 — method invocation still goes through {@code route.javaMethod().invoke()}.
     * P1 will implement direct invocation here, eliminating {@code Method.invoke} from the
     * hot path. {@code methodId} maps a stable per-class integer index to a specific
     * resource method; the index assignment is determined by the generator.</p>
     *
     * @param methodId a stable index identifying the resource method within this adapter's class
     * @param target   the resource instance
     * @param support  the per-request {@link InjectionSupport} façade
     * @return the return value of the invoked method (may be {@code null} for void)
     * @throws Exception if the resource method throws
     */
    Object invoke(int methodId, Object target, InjectionSupport support) throws Exception;
}
