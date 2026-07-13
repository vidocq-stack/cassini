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
     * @param support      the per-request {@link InjectionSupport} facade
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

    /**
     * Creates a fresh instance of the resource class via a direct {@code new} call (no reflection).
     *
     * <p><b>M6a design:</b> generated adapters override this when the resource class has a
     * publicly accessible no-arg constructor visible from the adapter's package (same package
     * as the resource, so package-private constructors are also accessible). When the class
     * has NO no-arg constructor (constructor injection, only a {@code String} ctor, private
     * nested class, etc.), the default implementation throws {@link UnsupportedOperationException}
     * and callers fall back to the reflective path ({@code getDeclaredConstructor().newInstance()}).</p>
     *
     * @return a fresh, uninitialised instance of the resource class
     * @throws UnsupportedOperationException if the resource class has no accessible no-arg
     *         constructor (caller must fall back to reflection)
     */
    default Object newInstance() {
        throw new UnsupportedOperationException(
                "No generated newInstance() — no public/package no-arg constructor or generation skipped");
    }

    /**
     * Returns the resource class this adapter was generated for, or {@code null} when the
     * adapter does not advertise it.
     *
     * <p><b>ServiceLoader keying:</b> when an adapter is registered as a {@code ServiceLoader}
     * provider (module-path {@code provides ResourceAdapter with <Class>$$CassiniAdapter}, or a
     * {@code META-INF/services} entry), {@code AdapterRegistry} builds a {@code Class → adapter}
     * map keyed by this method. This lets a strict Java Modules application keep its resource package
     * <em>closed</em> (neither {@code opens} nor {@code exports}): the module system instantiates
     * the provider from the closed package, so cassini-core never reflects into it.</p>
     *
     * <p>APT- and plugin-generated adapters override this with {@code return <Class>.class;}.
     * The default returns {@code null}: runtime-generated adapters (never registered as services)
     * and the registry sentinel are simply skipped when the {@code ServiceLoader} map is built —
     * they continue to be resolved through the {@code Class.forName} / runtime-generator fallback.</p>
     *
     * @return the resource class, or {@code null} if not advertised
     */
    default Class<?> resourceClass() {
        return null;
    }
}
