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
package io.vidocq.cassini.it.weld;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Destroyed;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.event.Observes;
import java.util.concurrent.atomic.AtomicInteger;

/** Counts the request contexts that start and end, whichever way the request ends. */
@ApplicationScoped
public class ScopeEvents {

    static final AtomicInteger INITIALIZED = new AtomicInteger();
    static final AtomicInteger DESTROYED = new AtomicInteger();

    void initialized(@Observes @Initialized(RequestScoped.class) Object payload) {
        INITIALIZED.incrementAndGet();
    }

    void destroyed(@Observes @Destroyed(RequestScoped.class) Object payload) {
        DESTROYED.incrementAndGet();
    }
}
