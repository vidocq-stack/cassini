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
module io.vidocq.cassini.cdi.vauban {
    requires io.vidocq.cassini.api;
    requires jakarta.cdi;
    requires jakarta.inject;
    requires jakarta.ws.rs;
    requires io.vidocq.vauban.core;

    exports io.vidocq.cassini.cdi.vauban;

    provides io.vidocq.cassini.spi.bean.BeanProvider.Factory
            with io.vidocq.cassini.cdi.vauban.VaubanBeanProviderFactory;

    // Cassini BCE that aligns Vauban with JAX-RS 4.0 spec §11.2.5:
    // @Path/@Provider without a scope -> @RequestScoped / @Dependent by default.
    // Without this provides clause, Vauban (a generic CDI container) ignores
    // JAX-RS classes without a bean-defining annotation and Cassini does not discover them.
    provides jakarta.enterprise.inject.build.compatible.spi.BuildCompatibleExtension
            with io.vidocq.cassini.cdi.vauban.CassiniScopeExtension;
}
