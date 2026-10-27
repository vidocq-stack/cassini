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

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A stack tells its transport whether a dispatch may hand the response over as a stream before
 * it ends: only a resource method with an {@code SseEventSink} does, or a sub-resource locator
 * whose target is known at run time only. The transport runs every other dispatch on the
 * calling thread.
 */
class MayStreamTest {

    @Path("/things")
    public static class Plain {
        @GET public String list() { return "[]"; }
    }

    @Path("/events")
    public static class Events {
        @GET public void stream(@Context SseEventSink sink, @Context Sse sse) throws java.io.IOException {
            sink.close();
        }
    }

    @Path("/locate")
    public static class Locator {
        @Path("{any}") public Object sub() { return new Plain(); }
    }

    private static boolean mayStream(Class<?>... resources) {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override public Set<Class<?>> getClasses() { return Set.of(resources); }
        }).build().adapter().mayStream();
    }

    @Test
    void aStackWithoutEventSinkNeverStreams() {
        assertFalse(mayStream(Plain.class));
    }

    @Test
    void aStackWithAnEventSinkMayStream() {
        assertTrue(mayStream(Plain.class, Events.class));
    }

    @Test
    void aStackWithARunTimeLocatorMayStream() {
        assertTrue(mayStream(Plain.class, Locator.class));
    }
}
