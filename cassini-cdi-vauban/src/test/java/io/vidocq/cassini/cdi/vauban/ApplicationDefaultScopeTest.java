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

import io.vidocq.vauban.core.container.VaubanContainer;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.ws.rs.ApplicationPath;
import jakarta.ws.rs.core.Application;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JAX-RS §2.3: the {@code Application} subclass is a singleton component the
 * container must instantiate — like {@code @Path} classes get a default scope,
 * an {@code Application} subclass without one becomes {@code @ApplicationScoped}
 * so the runtime can read its {@code @ApplicationPath} and mount accordingly.
 */
class ApplicationDefaultScopeTest {

    @ApplicationPath("pem")
    public static class PemApplication extends Application {
    }

    @Test
    void applicationSubclassWithoutScopeBecomesABean() {
        try (VaubanContainer container = VaubanContainer.builder()
                .addBeanClass(CassiniScopeExtension.class)
                .addBeanClass(PemApplication.class)
                .build()) {

            boolean found = false;
            for (Bean<?> bean : container.getBeanManager().getBeans(Object.class, Any.Literal.INSTANCE)) {
                if (bean.getBeanClass() != null
                        && Application.class.isAssignableFrom(bean.getBeanClass())) {
                    found = true;
                    break;
                }
            }
            assertTrue(found, "an Application subclass without scope must become a bean");
        }
    }
}
