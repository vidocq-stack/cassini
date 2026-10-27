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
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The standard entity providers every implementation must ship (Jakarta REST 4.0 §4.2.4, "Standard Entity
 * Providers"), exercised through the registry lookup.
 */
class MessageBodyRegistryBuiltinsTest {

    private static final Annotation[] NONE = new Annotation[0];
    private static final MediaType TEXT_LATIN_1 = MediaType.TEXT_PLAIN_TYPE.withCharset("ISO-8859-1");
    private static final MediaType FORM = MediaType.APPLICATION_FORM_URLENCODED_TYPE;
    private static final String HELLO = "héllo";

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    private final MessageBodyRegistry registry = new MessageBodyRegistry();

    private byte[] write(Object entity, Class<?> type, MediaType mt) throws IOException {
        MessageBodyWriter<Object> w = registry.<Object>findWriter(type, type, NONE, mt).orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MessageBodyRegistry.writeTo(w, entity, type, type, NONE, mt, MessageBodyRegistry.outHeaders(), out);
        return out.toByteArray();
    }

    private <T> T read(Class<T> type, MediaType mt, byte[] body) throws IOException {
        MessageBodyReader<T> r = registry.findReader(type, type, NONE, mt).orElseThrow();
        return r.readFrom(type, type, NONE, mt, new MultivaluedHashMap<>(), new ByteArrayInputStream(body));
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void a_string_honours_the_charset_parameter() throws IOException {
        assertArrayEquals(HELLO.getBytes(StandardCharsets.ISO_8859_1), write(HELLO, String.class, TEXT_LATIN_1));
        assertEquals(HELLO, read(String.class, TEXT_LATIN_1, HELLO.getBytes(StandardCharsets.ISO_8859_1)));
    }

    @Test
    void an_unknown_or_missing_charset_falls_back_to_utf_8() throws IOException {
        MediaType bogus = MediaType.TEXT_PLAIN_TYPE.withCharset("no-such-charset");
        assertArrayEquals(utf8(HELLO), write(HELLO, String.class, bogus));
        MessageBodyReader<String> r = registry.findReader(String.class, String.class, NONE, null).orElseThrow();
        assertEquals(HELLO, r.readFrom(String.class, String.class, NONE, null, new MultivaluedHashMap<>(),
                new ByteArrayInputStream(utf8(HELLO))));
    }

    @Test
    void bytes_pass_through_unchanged() throws IOException {
        byte[] body = {0, 1, 2, (byte) 0xFF};
        assertArrayEquals(body, write(body, byte[].class, MediaType.APPLICATION_OCTET_STREAM_TYPE));
        assertArrayEquals(body, read(byte[].class, MediaType.APPLICATION_OCTET_STREAM_TYPE, body));
    }

    @Test
    void streams_are_copied() throws IOException {
        byte[] body = utf8("stream");
        assertArrayEquals(body, write(new ByteArrayInputStream(body), InputStream.class,
                MediaType.APPLICATION_OCTET_STREAM_TYPE));
        try (InputStream in = read(InputStream.class, MediaType.APPLICATION_OCTET_STREAM_TYPE, body)) {
            assertArrayEquals(body, in.readAllBytes());
        }
    }

    @Test
    void readers_are_decoded_and_encoded_with_the_charset() throws IOException {
        assertArrayEquals(HELLO.getBytes(StandardCharsets.ISO_8859_1),
                write(new StringReader(HELLO), Reader.class, TEXT_LATIN_1));
        try (Reader r = read(Reader.class, TEXT_LATIN_1, HELLO.getBytes(StandardCharsets.ISO_8859_1))) {
            char[] buf = new char[16];
            assertEquals(HELLO, new String(buf, 0, r.read(buf)));
        }
    }

    @Test
    void a_streaming_output_writes_itself() throws IOException {
        StreamingOutput so = out -> out.write(utf8("streamed"));
        assertArrayEquals(utf8("streamed"), write(so, StreamingOutput.class, MediaType.TEXT_PLAIN_TYPE));
    }

    @Test
    void a_file_is_copied(@TempDir Path dir) throws IOException {
        Path file = Files.write(dir.resolve("f.bin"), utf8("file body"));
        assertArrayEquals(utf8("file body"), write(file.toFile(), File.class, MediaType.APPLICATION_OCTET_STREAM_TYPE));
    }

    @Test
    void a_form_is_url_encoded_and_decoded() throws IOException {
        MultivaluedMap<String, String> form = new MultivaluedHashMap<>();
        form.add("a", "1");
        form.add("a", "x y");
        form.add("b&", "é");
        assertEquals("a=1&a=x+y&b%26=%C3%A9",
                new String(write(form, MultivaluedMap.class, FORM), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        MultivaluedMap<String, String> back = read(MultivaluedMap.class, FORM, utf8("a=1&a=x+y&b%26=%C3%A9"));
        assertEquals(Map.of("a", List.of("1", "x y"), "b&", List.of("é")), back);
    }

    @Test
    void the_form_reader_only_takes_the_form_media_type() {
        MessageBodyReader<?> formReader = new MessageBodyRegistry.FormUrlEncodedReader();
        assertTrue(formReader.isReadable(MultivaluedMap.class, MultivaluedMap.class, NONE, null));
        assertTrue(formReader.isReadable(MultivaluedMap.class, MultivaluedMap.class, NONE,
                MediaType.valueOf("Application/X-WWW-Form-Urlencoded")));
        assertFalse(formReader.isReadable(MultivaluedMap.class, MultivaluedMap.class, NONE,
                MediaType.TEXT_PLAIN_TYPE));
    }

    @Test
    void an_xml_source_is_copied_and_read_as_a_stream_source() throws IOException {
        Source in = new StreamSource(new StringReader("<a>1</a>"));
        String xml = new String(write(in, Source.class, MediaType.APPLICATION_XML_TYPE), StandardCharsets.UTF_8);
        assertTrue(xml.endsWith("<a>1</a>"), xml);
        assertInstanceOf(StreamSource.class, read(Source.class, MediaType.TEXT_XML_TYPE, utf8("<a/>")));
    }

    @Test
    void an_invalid_xml_source_fails_with_an_io_exception() {
        Source broken = new StreamSource(new StringReader("<a>"));
        assertThrows(IOException.class, () -> write(broken, Source.class, MediaType.APPLICATION_XML_TYPE));
    }

    record Thing(String name) { }

    @Test
    void any_other_type_falls_back_to_its_string_form() throws IOException {
        assertArrayEquals(utf8("Thing[name=x]"), write(new Thing("x"), Thing.class, MediaType.TEXT_PLAIN_TYPE));
    }

    @Test
    void primitives_and_their_wrappers_are_plain_text() throws IOException {
        // §4.2.4: java.lang.Boolean, java.lang.Character, java.lang.Number and primitives as text/plain.
        MediaType text = MediaType.TEXT_PLAIN_TYPE;
        assertArrayEquals(utf8("42"), write(42, Integer.class, text));
        assertEquals(Integer.valueOf(42), read(int.class, text, utf8("42")));
        assertEquals(Boolean.TRUE, read(Boolean.class, text, utf8("TRUE")));
        assertEquals(Character.valueOf('z'), read(Character.class, text, utf8("zz")));
        assertEquals(Byte.valueOf((byte) 1), read(Byte.class, text, utf8("1")));
        assertEquals(Short.valueOf((short) 2), read(Short.class, text, utf8("2")));
        assertEquals(Long.valueOf(3L), read(Long.class, text, utf8("3")));
        assertEquals(Float.valueOf(1.5f), read(Float.class, text, utf8("1.5")));
        assertEquals(Double.valueOf(2.5d), read(double.class, text, utf8("2.5")));
        assertEquals(new BigDecimal("1.25"), read(BigDecimal.class, text, utf8("1.25")));
        assertEquals(new BigDecimal("7"), read(Number.class, text, utf8("7")));
        assertEquals(new BigInteger("123456789012345678901234567890"),
                read(BigInteger.class, text, utf8("123456789012345678901234567890")));
    }

    @Test
    void an_unparsable_primitive_body_is_a_bad_request() {
        // §4.2.4: a standard provider that cannot map the body to the Java type raises a 400.
        MediaType text = MediaType.TEXT_PLAIN_TYPE;
        assertThrows(BadRequestException.class, () -> read(Integer.class, text, utf8("x")));
        assertThrows(BadRequestException.class, () -> read(Boolean.class, text, utf8("yes")));
        assertThrows(BadRequestException.class, () -> read(char.class, text, new byte[0]));
    }

    @Test
    void exchange_headers_are_adapted_to_a_multivalued_map() {
        MultivaluedMap<String, String> m = MessageBodyRegistry.adaptExchangeHeaders(Map.of("A", List.of("1", "2")));
        assertEquals(List.of("1", "2"), m.get("A"));
        assertTrue(MessageBodyRegistry.adaptExchangeHeaders(null).isEmpty());
    }

    @Test
    void primitive_types_are_boxed_for_provider_selection() {
        assertEquals(Boolean.class, MessageBodyRegistry.box(boolean.class));
        assertEquals(Byte.class, MessageBodyRegistry.box(byte.class));
        assertEquals(Short.class, MessageBodyRegistry.box(short.class));
        assertEquals(Long.class, MessageBodyRegistry.box(long.class));
        assertEquals(Float.class, MessageBodyRegistry.box(float.class));
        assertEquals(Character.class, MessageBodyRegistry.box(char.class));
        assertEquals(String.class, MessageBodyRegistry.box(String.class));
    }
}
