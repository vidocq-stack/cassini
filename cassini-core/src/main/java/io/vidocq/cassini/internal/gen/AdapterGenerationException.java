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
package io.vidocq.cassini.internal.gen;

import java.io.Serial;

/**
 * A runtime resource adapter could not be generated or defined: the resource's package is not
 * open to Cassini, the class definition failed, or its static initialiser failed. The
 * {@link AdapterRegistry} then falls back to reflective injection.
 */
public final class AdapterGenerationException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    AdapterGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
