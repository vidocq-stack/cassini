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

import java.io.ByteArrayOutputStream;
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
        httpHeaders.putSingle("Content-Type", "multipart/form-data; boundary=" + boundary);
        write(parts, boundary, entityStream);
    }

    /** RFC 7578: serializes parts as multipart/form-data. */
    static void write(List<EntityPart> parts, String boundary, OutputStream out) throws IOException {
        byte[] eol = {'\r', '\n'};
        byte[] dashBoundary = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        for (EntityPart p : parts) {
            out.write(dashBoundary); out.write(eol);
            // Content-Disposition
            StringBuilder cd = new StringBuilder("Content-Disposition: form-data; name=\"")
                    .append(p.getName()).append('"');
            p.getFileName().ifPresent(fn -> cd.append("; filename=\"").append(fn).append('"'));
            out.write(cd.toString().getBytes(StandardCharsets.UTF_8));
            out.write(eol);
            // Content-Type
            MediaType mt = p.getMediaType();
            if (mt != null) {
                out.write(("Content-Type: " + mt.toString()).getBytes(StandardCharsets.UTF_8));
                out.write(eol);
            }
            // Other headers
            for (var e : p.getHeaders().entrySet()) {
                if ("Content-Disposition".equalsIgnoreCase(e.getKey())) continue;
                if ("Content-Type".equalsIgnoreCase(e.getKey())) continue;
                for (String v : e.getValue()) {
                    out.write((e.getKey() + ": " + v).getBytes(StandardCharsets.UTF_8));
                    out.write(eol);
                }
            }
            out.write(eol);
            // Content
            byte[] body = (p instanceof CassiniEntityPart cep)
                    ? cep.rawContent()
                    : p.getContent().readAllBytes();
            out.write(body);
            out.write(eol);
        }
        out.write(dashBoundary);
        out.write(new byte[]{'-', '-'});
        out.write(eol);
    }

    /** RFC 7578: parses a multipart/form-data body into parts. */
    static List<EntityPart> parse(byte[] body, String boundary) throws IOException {
        List<EntityPart> out = new ArrayList<>();
        byte[] delim = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        int idx = indexOf(body, delim, 0);
        if (idx < 0) return out;
        idx += delim.length;
        while (idx < body.length) {
            // skip --\r\n at end
            if (idx + 1 < body.length && body[idx] == '-' && body[idx + 1] == '-') break;
            // Skip CRLF after boundary
            while (idx < body.length && (body[idx] == '\r' || body[idx] == '\n')) idx++;
            // Headers
            MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
            String partName = null;
            String fileName = null;
            MediaType ct = null;
            while (true) {
                int eol = indexOf(body, new byte[]{'\r', '\n'}, idx);
                if (eol < 0) break;
                String line = new String(body, idx, eol - idx, StandardCharsets.UTF_8);
                idx = eol + 2;
                if (line.isEmpty()) break;
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String hname = line.substring(0, colon).trim();
                String hvalue = line.substring(colon + 1).trim();
                headers.add(hname, hvalue);
                if ("Content-Disposition".equalsIgnoreCase(hname)) {
                    partName = paramValue(hvalue, "name");
                    fileName = paramValue(hvalue, "filename");
                } else if ("Content-Type".equalsIgnoreCase(hname)) {
                    try { ct = MediaType.valueOf(hvalue); } catch (RuntimeException ignored) {}
                }
            }
            // Content up to the next boundary
            int next = indexOf(body, delim, idx);
            if (next < 0) break;
            int contentEnd = next;
            // Stripe trailing CRLF before boundary
            if (contentEnd >= 2 && body[contentEnd - 2] == '\r' && body[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }
            byte[] partContent = new byte[contentEnd - idx];
            System.arraycopy(body, idx, partContent, 0, partContent.length);
            if (partName != null) {
                out.add(new CassiniEntityPart(partName, fileName, ct, headers, partContent));
            }
            idx = next + delim.length;
        }
        return out;
    }

    private static String paramValue(String header, String name) {
        // simplistic : name="value" or name=value
        String search = name + "=";
        int i = header.indexOf(search);
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

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        outer: for (int i = from; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
