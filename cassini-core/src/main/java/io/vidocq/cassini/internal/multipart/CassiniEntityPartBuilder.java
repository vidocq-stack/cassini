package io.vidocq.cassini.internal.multipart;

import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** §3.5.4 : implémentation {@link EntityPart.Builder}. */
public final class CassiniEntityPartBuilder implements EntityPart.Builder {

    private final String name;
    private String fileName;
    private MediaType mediaType = MediaType.TEXT_PLAIN_TYPE;
    private final MultivaluedMap<String, String> headers = new MultivaluedHashMap<>();
    private byte[] content;

    public CassiniEntityPartBuilder(String name) {
        if (name == null) throw new IllegalArgumentException("name must not be null");
        this.name = name;
    }

    @Override public EntityPart.Builder mediaType(MediaType mt) { this.mediaType = mt; return this; }
    @Override public EntityPart.Builder mediaType(String mt) {
        this.mediaType = MediaType.valueOf(mt); return this;
    }
    @Override public EntityPart.Builder header(String n, String... v) {
        for (String s : v) headers.add(n, s);
        return this;
    }
    @Override public EntityPart.Builder headers(MultivaluedMap<String, String> h) {
        if (h != null) headers.putAll(h);
        return this;
    }
    @Override public EntityPart.Builder fileName(String fn) {
        this.fileName = fn;
        if (mediaType == null || mediaType.equals(MediaType.TEXT_PLAIN_TYPE)) {
            mediaType = MediaType.APPLICATION_OCTET_STREAM_TYPE;
        }
        return this;
    }

    @Override public EntityPart.Builder content(InputStream is) throws IllegalArgumentException {
        if (is == null) throw new IllegalArgumentException("content stream is null");
        try { this.content = is.readAllBytes(); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
        return this;
    }

    @Override public EntityPart.Builder content(String fileName, InputStream is) throws IllegalArgumentException {
        return fileName(fileName).content(is);
    }

    @Override public <T> EntityPart.Builder content(T content, Class<? extends T> type) throws IllegalArgumentException {
        return contentImpl(content);
    }

    @Override public <T> EntityPart.Builder content(T content, GenericType<T> type) throws IllegalArgumentException {
        return contentImpl(content);
    }

    @Override public EntityPart.Builder content(Object content) throws IllegalArgumentException {
        return contentImpl(content);
    }

    private EntityPart.Builder contentImpl(Object value) {
        if (value == null) throw new IllegalArgumentException("content is null");
        if (value instanceof byte[] b) { this.content = b; return this; }
        if (value instanceof String s) { this.content = s.getBytes(StandardCharsets.UTF_8); return this; }
        if (value instanceof InputStream is) {
            try { this.content = is.readAllBytes(); }
            catch (IOException e) { throw new IllegalArgumentException(e); }
            return this;
        }
        if (value instanceof java.io.File f) {
            try (InputStream fis = new java.io.FileInputStream(f)) {
                this.content = fis.readAllBytes();
                if (fileName == null) fileName = f.getName();
            } catch (IOException e) { throw new IllegalArgumentException(e); }
            return this;
        }
        // Fallback : toString → bytes
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try { bos.write(value.toString().getBytes(StandardCharsets.UTF_8)); }
        catch (IOException e) { throw new IllegalArgumentException(e); }
        this.content = bos.toByteArray();
        return this;
    }

    @Override public EntityPart build() {
        return new CassiniEntityPart(name, fileName, mediaType, headers, content == null ? new byte[0] : content);
    }
}
