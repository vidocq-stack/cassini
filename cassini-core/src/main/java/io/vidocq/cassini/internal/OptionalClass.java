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
package io.vidocq.cassini.internal;

/**
 * A class that may be absent from the module path, such as the API of an optional
 * specification ({@code jakarta.activation.DataSource}), looked up once and remembered, present
 * or not.
 *
 * <p>A lookup that fails is expensive: the class loaders walk their paths and build a
 * {@code ClassNotFoundException} with its stack trace. Holding the answer in a static field keeps
 * that cost out of the request path, where the previous {@code Class.forName} per call paid it
 * on every request of a deployment without the optional API.</p>
 */
public final class OptionalClass {

    private final Class<?> type;

    private OptionalClass(Class<?> type) {
        this.type = type;
    }

    /** Looks {@code name} up through {@code loader}, without initializing the class. */
    public static OptionalClass of(String name, ClassLoader loader) {
        try {
            return new OptionalClass(Class.forName(name, false, loader));
        } catch (ClassNotFoundException | LinkageError e) {
            return new OptionalClass(null);
        }
    }

    public boolean isPresent() {
        return type != null;
    }

    /** The class, or {@code null} when absent. */
    public Class<?> get() {
        return type;
    }

    /** False when the class is absent or {@code candidate} is {@code null}. */
    public boolean isAssignableFrom(Class<?> candidate) {
        return type != null && candidate != null && type.isAssignableFrom(candidate);
    }
}
