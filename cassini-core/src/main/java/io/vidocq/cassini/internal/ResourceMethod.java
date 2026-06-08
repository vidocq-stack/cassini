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
package io.vidocq.cassini.internal;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

/**
 * Model of a JAX-RS resource method discovered by Cassini.
 *
 * @param beanClass       class carrying the final method (sub-resource)
 * @param javaMethod      Java method (already made accessible) — null if dynamicLocator
 * @param httpMethod      HTTP verb (GET, POST, …)
 * @param template        combined URI template (class + method)
 * @param produces        media types declared via {@code @Produces}
 * @param consumes        media types declared via {@code @Consumes}
 * @param rootBeanClass   root class instantiated first (§3.4.1)
 * @param locatorChain    ordered chain of locator methods to invoke on the
 *                        root to reach the instance of {@code beanClass}
 * @param dynamicLocator  §3.4.1: catch-all route emitted when a sub-resource
 *                        locator returns {@code Object} or a non-scannable type.
 *                        At runtime, the Invoker executes the chain and scans the
 *                        effective class of the returned instance for dispatch.
 */
public record ResourceMethod(
        Class<?> beanClass,
        Method javaMethod,
        String httpMethod,
        UriTemplate template,
        Set<String> produces,
        Set<String> consumes,
        Class<?> rootBeanClass,
        List<Method> locatorChain,
        int classPathLiterals,
        boolean dynamicLocator) {

    public ResourceMethod(Class<?> beanClass, Method javaMethod, String httpMethod,
                          UriTemplate template, Set<String> produces, Set<String> consumes,
                          Class<?> rootBeanClass, List<Method> locatorChain, int classPathLiterals) {
        this(beanClass, javaMethod, httpMethod, template, produces, consumes,
                rootBeanClass, locatorChain, classPathLiterals, false);
    }

    public ResourceMethod(Class<?> beanClass, Method javaMethod, String httpMethod,
                          UriTemplate template, Set<String> produces, Set<String> consumes) {
        this(beanClass, javaMethod, httpMethod, template, produces, consumes, null, null, 0, false);
    }

    /** Compatibilité : locator unique (chaîne de longueur 1). */
    public ResourceMethod(Class<?> beanClass, Method javaMethod, String httpMethod,
                          UriTemplate template, Set<String> produces, Set<String> consumes,
                          Class<?> rootBeanClass, Method locator) {
        this(beanClass, javaMethod, httpMethod, template, produces, consumes, rootBeanClass,
                locator == null ? null : List.of(locator), 0, false);
    }

    public String path() {
        return template.template();
    }

    /** True if this route comes from a sub-resource locator §3.4.1. */
    public boolean isLocated() {
        return locatorChain != null && !locatorChain.isEmpty() && rootBeanClass != null;
    }
}
