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
package io.vidocq.cassini.tck;

import io.vidocq.cassini.internal.multipart.MultipartFormDataProvider;
import jakarta.ws.rs.RuntimeType;
import jakarta.ws.rs.core.FeatureContext;
import org.glassfish.jersey.internal.spi.AutoDiscoverable;

/**
 * §3.5.4 / Jersey AutoDiscoverable: registers the multipart MBR/MBW
 * {@link MultipartFormDataProvider} as soon as a Jersey {@link jakarta.ws.rs.client.Client}
 * is created via {@link jakarta.ws.rs.client.ClientBuilder}. Without this, the
 * Jersey client cannot serialize/deserialize {@code List<EntityPart>}
 * (it would look for its internal {@code BodyPart} type).
 *
 * <p>Activé via {@code META-INF/services/org.glassfish.jersey.internal.spi.AutoDiscoverable}.</p>
 */
public final class CassiniMultipartAutoDiscover implements AutoDiscoverable {

    @Override
    public void configure(FeatureContext context) {
        if (context.getConfiguration().getRuntimeType() == RuntimeType.CLIENT) {
            if (!context.getConfiguration().isRegistered(MultipartFormDataProvider.class)) {
                context.register(MultipartFormDataProvider.class);
            }
            if (!context.getConfiguration().isRegistered(CassiniMultipartBoundaryFilter.class)) {
                context.register(CassiniMultipartBoundaryFilter.class);
            }
        }
    }
}
