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

import io.vidocq.cassini.internal.json.CassiniJsonbReaderWriter;
import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Provider selection (§4.2.1, §4.2.4) runs on every request. What it needs to know about a
 * provider — the entity type it declares, its {@code @Produces} / {@code @Consumes} media types —
 * is fixed when the provider is registered; it was resolved again, through generic reflection
 * and media type parsing, for every provider on every call.
 */
class MessageBodyRegistryTest {

    private static final Annotation[] NONE = new Annotation[0];

    record Todo(long id, String title) {}

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private final MessageBodyRegistry registry = new MessageBodyRegistry();

    @Test
    void theJsonProviderServesARecordAsJson() {
        assertEquals(CassiniJsonbReaderWriter.class,
                registry.findWriter(Todo.class, Todo.class, NONE, MediaType.APPLICATION_JSON_TYPE).orElseThrow().getClass());
        assertEquals(CassiniJsonbReaderWriter.class,
                registry.findReader(Todo.class, Todo.class, NONE, MediaType.APPLICATION_JSON_TYPE).orElseThrow().getClass());
    }

    @Test
    void findWriterResolvesWhatItKnowsAboutTheProvidersOnce() {
        long perCall = Allocations.bytesPerCall(
                () -> registry.findWriter(Todo.class, Todo.class, NONE, MediaType.APPLICATION_JSON_TYPE), 2_000, 10_000);
        assertTrue(perCall < 512, "findWriter allocates " + perCall
                + " bytes per call: the provider types and declared media types are resolved again on every call");
    }

    @Test
    void findReaderResolvesWhatItKnowsAboutTheProvidersOnce() {
        long perCall = Allocations.bytesPerCall(
                () -> registry.findReader(Todo.class, Todo.class, NONE, MediaType.APPLICATION_JSON_TYPE), 2_000, 10_000);
        assertTrue(perCall < 512, "findReader allocates " + perCall
                + " bytes per call: the provider types and declared media types are resolved again on every call");
    }

    /** A user provider registered later keeps winning on a tie, and its declared types still filter it. */
    @Test
    void aUserProviderRegisteredLaterStillWinsOnItsMediaType() {
        registry.addWriter(new TodoXmlWriter());
        assertEquals(TodoXmlWriter.class,
                registry.findWriter(Todo.class, Todo.class, NONE, MediaType.APPLICATION_XML_TYPE).orElseThrow().getClass());
        assertEquals(CassiniJsonbReaderWriter.class,
                registry.findWriter(Todo.class, Todo.class, NONE, MediaType.APPLICATION_JSON_TYPE).orElseThrow().getClass());
    }

    @Produces("application/xml")
    static final class TodoXmlWriter implements MessageBodyWriter<Todo> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return true; }
        @Override public void writeTo(Todo todo, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream out) { }
    }
}
