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
package io.vidocq.cassini.internal.multipart;

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §3.5.4: {@link EntityPart.Builder} and the {@link EntityPart} it builds. */
class CassiniEntityPartTest {

    private static final String NAME = "part";
    private static final String TEXT = "content";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private static CassiniEntityPartBuilder builder() {
        return new CassiniEntityPartBuilder(NAME);
    }

    @Test
    void aPartHasANameAndDefaultsToTextPlain() throws IOException {
        EntityPart part = builder().content("").build();
        assertEquals(NAME, part.getName());
        assertEquals(Optional.empty(), part.getFileName());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, part.getMediaType());
        assertTrue(part.getHeaders().isEmpty());
        assertEquals("", part.getContent(String.class));
        assertThrows(IllegalArgumentException.class, () -> new CassiniEntityPartBuilder(null));
    }

    @Test
    void aFileNameDefaultsTheMediaTypeToOctetStream() throws IOException {
        EntityPart part = builder().fileName("a.bin").content(TEXT).build();
        assertEquals(Optional.of("a.bin"), part.getFileName());
        assertEquals(MediaType.APPLICATION_OCTET_STREAM_TYPE, part.getMediaType());

        EntityPart json = builder().mediaType("application/json").fileName("a.json").content(TEXT).build();
        assertEquals(MediaType.APPLICATION_JSON_TYPE, json.getMediaType(), "an explicit media type stays");
    }

    @Test
    void headersAreAddedOneByOneOrAsAMap() throws IOException {
        var map = new MultivaluedHashMap<String, String>();
        map.add("X-B", "b");
        EntityPart part = builder().header("X-A", "1", "2").headers(map).content(TEXT).build();
        assertEquals(List.of("1", "2"), part.getHeaders().get("X-A"));
        assertEquals("b", part.getHeaders().getFirst("X-B"));
    }

    @Test
    void contentIsTakenFromEveryKindOfSource(@TempDir File dir) throws IOException {
        assertEquals(TEXT, builder().content(TEXT).build().getContent(String.class));
        assertArrayEquals(new byte[]{1, 2}, builder().content(new byte[]{1, 2}).build().getContent(byte[].class));
        assertEquals(TEXT, builder().content(stream(TEXT)).build().getContent(String.class));
        assertEquals(TEXT, builder().content((Object) stream(TEXT)).build().getContent(String.class));
        assertEquals("42", builder().content(42, Integer.class).build().getContent(String.class), "toString");
        assertEquals(TEXT, builder().content(TEXT, new GenericType<String>() { }).build().getContent(String.class));

        EntityPart upload = builder().content("up.txt", stream(TEXT)).build();
        assertEquals(Optional.of("up.txt"), upload.getFileName());

        File file = new File(dir, "data.txt");
        Files.writeString(file.toPath(), TEXT);
        EntityPart fromFile = builder().content(file).build();
        assertEquals(Optional.of("data.txt"), fromFile.getFileName());
        assertEquals(TEXT, fromFile.getContent(String.class));
        EntityPart named = builder().fileName("kept.txt").content(file).build();
        assertEquals(Optional.of("kept.txt"), named.getFileName());
    }

    @Test
    void unusableContentIsRejected(@TempDir File dir) {
        var builder = builder();
        assertThrows(IllegalArgumentException.class, () -> builder.content((InputStream) null));
        assertThrows(IllegalArgumentException.class, () -> builder.content((Object) null));
        assertThrows(IllegalArgumentException.class, () -> builder.content(new FailingStream()));
        Object failing = new FailingStream();
        assertThrows(IllegalArgumentException.class, () -> builder.content(failing));
        File missing = new File(dir, "missing.txt");
        assertThrows(IllegalArgumentException.class, () -> builder.content(missing));
    }

    @Test
    void contentConvertsToTheSupportedTypesOnlyOnce() throws IOException {
        assertEquals(TEXT, new String(textPart().getContent(InputStream.class).readAllBytes(), StandardCharsets.UTF_8));
        assertEquals(TEXT, textPart().getContent(CharSequence.class).toString());
        assertEquals(TEXT, textPart().getContent(new GenericType<String>() { }));
        EntityPart unsupported = textPart();
        assertThrows(IllegalArgumentException.class, () -> unsupported.getContent(Integer.class));

        EntityPart once = textPart();
        once.getContent();
        assertThrows(IllegalStateException.class, once::getContent);
        assertThrows(IllegalStateException.class, () -> once.getContent(String.class));
        var stringType = new GenericType<String>() { };
        assertThrows(IllegalStateException.class, () -> once.getContent(stringType));
    }

    @Test
    void aPartBuiltWithoutHeadersOrMediaTypeGetsDefaults() {
        var part = new CassiniEntityPart(NAME, null, null, null, null);
        assertEquals(MediaType.TEXT_PLAIN_TYPE, part.getMediaType());
        assertTrue(part.getHeaders().isEmpty());
        assertEquals(0, part.rawContent().length);
    }

    private static EntityPart textPart() throws IOException {
        return builder().content(TEXT).build();
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static final class FailingStream extends InputStream {
        @Override
        public int read() throws IOException {
            throw new IOException("broken");
        }
    }
}
