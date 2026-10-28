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

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §4.4 exception mapping: the most specific mapper wins, and an exception thrown while a mapper
 * runs is not mapped again.
 */
class ExceptionMapperRegistryTest {

    @BeforeAll
    static void installRuntimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static ExceptionMapper<RuntimeException> status(int code) {
        return e -> Response.status(code).build();
    }

    @Test
    void theMostSpecificMapperIsFound() {
        var registry = new ExceptionMapperRegistry();
        ExceptionMapper<RuntimeException> runtime = status(500);
        ExceptionMapper<IllegalStateException> illegalState = e -> Response.status(409).build();
        registry.register(RuntimeException.class, runtime);
        registry.register(IllegalStateException.class, illegalState);

        assertSame(illegalState, registry.findMapper(IllegalStateException.class));
        assertSame(runtime, registry.findMapper(IllegalArgumentException.class));
        assertNull(registry.findMapper(Exception.class));
    }

    @Test
    void theMostSpecificMapperRendersTheException() {
        var registry = new ExceptionMapperRegistry();
        registry.register(RuntimeException.class, status(500));
        registry.register(IllegalStateException.class, e -> Response.status(409).build());

        Optional<Response> mapped = registry.map(new IllegalStateException("conflict"));

        assertEquals(409, mapped.orElseThrow().getStatus());
    }

    @Test
    void anExceptionRaisedWhileMappingIsNotMappedAgain() {
        var registry = new ExceptionMapperRegistry();
        registry.register(RuntimeException.class, e -> {
            Optional<Response> nested = registry.map(new IllegalStateException("from the mapper"));
            return Response.status(nested.isEmpty() ? 500 : 409).build();
        });

        assertEquals(500, registry.map(new IllegalArgumentException("boom")).orElseThrow().getStatus());
    }

    @Test
    void anExceptionWithoutMapperIsNotMapped() {
        assertTrue(new ExceptionMapperRegistry().map(new IllegalStateException("unmapped")).isEmpty());
    }
}
