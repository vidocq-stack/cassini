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

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * §3.5.4: MBR/MBW for {@code List<EntityPart>} over {@code multipart/form-data}.
 *
 * <p>Minimal RFC 7578 parser: for each part, reads the {@code Content-Disposition}
 * and {@code Content-Type} headers, then the content up to the next boundary.</p>
 */
@Consumes("multipart/form-data")
@Produces("multipart/form-data")
public final class MultipartFormDataProvider
        implements MessageBodyReader<List<EntityPart>>, MessageBodyWriter<List<EntityPart>> {

    private static final String CONTENT_TYPE = "Content-Type";
    private static final String CONTENT_DISPOSITION = "Content-Disposition";
    private static final byte[] CRLF = {'\r', '\n'};

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return List.class.isAssignableFrom(type)
                && (isListOfEntityPart(genericType) || isMultipart(mediaType));
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return List.class.isAssignableFrom(type)
                && (isListOfEntityPart(genericType) || isMultipart(mediaType));
    }

    private static boolean isMultipart(MediaType mt) {
        return mt != null && "multipart".equalsIgnoreCase(mt.getType())
                && "form-data".equalsIgnoreCase(mt.getSubtype());
    }

    private static boolean isListOfEntityPart(Type genericType) {
        if (!(genericType instanceof ParameterizedType pt)) return false;
        if (pt.getActualTypeArguments().length != 1) return false;
        Type a = pt.getActualTypeArguments()[0];
        return a == EntityPart.class
                || (a instanceof Class<?> c && EntityPart.class.isAssignableFrom(c));
    }

    @Override
    public List<EntityPart> readFrom(Class<List<EntityPart>> type, Type genericType,
                                     Annotation[] annotations, MediaType mediaType,
                                     MultivaluedMap<String, String> httpHeaders, InputStream entityStream)
            throws IOException, WebApplicationException {
        String boundary = mediaType.getParameters().get("boundary");
        if (boundary == null || boundary.isEmpty())
            throw new WebApplicationException("multipart/form-data missing boundary parameter", 400);
        return parse(entityStream.readAllBytes(), boundary);
    }

    @Override
    public void writeTo(List<EntityPart> parts, Class<?> type, Type genericType,
                        Annotation[] annotations, MediaType mediaType,
                        MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream)
            throws IOException, WebApplicationException {
        String boundary = mediaType.getParameters().get("boundary");
        if (boundary == null || boundary.isEmpty()) {
            boundary = "Boundary_" + UUID.randomUUID().toString().replace("-", "");
        }
        // §3.5.4: force the wire Content-Type to include the boundary,
        // regardless of the initial mediaType value — without it, the
        // receiver cannot parse the body.
        httpHeaders.putSingle(CONTENT_TYPE, "multipart/form-data; boundary=" + boundary);
        write(parts, boundary, entityStream);
    }

    /** RFC 7578: serializes parts as multipart/form-data. */
    static void write(List<EntityPart> parts, String boundary, OutputStream out) throws IOException {
        byte[] dashBoundary = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        for (EntityPart p : parts) {
            out.write(dashBoundary); out.write(CRLF);
            writePartHeaders(p, out);
            out.write(CRLF);
            // Content
            byte[] body = (p instanceof CassiniEntityPart cep)
                    ? cep.rawContent()
                    : p.getContent().readAllBytes();
            out.write(body);
            out.write(CRLF);
        }
        out.write(dashBoundary);
        out.write(new byte[]{'-', '-'});
        out.write(CRLF);
    }

    /** Content-Disposition, Content-Type, then the other headers of a part, one per line. */
    private static void writePartHeaders(EntityPart p, OutputStream out) throws IOException {
        StringBuilder cd = new StringBuilder(CONTENT_DISPOSITION + ": form-data; name=\"")
                .append(p.getName()).append('"');
        p.getFileName().ifPresent(fn -> cd.append("; filename=\"").append(fn).append('"'));
        out.write(cd.toString().getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
        MediaType mt = p.getMediaType();
        if (mt != null) {
            out.write((CONTENT_TYPE + ": " + mt.toString()).getBytes(StandardCharsets.UTF_8));
            out.write(CRLF);
        }
        for (var e : p.getHeaders().entrySet()) {
            if (CONTENT_DISPOSITION.equalsIgnoreCase(e.getKey()) || CONTENT_TYPE.equalsIgnoreCase(e.getKey())) continue;
            for (String v : e.getValue()) {
                out.write((e.getKey() + ": " + v).getBytes(StandardCharsets.UTF_8));
                out.write(CRLF);
            }
        }
    }

    /** RFC 7578: parses a multipart/form-data body into parts. */
    static List<EntityPart> parse(byte[] body, String boundary) {
        List<EntityPart> out = new ArrayList<>();
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(body, delim, 0);
        if (idx < 0) return out;
        idx += delim.length;
        // the close delimiter is followed by "--"
        while (idx >= 0 && idx < body.length
                && !(idx + 1 < body.length && body[idx] == '-' && body[idx + 1] == '-')) {
            // Skip CRLF after boundary
            while (idx < body.length && (body[idx] == '\r' || body[idx] == '\n')) idx++;
            idx = parsePart(body, idx, delim, out);
        }
        return out;
    }

    /** Parses the part whose headers start at {@code idx} into {@code out} (a part without a
     *  name is dropped); returns the index after the delimiter that ends it, or -1 when the
     *  body ends before that delimiter. */
    private static int parsePart(byte[] body, int idx, byte[] delim, List<EntityPart> out) {
        PartHeaders head = new PartHeaders();
        int contentStart = head.read(body, idx);
        // Content up to the next boundary
        int next = indexOf(body, delim, contentStart);
        if (next < 0) return -1;
        int contentEnd = next;
        // Stripe trailing CRLF before boundary
        if (contentEnd >= 2 && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
            contentEnd -= 2;
        }
        byte[] partContent = new byte[contentEnd - contentStart];
        System.arraycopy(body, contentStart, partContent, 0, partContent.length);
        if (head.name != null) {
            out.add(new CassiniEntityPart(head.name, head.fileName, head.contentType, head.headers, partContent));
        }
        return next + delim.length;
    }

    /** The headers of one part, with its name, file name and media type. */
    private static final class PartHeaders {
        private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
        private String name;
        private String fileName;
        private MediaType contentType;

        /** Reads the header lines from {@code idx} up to the empty line; returns the index of the content. */
        int read(byte[] body, int idx) {
            for (int eol = indexOf(body, CRLF, idx); eol >= 0; eol = indexOf(body, CRLF, idx)) {
                String line = new String(body, idx, eol - idx, StandardCharsets.UTF_8);
                idx = eol + 2;
                if (line.isEmpty()) break;
                add(line);
            }
            return idx;
        }

        private void add(String line) {
            int colon = line.indexOf(':');
            if (colon < 0) return;
            String hname = line.substring(0, colon).trim();
            String hvalue = line.substring(colon + 1).trim();
            headers.add(hname, hvalue);
            if (CONTENT_DISPOSITION.equalsIgnoreCase(hname)) {
                name = paramValue(hvalue, "name");
                fileName = paramValue(hvalue, "filename");
            } else if (CONTENT_TYPE.equalsIgnoreCase(hname)) {
                try {
                    contentType = MediaType.valueOf(hvalue);
                } catch (RuntimeException _) {
                    // malformed: the part keeps the media type it had, if any
                }
            }
        }

        private static String paramValue(String header, String name) {
            // simplistic : name="value" or name=value
            String search = name + "=";
            int i = parameterStart(header, search);
            if (i < 0) return null;
            i += search.length();
            if (i < header.length() && header.charAt(i) == '"') {
                int end = header.indexOf('"', i + 1);
                return end < 0 ? null : header.substring(i + 1, end);
            }
            int end = header.indexOf(';', i);
            if (end < 0) end = header.length();
            return header.substring(i, end).trim();
        }

        /**
         * Index of the parameter {@code search} ({@code name=}) in a header value, or -1. A match
         * must start the value or follow a {@code ;} (whitespace allowed): {@code name=} inside
         * {@code filename=} is not the {@code name} parameter (CASSINI-026).
         */
        private static int parameterStart(String header, String search) {
            for (int i = header.indexOf(search); i >= 0; i = header.indexOf(search, i + 1)) {
                int before = i - 1;
                while (before >= 0 && Character.isWhitespace(header.charAt(before))) before--;
                if (before < 0 || header.charAt(before) == ';') return i;
            }
            return -1;
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int i = from; i <= haystack.length - needle.length; i++) {
            if (java.util.Arrays.equals(haystack, i, i + needle.length, needle, 0, needle.length)) return i;
        }
        return -1;
    }
}
