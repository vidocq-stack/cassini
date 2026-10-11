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
package io.vidocq.cassini.spi.gen;

/**
 * SPI implemented by generated {@code <ApplicationClass>$$CassiniApplication} classes: one per
 * {@code jakarta.ws.rs.core.Application} subclass annotated with {@code @ApplicationPath}.
 *
 * <p>A CDI container in {@code annotated} discovery mode does not discover an {@code Application}
 * subclass without a bean-defining annotation, so a runtime that looks for the application among
 * the beans misses its {@code @ApplicationPath}. The descriptor is found by {@code ServiceLoader}
 * instead (module path: {@code provides ApplicationDescriptor with <Class>$$CassiniApplication};
 * class path: {@code META-INF/services}), without reflection.</p>
 */
public interface ApplicationDescriptor {

    /** @return the {@code Application} subclass this descriptor was generated for */
    Class<?> applicationClass();

    /** @return the value of its {@code @ApplicationPath}, as written */
    String applicationPath();
}
