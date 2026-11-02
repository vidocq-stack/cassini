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
package io.vidocq.cassini.internal.gen;

import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;

/**
 * Finds one class the build generated next to an application class and published as a {@link ServiceLoader}
 * provider: in the layer of the class's module (its {@code provides}, or the ones Vidocq promotes from
 * {@code META-INF/services}) or, outside a named module, through its class loader. The provider is matched by
 * type, so the other providers are not instantiated.
 */
final class GeneratedServices {

    private GeneratedServices() {
    }

    /** The provider of {@code service} named {@code className} seen from {@code owner}, or {@code null}. */
    static <S> S find(Class<?> owner, Class<S> service, String className) {
        Module module = owner.getModule();
        ModuleLayer layer = module.getLayer();
        try {
            ServiceLoader<S> loader = module.isNamed() && layer != null
                    ? ServiceLoader.load(layer, service)
                    : ServiceLoader.load(service, owner.getClassLoader());
            return loader.stream()
                    .filter(p -> p.type().getName().equals(className))
                    .map(ServiceLoader.Provider::get)
                    .findFirst()
                    .orElse(null);
        } catch (ServiceConfigurationError | RuntimeException _) {
            return null;
        }
    }
}
