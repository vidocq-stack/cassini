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
package io.vidocq.cassini.cdi.vauban;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.lang.module.ModuleDescriptor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** On the module path Cassini finds a provider descriptor through {@code provides}: the module declares every one
 *  cassini-processor listed in {@code META-INF/services}. */
class ProviderDescriptorProvidesTest {

    private static final String SERVICE = "io.vidocq.cassini.spi.gen.ProviderDescriptor";

    @Test
    void theModuleProvidesTheDescriptorOfItsFilter() throws IOException {
        Set<String> listed = Set.copyOf(Files.readAllLines(Path.of("target/classes/META-INF/services/" + SERVICE)));
        ModuleDescriptor module;
        try (InputStream in = Files.newInputStream(Path.of("target/classes/module-info.class"))) {
            module = ModuleDescriptor.read(in);
        }
        Set<String> provided = module.provides().stream()
                .filter(p -> p.service().equals(SERVICE))
                .flatMap(p -> p.providers().stream())
                .collect(Collectors.toSet());

        assertEquals(Set.of(VaubanRequestScopeFilter.class.getName() + "$$CassiniProvider"), listed);
        assertEquals(listed, provided);
    }
}
