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
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §3.5.4: the {@code multipart/form-data} reader and writer of {@code List<EntityPart>} (RFC 7578).
 */
class MultipartFormDataProviderTest {

    private static final String BOUNDARY = "XyZ";
    private static final Annotation[] NONE = new Annotation[0];
    private static final Type LIST_OF_PARTS = new GenericType<List<EntityPart>>() { }.getType();
    private static final MediaType FORM_DATA = MediaType.MULTIPART_FORM_DATA_TYPE;
    private static final MediaType FORM_DATA_WITH_BOUNDARY =
            new MediaType("multipart", "form-data", Map.of("boundary", BOUNDARY));
    private static final String CRLF = "\r\n";

    private final MultipartFormDataProvider provider = new MultipartFormDataProvider();

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    // ---- isReadable / isWriteable ----

    @Test
    void aListOfEntityPartsIsHandledWhateverTheMediaType() {
        assertTrue(provider.isReadable(List.class, LIST_OF_PARTS, NONE, MediaType.TEXT_PLAIN_TYPE));
        assertTrue(provider.isWriteable(List.class, LIST_OF_PARTS, NONE, null));
        Type listOfImpl = new GenericType<List<CassiniEntityPart>>() { }.getType();
        assertTrue(provider.isReadable(List.class, listOfImpl, NONE, null), "a list of an EntityPart subtype");
    }

    @Test
    void aRawListIsHandledOnlyAsMultipartFormData() {
        assertTrue(provider.isReadable(List.class, List.class, NONE, FORM_DATA));
        assertTrue(provider.isWriteable(List.class, List.class, NONE, FORM_DATA));
        assertFalse(provider.isReadable(List.class, List.class, NONE, null));
        assertFalse(provider.isReadable(List.class, List.class, NONE, MediaType.valueOf("multipart/mixed")));
        assertFalse(provider.isWriteable(List.class, new GenericType<List<String>>() { }.getType(), NONE,
                MediaType.APPLICATION_JSON_TYPE));
        assertFalse(provider.isWriteable(List.class, new GenericType<Map<String, EntityPart>>() { }.getType(), NONE,
                MediaType.APPLICATION_JSON_TYPE), "two type arguments");
    }

    @Test
    void aTypeThatIsNotAListIsNeverHandled() {
        assertFalse(provider.isReadable(String.class, String.class, NONE, FORM_DATA));
        assertFalse(provider.isWriteable(String.class, String.class, NONE, FORM_DATA));
    }

    // ---- reading ----

    @Test
    void readsEveryNamedPartWithItsHeadersFileNameAndMediaType() throws IOException {
        String body = "preamble to ignore" + CRLF
                + "--XyZ" + CRLF
                + "Content-Disposition: form-data; name=\"title\"" + CRLF
                + CRLF
                + "Hello" + CRLF
                + "--XyZ" + CRLF
                + "Content-Disposition: form-data; name=\"doc\"; filename=\"a.json\"" + CRLF
                + "Content-Type: application/json" + CRLF
                + "X-Extra: 1" + CRLF
                + "a header line without colon" + CRLF
                + CRLF
                + "{\"a\":1}" + CRLF
                + "--XyZ" + CRLF
                + "Content-Disposition: form-data; name=bare; other=x" + CRLF
                + "Content-Type: not a media type" + CRLF
                + CRLF
                + "unquoted" + CRLF
                + "--XyZ" + CRLF
                + "Content-Disposition: form-data" + CRLF
                + CRLF
                + "anonymous parts are dropped" + CRLF
                + "--XyZ--" + CRLF;

        List<EntityPart> parts = read(body);

        assertEquals(3, parts.size());
        EntityPart title = parts.get(0);
        assertEquals("title", title.getName());
        assertEquals(Optional.empty(), title.getFileName());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, title.getMediaType());
        assertEquals("Hello", title.getContent(String.class));

        EntityPart doc = parts.get(1);
        assertEquals("doc", doc.getName());
        assertEquals(Optional.of("a.json"), doc.getFileName());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, doc.getMediaType());
        assertEquals("1", doc.getHeaders().getFirst("X-Extra"));
        assertEquals("{\"a\":1}", new String(doc.getContent().readAllBytes(), StandardCharsets.UTF_8));

        EntityPart bare = parts.get(2);
        assertEquals("bare", bare.getName());
        assertEquals(MediaType.TEXT_PLAIN_TYPE, bare.getMediaType(), "an unparseable Content-Type is ignored");
        assertEquals("unquoted", bare.getContent(String.class));
    }

    /** CASSINI-026: RFC 7578 does not order the parameters; "name=" must not match inside "filename=". */
    @Test
    void theNameIsFoundWhenTheFileNameComesFirst() throws IOException {
        String body = "--XyZ" + CRLF
                + "Content-Disposition: form-data; filename=\"photo.png\"; name=\"upload\"" + CRLF
                + CRLF
                + "bytes" + CRLF
                + "--XyZ--" + CRLF;

        EntityPart part = read(body).get(0);

        assertEquals("upload", part.getName());
        assertEquals(Optional.of("photo.png"), part.getFileName());
    }

    @Test
    void aBodyWithoutTheBoundaryOrWithATruncatedPartHasNoPart() throws IOException {
        assertTrue(read("no boundary here").isEmpty());
        assertTrue(read("--XyZ" + CRLF + "Content-Disposition: form-data; name=\"a\"" + CRLF + CRLF + "never closed")
                .isEmpty());
        assertTrue(read("--XyZ" + CRLF + "Content-Disposition: form-data; name=\"a\"").isEmpty(),
                "headers never ended");
    }

    @Test
    void aMissingBoundaryParameterIsABadRequest() {
        var in = new ByteArrayInputStream(new byte[0]);
        var noBoundary = assertThrows(WebApplicationException.class, () -> readFrom(FORM_DATA, in));
        assertEquals(400, noBoundary.getResponse().getStatus());
        var empty = new MediaType("multipart", "form-data", Map.of("boundary", ""));
        var emptyBoundary = assertThrows(WebApplicationException.class, () -> readFrom(empty, in));
        assertEquals(400, emptyBoundary.getResponse().getStatus());
    }

    // ---- writing ----

    @Test
    void writesPartsThatReadBackIdentically() throws IOException {
        List<EntityPart> parts = List.of(
                new CassiniEntityPartBuilder("title").content("Hello").build(),
                new CassiniEntityPartBuilder("doc").fileName("a.json").mediaType(MediaType.APPLICATION_JSON_TYPE)
                        .header("X-Extra", "1", "2").header("Content-Type", "ignored/duplicate")
                        .content("{}").build(),
                new ForeignPart("foreign", "from another implementation"));
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        var out = new ByteArrayOutputStream();

        provider.writeTo(parts, List.class, LIST_OF_PARTS, NONE, FORM_DATA_WITH_BOUNDARY, headers, out);

        assertEquals("multipart/form-data; boundary=" + BOUNDARY, headers.getFirst("Content-Type"));
        String wire = out.toString(StandardCharsets.UTF_8);
        assertTrue(wire.endsWith("--XyZ--" + CRLF), wire);
        assertFalse(wire.contains("ignored/duplicate"), "Content-Type is written once, from the media type");
        List<EntityPart> back = MultipartFormDataProvider.parse(out.toByteArray(), BOUNDARY);
        assertEquals(3, back.size());
        assertEquals("Hello", back.get(0).getContent(String.class));
        assertEquals(Optional.of("a.json"), back.get(1).getFileName());
        assertEquals(MediaType.APPLICATION_JSON_TYPE, back.get(1).getMediaType());
        assertEquals(List.of("1", "2"), back.get(1).getHeaders().get("X-Extra"));
        assertEquals("from another implementation", back.get(2).getContent(String.class));
    }

    @Test
    void aWriterWithoutBoundaryInventsOneAndAnnouncesIt() throws IOException {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        var out = new ByteArrayOutputStream();

        provider.writeTo(List.of(new CassiniEntityPartBuilder("a").content("x").build()), List.class,
                LIST_OF_PARTS, NONE, FORM_DATA, headers, out);

        String contentType = (String) headers.getFirst("Content-Type");
        String boundary = MediaType.valueOf(contentType).getParameters().get("boundary");
        assertTrue(boundary.startsWith("Boundary_"), contentType);
        assertEquals("x", MultipartFormDataProvider.parse(out.toByteArray(), boundary).get(0).getContent(String.class));
    }

    private List<EntityPart> read(String body) throws IOException {
        return readFrom(FORM_DATA_WITH_BOUNDARY, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<EntityPart> readFrom(MediaType mediaType, InputStream in) throws IOException {
        return provider.readFrom((Class) List.class, LIST_OF_PARTS, NONE, mediaType, new MultivaluedHashMap<>(), in);
    }

    /** An {@link EntityPart} that is not Cassini's: its content is read through {@link #getContent()}. */
    private record ForeignPart(String name, String text) implements EntityPart {
        @Override public String getName() { return name; }
        @Override public Optional<String> getFileName() { return Optional.empty(); }
        @Override public InputStream getContent() {
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        @Override public <T> T getContent(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public <T> T getContent(GenericType<T> type) { throw new UnsupportedOperationException(); }
        @Override public MultivaluedMap<String, String> getHeaders() { return new MultivaluedHashMap<>(); }
        @Override public MediaType getMediaType() { return null; }
    }
}
