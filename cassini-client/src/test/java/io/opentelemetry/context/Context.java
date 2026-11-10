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
package io.opentelemetry.context;

/**
 * Test double of {@code io.opentelemetry.context.Context}: cassini-client has no OpenTelemetry
 * dependency, so {@code AsyncContextPropagator} finds this class by name, as it finds the real one.
 */
public final class Context {

    private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    private static final Context ROOT = new Context("root");

    private final String name;

    public Context(String name) {
        this.name = name;
    }

    public static Context current() {
        Context current = CURRENT.get();
        return current == null ? ROOT : current;
    }

    public Scope makeCurrent() {
        Context previous = CURRENT.get();
        CURRENT.set(this);
        return () -> CURRENT.set(previous);
    }

    public String name() {
        return name;
    }
}
