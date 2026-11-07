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

import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.core.Configuration;
import jakarta.ws.rs.core.FeatureContext;

import java.util.Map;

/**
 * {@link FeatureContext} over a {@link CassiniClientConfiguration}: what a
 * {@link jakarta.ws.rs.core.Feature} registers or sets lands in the configuration the feature
 * was registered on (the client's, the builder's or a target's), never in another one.
 */
final class CassiniClientFeatureContext implements FeatureContext {

    private final CassiniClientConfiguration configuration;

    CassiniClientFeatureContext(CassiniClientConfiguration configuration) { this.configuration = configuration; }

    @Override public Configuration getConfiguration() { return configuration; }

    @Override public FeatureContext property(String name, Object value) { configuration.putProperty(name, value); return this; }

    @Override public FeatureContext register(Class<?> componentClass) { configuration.registerComponent(componentClass, null); return this; }
    @Override public FeatureContext register(Class<?> componentClass, int priority) { configuration.registerComponent(componentClass, CassiniClientConfiguration.contractsOf(componentClass, priority)); return this; }
    @Override public FeatureContext register(Class<?> componentClass, Class<?>... contracts) { configuration.registerComponent(componentClass, CassiniClientConfiguration.contractsOf(contracts, Priorities.USER)); return this; }
    @Override public FeatureContext register(Class<?> componentClass, Map<Class<?>, Integer> contracts) { configuration.registerComponent(componentClass, contracts); return this; }
    @Override public FeatureContext register(Object component) { configuration.registerComponent(component, null); return this; }
    @Override public FeatureContext register(Object component, int priority) { configuration.registerComponent(component, CassiniClientConfiguration.contractsOf(component.getClass(), priority)); return this; }
    @Override public FeatureContext register(Object component, Class<?>... contracts) { configuration.registerComponent(component, CassiniClientConfiguration.contractsOf(contracts, Priorities.USER)); return this; }
    @Override public FeatureContext register(Object component, Map<Class<?>, Integer> contracts) { configuration.registerComponent(component, contracts); return this; }
}
