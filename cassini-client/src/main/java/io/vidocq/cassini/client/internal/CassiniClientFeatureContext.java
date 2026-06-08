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
package io.vidocq.cassini.client.internal;

import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;

import java.util.Map;

/**
 * {@link FeatureContext} adapter that delegates all
 * {@code register}/{@code property} operations to the target {@link Client} — used by
 * {@link CassiniClient#build()} to invoke
 * {@link jakarta.ws.rs.core.Feature#configure(FeatureContext)} on each Feature
 * discovered via {@link java.util.ServiceLoader}.
 */
final class CassiniClientFeatureContext implements FeatureContext {

    private final Client client;

    CassiniClientFeatureContext(Client client) { this.client = client; }

    @Override public Configuration getConfiguration() { return client.getConfiguration(); }

    @Override public FeatureContext property(String name, Object value) { client.property(name, value); return this; }

    @Override public FeatureContext register(Class<?> componentClass) { client.register(componentClass); return this; }
    @Override public FeatureContext register(Class<?> componentClass, int priority) { client.register(componentClass, priority); return this; }
    @Override public FeatureContext register(Class<?> componentClass, Class<?>... contracts) { client.register(componentClass, contracts); return this; }
    @Override public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) { client.register(componentClass, contracts); return this; }
    @Override public FeatureContext register(Object component) { client.register(component); return this; }
    @Override public FeatureContext register(Object component, int priority) { client.register(component, priority); return this; }
    @Override public FeatureContext register(Object component, Class<?>... contracts) { client.register(component, contracts); return this; }
    @Override public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) { client.register(component, contracts); return this; }
}
