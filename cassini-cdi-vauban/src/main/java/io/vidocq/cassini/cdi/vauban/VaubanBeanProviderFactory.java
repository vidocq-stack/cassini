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
package io.vidocq.cassini.cdi.vauban;

import io.vidocq.cassini.spi.bean.BeanProvider;
import io.vidocq.vauban.core.container.VaubanContainer;

/**
 * ServiceLoader factory for {@link VaubanBeanProvider}.
 *
 * <p>Discovered by {@code CassiniStack.builder()}: if Vauban is on the
 * classpath and a container is running, the current instance is used
 * as the default {@link BeanProvider}.</p>
 */
public final class VaubanBeanProviderFactory implements BeanProvider.Factory {

    @Override
    public BeanProvider create() {
        var container = VaubanContainer.current();
        if (container == null) {
            throw new IllegalStateException(
                    "No Vauban CDI container is running — call VaubanContainer.builder().build() first");
        }
        return new VaubanBeanProvider(container);
    }

    @Override
    public int priority() { return 100; }
}
