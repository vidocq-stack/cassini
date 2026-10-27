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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A type that may be absent from the module path (an optional API such as
 * {@code jakarta.activation.DataSource}) is looked up once. Before this class existed, the JSON
 * provider probed it with {@code Class.forName} on every {@code isWriteable} / {@code isReadable}
 * call, which on a deployment without the API meant one {@code ClassNotFoundException}, with its
 * stack trace, per request.
 */
class OptionalClassTest {

    private static final ClassLoader LOADER = OptionalClassTest.class.getClassLoader();

    @Test
    void anAbsentClassIsLookedUpOnceNotOnEveryCheck() {
        OptionalClass absent = OptionalClass.of("no.such.api.NoSuchType", LOADER);
        assertFalse(absent.isPresent());
        long perCall = Allocations.bytesPerCall(() -> assertFalse(absent.isAssignableFrom(Integer.class)), 1_000, 10_000);
        assertTrue(perCall < 16, "an absent class is probed again on every check: " + perCall + " bytes per call");
    }

    @Test
    void aPresentClassAnswersAssignability() {
        OptionalClass list = OptionalClass.of("java.util.List", LOADER);
        assertTrue(list.isPresent());
        assertTrue(list.isAssignableFrom(ArrayList.class));
        assertTrue(list.isAssignableFrom(List.class));
        assertFalse(list.isAssignableFrom(String.class));
        assertEquals(List.class, list.get());
    }

    @Test
    void anAbsentClassIsAssignableFromNothing() {
        OptionalClass absent = OptionalClass.of("no.such.api.NoSuchType", LOADER);
        assertFalse(absent.isAssignableFrom(Object.class));
        assertFalse(absent.isAssignableFrom(null));
        assertEquals(null, absent.get());
    }
}
