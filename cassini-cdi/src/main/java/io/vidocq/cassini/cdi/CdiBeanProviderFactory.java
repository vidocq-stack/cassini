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
package io.vidocq.cassini.cdi;

import io.vidocq.cassini.spi.bean.BeanProvider;
import jakarta.enterprise.inject.spi.CDI;

/**
 * Finds the running CDI container through {@link CDI#current()}, whatever it is. Its priority is
 * below the Vauban provider's: when Vauban runs, its own provider, which reads Vauban's build-time
 * index, is chosen.
 *
 * <p>With no container running, {@link #create()} returns {@code null}: Cassini then runs without
 * CDI, as it does with no provider at all.</p>
 */
public final class CdiBeanProviderFactory implements BeanProvider.Factory {

    /** Below {@code VaubanBeanProviderFactory} (100). */
    static final int PRIORITY = 50;

    @Override
    public BeanProvider create() {
        try {
            CDI<Object> cdi = CDI.current();
            return new CdiBeanProvider(cdi);
        } catch (IllegalStateException _) {
            return null;
        }
    }

    @Override
    public int priority() {
        return PRIORITY;
    }
}
