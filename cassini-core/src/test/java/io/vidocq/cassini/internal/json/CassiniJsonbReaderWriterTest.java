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
package io.vidocq.cassini.internal.json;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.NoContentException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The built-in JSON-B reader's contract for an empty entity (cassini#39), checked
 * on the reader alone — which is also what application code gets through
 * {@code @Context Providers}: a {@link NoContentException}, never a
 * {@code BadRequestException} (that translation is the server runtime's, §4.2.4).
 */
class CassiniJsonbReaderWriterTest {

    private final CassiniJsonbReaderWriter reader = new CassiniJsonbReaderWriter();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object read(Class<?> type, String body) throws Exception {
        return reader.readFrom((Class) type, type, new Annotation[0], MediaType.APPLICATION_JSON_TYPE,
                null, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void anEmptyEntityIsANoContentException() {
        assertThrows(NoContentException.class, () -> read(Integer.class, ""));
    }

    @Test
    void aNonEmptyEntityIsBoundWhole() throws Exception {
        // The emptiness probe reads one byte ahead; it must hand it back.
        assertEquals(42, read(Integer.class, "42"));
        assertEquals(7, read(Integer.class, "7"));
    }
}
