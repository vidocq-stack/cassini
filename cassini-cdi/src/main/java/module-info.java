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
/**
 * Container-neutral CDI integration for Cassini (cassini#53): resolves resources and providers as CDI
 * beans through the standard APIs only, so Cassini runs with any CDI 4 container. The Vauban-specific
 * provider of {@code io.vidocq.cassini.cdi.vauban} takes precedence when Vauban runs.
 */
module io.vidocq.cassini.cdi {
    requires io.vidocq.cassini.api;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires jakarta.annotation;

    exports io.vidocq.cassini.cdi;

    provides io.vidocq.cassini.spi.bean.BeanProvider.Factory
            with io.vidocq.cassini.cdi.CdiBeanProviderFactory;

    // JAX-RS 4.0 §11.2.5: @Path and @Provider classes without a scope become beans, with their
    // default scope. A standard build compatible extension: every CDI container runs it.
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cassini.cdi.CassiniScopeExtension;
}
