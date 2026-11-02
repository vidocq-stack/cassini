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

import io.vidocq.cassini.spi.gen.ProviderDescriptor;

import java.util.Map;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link ProviderDescriptor} {@code cassini-processor} generated for a {@code @Provider} class, so that the
 * runtime reads what it needs from generated code instead of reflection. A class without a descriptor (the
 * processor did not run on it, another container, a provider registered by hand) gets {@code null}, and every
 * caller then keeps its reflective reading, unchanged.
 *
 * <p>The descriptor is the provider {@code <binary name>$$CassiniProvider} of the {@link ServiceLoader} of the
 * class's layer (its {@code provides}) or, outside a named module, of its class loader. It is matched by type,
 * without instantiating the other descriptors, and cached on the class.</p>
 */
public final class ProviderDescriptors {

    /** Suffix of the descriptor class generated next to a provider class. */
    public static final String SUFFIX = "$$CassiniProvider";

    /** Descriptors registered directly, for tests. */
    private static final Map<Class<?>, ProviderDescriptor> REGISTERED = new ConcurrentHashMap<>();

    private static final ClassValue<Optional<ProviderDescriptor>> FOUND = new ClassValue<>() {
        @Override
        protected Optional<ProviderDescriptor> computeValue(Class<?> type) {
            return Optional.ofNullable(load(type));
        }
    };

    private ProviderDescriptors() {
    }

    /** The descriptor generated for {@code providerClass}, or {@code null}. */
    public static ProviderDescriptor of(Class<?> providerClass) {
        if (providerClass == null) return null;
        ProviderDescriptor registered = REGISTERED.get(providerClass);
        return registered != null ? registered : FOUND.get(providerClass).orElse(null);
    }

    /** Registers a descriptor directly; for tests. */
    static void register(ProviderDescriptor descriptor) {
        REGISTERED.put(descriptor.providerClass(), descriptor);
    }

    /** Removes a descriptor registered directly; for tests. */
    static void unregister(Class<?> providerClass) {
        REGISTERED.remove(providerClass);
    }

    private static ProviderDescriptor load(Class<?> type) {
        ProviderDescriptor d = GeneratedServices.find(type, ProviderDescriptor.class, type.getName() + SUFFIX);
        return d != null && d.providerClass() == type ? d : null;
    }
}
