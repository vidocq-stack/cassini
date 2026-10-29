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

import io.vidocq.cassini.spi.gen.InjectionSupport;
import io.vidocq.cassini.spi.gen.ResourceAdapter;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An adapter generated at run time is the exception, so it is announced loudly: once when a stack is built for the
 * resources the build left without one, then once for each class generated later that no warning named.
 */
class RuntimeGenerationTest {

    private final List<String> warnings = new CopyOnWriteArrayList<>();

    @Path("/started")
    public static class Started {
        @GET public String get() { return "started"; }
    }

    @Path("/later")
    public static class Later {
        @GET public String get() { return "later"; }
    }

    @BeforeEach
    void capture() {
        RuntimeGeneration.redirectForTests(warnings::add);
        AdapterRegistry.deregister(Started.class);
        AdapterRegistry.deregister(Later.class);
    }

    @AfterEach
    void restore() {
        RuntimeGeneration.redirectForTests(null);
        AdapterRegistry.deregister(Started.class);
        AdapterRegistry.deregister(Later.class);
    }

    @Test
    void aStackWarnsOnceForTheResourcesTheBuildLeftWithoutAnAdapter() {
        RuntimeGeneration.stackBuilt(List.of(Started.class));
        RuntimeGeneration.stackBuilt(List.of(Started.class));

        assertEquals(1, warnings.size(), () -> "one warning, not one per stack: " + warnings);
        String warning = warnings.getFirst();
        assertTrue(warning.contains("CASSINI — RUNTIME CODE GENERATION FALLBACK"), warning);
        assertTrue(warning.contains("generated when the application starts"), warning);
        assertTrue(warning.contains("  - " + Started.class.getName() + "\n"), warning);
        assertTrue(warning.contains("cassini-processor"), warning);

        AdapterRegistry.lookup(Started.class).orElseThrow();
        assertEquals(1, warnings.size(), "generating an announced adapter adds nothing");
    }

    @Test
    void aClassGeneratedLaterIsAnnouncedOnFirstUse() {
        AdapterRegistry.lookup(Later.class).orElseThrow();
        AdapterRegistry.lookup(Later.class).orElseThrow();

        assertEquals(1, warnings.size(), () -> String.valueOf(warnings));
        assertTrue(warnings.getFirst().contains("generated on first use"), warnings.getFirst());
        assertTrue(warnings.getFirst().contains("  - " + Later.class.getName() + "\n"), warnings.getFirst());
    }

    @Test
    void aResourceWithAnAdapterFromTheBuildIsNotAnnounced() {
        RuntimeGeneration.stackBuilt(List.of(Prebuilt.class));

        assertEquals(List.of(), warnings);
    }

    @Path("/prebuilt")
    public static class Prebuilt {
        @GET public String get() { return "prebuilt"; }
    }

    /** What the processor or the Maven plugin leaves next to {@link Prebuilt}: found by its name. */
    @SuppressWarnings("java:S101") // the name the build gives an adapter
    public static final class Prebuilt$$CassiniAdapter implements ResourceAdapter {
        @Override
        public void injectFields(Object target, InjectionSupport support, boolean injectParams) {
            // Prebuilt has no field to inject.
        }

        @Override
        public Object invoke(int methodId, Object target, Object[] args) {
            return ((Prebuilt) target).get();
        }
    }
}
