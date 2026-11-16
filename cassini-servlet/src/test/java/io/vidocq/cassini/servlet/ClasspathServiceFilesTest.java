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
package io.vidocq.cassini.servlet;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * On the class path there is no {@code module-info}: the initializer and the delegate are found through
 * {@code META-INF/services}, which must say the same as the {@code provides} of the module descriptor.
 */
class ClasspathServiceFilesTest {

    private static String service(String name) throws IOException {
        try (var in = ClasspathServiceFilesTest.class.getClassLoader().getResourceAsStream("META-INF/services/" + name)) {
            assertNotNull(in, "META-INF/services/" + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).strip();
        }
    }

    @Test
    void theInitializerIsFoundOnTheClassPath() throws IOException {
        assertEquals(CassiniServletInitializer.class.getName(), service("jakarta.servlet.ServletContainerInitializer"));
    }

    @Test
    void theRuntimeDelegateIsFoundOnTheClassPath() throws IOException {
        assertEquals(ServletRuntimeDelegate.class.getName(), service("jakarta.ws.rs.ext.RuntimeDelegate"));
    }
}
