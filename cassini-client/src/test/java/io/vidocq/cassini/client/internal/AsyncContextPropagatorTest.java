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
package io.vidocq.cassini.client.internal;

import io.opentelemetry.context.Context;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link AsyncContextPropagator} against a test double of the OpenTelemetry {@code Context}
 * (same name, on the test path): the context captured on the caller thread is the current one on the
 * async thread, and it is restored when the scope closes.
 */
class AsyncContextPropagatorTest {

    @Test
    void the_captured_context_is_current_on_another_thread_and_restored_on_close() throws Exception {
        Context caller = new Context("caller");
        AtomicReference<String> seen = new AtomicReference<>();
        AtomicReference<String> after = new AtomicReference<>();
        try (var _ = caller.makeCurrent()) {
            Object captured = AsyncContextPropagator.capture();
            assertSame(caller, captured);
            Thread async = Thread.ofVirtual().start(() -> {
                try (var _ = AsyncContextPropagator.activate(captured)) {
                    seen.set(Context.current().name());
                }
                after.set(Context.current().name());
            });
            async.join();
        }
        assertEquals("caller", seen.get());
        assertEquals("root", after.get());
    }

    @Test
    void a_null_capture_activates_nothing() {
        try (var _ = AsyncContextPropagator.activate(null)) {
            assertEquals("root", Context.current().name());
        }
        assertNotNull(AsyncContextPropagator.capture());
    }

    @Test
    void the_lookup_is_done_once_not_on_each_call() {
        for (Field field : AsyncContextPropagator.class.getDeclaredFields()) {
            assertNotSame(Method.class, field.getType(), field + " would reflect on each call");
        }
        long handles = java.util.Arrays.stream(AsyncContextPropagator.class.getDeclaredFields())
                .filter(f -> f.getType() == MethodHandle.class)
                .count();
        assertEquals(2, handles);
    }
}
