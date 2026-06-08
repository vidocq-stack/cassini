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
package io.vidocq.cassini.spi.bean;

import java.util.Set;

/**
 * SPI allowing a DI container (CDI Vauban/Weld/OpenWebBeans, or any other
 * mechanism) to provide managed instances of JAX-RS resources and providers
 * to Cassini.
 *
 * <p>Decoupling: neither cassini-api nor cassini-core depend on jakarta.cdi.
 * Each ecosystem provides its own {@code BeanProvider} via ServiceLoader.</p>
 *
 * <p>Provided implementations:</p>
 * <ul>
 *   <li>{@code cassini-cdi-vauban} → Vauban adapter (priority 100)</li>
 * </ul>
 *
 * <p>Auto-discovery: {@code CassiniStack#builder()} looks for a
 * {@link Factory} via {@link java.util.ServiceLoader} and uses the one with the
 * highest priority. The user can also pass one explicitly via
 * {@code CassiniStack.Builder#beanProvider(BeanProvider)}.</p>
 */
public interface BeanProvider {

    /**
     * Returns a managed instance of the class (resolved injection).
     *
     * @throws IllegalArgumentException if the class is not managed by this provider
     */
    <T> T getBean(Class<T> type);

    /**
     * Lists the classes annotated with {@code @Path} or {@code @Provider}
     * managed by this provider. Used by Cassini to scan resources without the
     * user having to declare them in {@code Application.getClasses()}.
     */
    Set<Class<?>> getResourceClasses();

    /**
     * Returns the underlying <em>contextual</em> instance of {@code bean}.
     *
     * <p>For a normal-scoped bean (e.g. {@code @RequestScoped}), {@link #getBean(Class)}
     * returns a <em>client proxy</em> that lazily delegates to the contextual instance.
     * Cassini's {@code @Context} injection writes by reflection into the fields of the object
     * it is given: if that object is the proxy, the injected field is never seen by the method
     * body (which executes on the contextual instance behind the proxy). Cassini therefore calls
     * this method to obtain the <em>real</em> contextual instance into which to inject the
     * {@code @Context} fields; invocation of the resource method still happens through the proxy
     * (which resolves the same contextual instance in the active scope).</p>
     *
     * <p>Contract: the instance returned MUST be the one to which {@code bean} (the proxy)
     * delegates in the current scope. Default implementation: returns {@code bean} as-is
     * (case without a proxy — direct instantiation or pseudo-scopes such as {@code @Dependent}).</p>
     *
     * @param type the resource/provider class requested via {@link #getBean(Class)}
     * @param bean the object returned by {@link #getBean(Class)} (potentially a proxy)
     * @return the real contextual instance, or {@code bean} if not applicable
     */
    default Object contextualInstance(Class<?> type, Object bean) {
        return bean;
    }

    /**
     * SPI ServiceLoader — implementations registered via
     * {@code provides BeanProvider.Factory with ...} in {@code module-info}.
     */
    interface Factory {
        /**
         * Creates an instance of {@link BeanProvider}. Called once by
         * {@code CassiniStack#builder()} (the BeanProvider is then reused).
         */
        BeanProvider create();

        /**
         * Priority (higher = preferred) in case of conflicts between multiple
         * implementations on the classpath. Default: 0.
         */
        default int priority() { return 0; }
    }
}
