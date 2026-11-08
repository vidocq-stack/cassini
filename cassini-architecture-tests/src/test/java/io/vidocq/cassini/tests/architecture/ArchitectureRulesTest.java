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
package io.vidocq.cassini.tests.architecture;

import static io.vidocq.cassini.tests.architecture.CassiniClasses.SHIPPED;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Named.named;

import java.util.stream.Stream;

import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The architecture rules on the classes Cassini ships, test classes left out (Vidocq/vidocq#289). A failure names
 * each class and the dependency or call that breaks the rule.
 */
class ArchitectureRulesTest {

    @Test
    void theShippedClassesAreFound() {
        // Guards the other tests: a class path change that imports nothing would make every rule pass.
        assertTrue(SHIPPED.contain("io.vidocq.cassini.spi.gen.ResourceAdapter"), "cassini-api imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.internal.RequestScope"), "cassini-core imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.processor.CassiniResourceProcessor"), "processor imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.cdi.CdiBeanProvider"), "cassini-cdi imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.cdi.vauban.VaubanBeanProvider"), "cdi-vauban imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.chappe.ChappeHttpAdapter"), "cassini-chappe imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.jdkhttp.JdkHttpAdapter"), "cassini-jdk-http imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.client.internal.CassiniClient"), "cassini-client imported");
        assertTrue(SHIPPED.contain("io.vidocq.cassini.testing.CassiniTestHarness"), "cassini-testing imported");
    }

    static Stream<Named<ArchRule>> rules() {
        return Stream.of(
                named("API_DEPENDENCIES", ArchitectureRules.API_DEPENDENCIES),
                named("PROCESSOR_DEPENDENCIES", ArchitectureRules.PROCESSOR_DEPENDENCIES),
                named("CORE_DEPENDENCIES", ArchitectureRules.CORE_DEPENDENCIES),
                named("CDI_DEPENDENCIES", ArchitectureRules.CDI_DEPENDENCIES),
                named("CDI_VAUBAN_DEPENDENCIES", ArchitectureRules.CDI_VAUBAN_DEPENDENCIES),
                named("CHAPPE_DEPENDENCIES", ArchitectureRules.CHAPPE_DEPENDENCIES),
                named("JDK_HTTP_DEPENDENCIES", ArchitectureRules.JDK_HTTP_DEPENDENCIES),
                named("CLIENT_DEPENDENCIES", ArchitectureRules.CLIENT_DEPENDENCIES),
                named("TESTING_DEPENDENCIES", ArchitectureRules.TESTING_DEPENDENCIES),
                named("API_NEVER_DEPENDS_ON_CORE", ArchitectureRules.API_NEVER_DEPENDS_ON_CORE),
                named("NO_OTHER_BRICK_IN_API_AND_CORE", ArchitectureRules.NO_OTHER_BRICK_IN_API_AND_CORE),
                named("LAYERS", ArchitectureRules.LAYERS),
                named("NO_REFLECTION", ArchitectureRules.NO_REFLECTION),
                named("NO_PLATFORM_THREADS", ArchitectureRules.NO_PLATFORM_THREADS),
                named("NO_THREAD_LOCAL", ArchitectureRules.NO_THREAD_LOCAL));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rules")
    void theShippedClassesKeepTheRule(ArchRule rule) {
        rule.check(SHIPPED);
    }
}
