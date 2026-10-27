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

import jakarta.ws.rs.core.MediaType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import javax.xml.transform.stream.StreamSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * The built-in XML providers never resolve an external entity or fetch an external DTD: an XML
 * document can otherwise make the server read a local file (XXE) and send it back.
 */
class XmlExternalEntityTest {

    private static final String SECRET = "content-of-a-local-file";

    /** A document whose external DTD, a local file, defines {@code &leak;} as {@link #SECRET}. */
    private static String documentReading(Path dir) throws IOException {
        Path dtd = dir.resolve("leak.dtd");
        Files.writeString(dtd, "<!ENTITY leak \"" + SECRET + "\">");
        return "<?xml version=\"1.0\"?><!DOCTYPE r SYSTEM \"" + dtd.toUri() + "\"><r>&leak;</r>";
    }

    @Test
    void the_source_writer_does_not_fetch_an_external_dtd(@TempDir Path dir) throws Exception {
        var source = new StreamSource(new StringReader(documentReading(dir)));
        var out = new ByteArrayOutputStream();
        try {
            new MessageBodyRegistry.SourceWriter().writeTo(source, StreamSource.class, StreamSource.class,
                    new Annotation[0], MediaType.APPLICATION_XML_TYPE, null, out);
        } catch (IOException _) {
            // Refusing the document is fine: what matters is that the file never reaches the output.
        }
        assertFalse(out.toString(StandardCharsets.UTF_8).contains(SECRET), out.toString(StandardCharsets.UTF_8));
    }

    @Test
    void the_jaxb_element_reader_does_not_resolve_an_external_entity(@TempDir Path dir) throws Exception {
        byte[] doc = documentReading(dir).getBytes(StandardCharsets.UTF_8);
        var text = new StringBuilder();
        try {
            XMLStreamReader reader = MessageBodyRegistry.xmlStreamReader(new ByteArrayInputStream(doc));
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamReader.CHARACTERS) {
                    text.append(reader.getText());
                }
            }
        } catch (XMLStreamException _) {
            // Refusing the document is fine: what matters is that the file is never read.
        }
        assertFalse(text.toString().contains(SECRET), text.toString());
    }

    @Test
    void the_jaxb_element_reader_still_reads_a_plain_document() throws Exception {
        byte[] doc = "<r>hello</r>".getBytes(StandardCharsets.UTF_8);
        XMLStreamReader reader = MessageBodyRegistry.xmlStreamReader(new ByteArrayInputStream(doc));
        var text = new StringBuilder();
        while (reader.hasNext()) {
            if (reader.next() == XMLStreamReader.CHARACTERS) {
                text.append(reader.getText());
            }
        }
        assertEquals("hello", text.toString());
    }
}
