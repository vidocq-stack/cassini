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
package io.vidocq.cassini.spi.http;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.cassini.spi.resource.ResourceFactory;
import jakarta.ws.rs.core.Application;

import java.util.Comparator;
import java.util.ServiceLoader;

/**
 * Cassini bootstrap facade — assembles the internal components (router,
 * invoker, registries) and exposes a {@link CassiniHttpAdapter} ready to dispatch.
 *
 * <p>Allows {@code cassini-chappe}, {@code cassini-jdk-http} and DI integration
 * modules (e.g. {@code cassini-cdi-vauban}) to no longer depend on
 * {@code cassini-core} directly: they go through the public SPI
 * {@code cassini-api}.
 *
 * <p>Usage example:
 * <pre>{@code
 * CassiniStack stack = CassiniStack.builder()
 *         .application(new MyApplication())
 *         .build();
 * CassiniHttpAdapter adapter = stack.adapter();
 * }</pre>
 *
 * <p>If a {@link BeanProvider.Factory} is registered via {@link ServiceLoader}
 * (e.g. {@code cassini-cdi-vauban} on the classpath), the {@link Builder} is
 * automatically pre-configured with the highest-priority {@link BeanProvider}.
 * The user can override this by explicitly passing {@code beanProvider(null)}.</p>
 */
public interface CassiniStack {

    /** The HTTP adapter ready to dispatch requests. */
    CassiniHttpAdapter adapter();

    static Builder builder() {
        Builder builder = ServiceLoader.load(BuilderFactory.class)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No CassiniStack implementation found. Is cassini-core on the classpath?"))
                .create();

        // Auto-discover BeanProvider via ServiceLoader, choose highest priority.
        ServiceLoader.load(BeanProvider.Factory.class)
                .stream()
                .map(ServiceLoader.Provider::get)
                .max(Comparator.comparingInt(BeanProvider.Factory::priority))
                .ifPresent(f -> builder.beanProvider(f.create()));

        return builder;
    }

    interface Builder {
        Builder application(Application app);
        Builder resourceFactory(ResourceFactory factory);

        /** Adds a manually registered {@code @Provider} instance. */
        Builder provider(Object providerInstance);

        /**
         * Configures the {@link BeanProvider} used to resolve managed resources
         * and providers. If {@code null}, disables all BeanProvider
         * (including the one auto-discovered by {@link CassiniStack#builder()}).
         */
        Builder beanProvider(BeanProvider provider);

        CassiniStack build();
    }

    /** SPI — implemented by cassini-core via ServiceLoader. */
    interface BuilderFactory {
        Builder create();
    }
}
