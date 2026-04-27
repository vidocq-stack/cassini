package io.vidocq.cassini.internal;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.StreamingOutput;
import io.vidocq.cassini.internal.FormDecoder;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.util.Comparator;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Registre des {@link MessageBodyReader} / {@link MessageBodyWriter}
 * utilisés pour sérialiser entités requête/réponse (§4.2).
 *
 * <p>M2c : providers built-in pour {@code String}, {@code byte[]},
 * {@code InputStream}, {@code Reader}, {@link StreamingOutput}, {@link File}.
 * Le scan de {@code @Provider} utilisateur arrive en M2f.</p>
 */
public final class MessageBodyRegistry {

    private final List<MessageBodyReader<?>> readers = new ArrayList<>();
    private final List<MessageBodyWriter<?>> writers = new ArrayList<>();

    public MessageBodyRegistry() {
        registerBuiltins();
    }

    public void addReader(MessageBodyReader<?> r) {
        jakarta.ws.rs.ConstrainedTo ct = r.getClass().getAnnotation(jakarta.ws.rs.ConstrainedTo.class);
        if (ct != null && ct.value() == jakarta.ws.rs.RuntimeType.CLIENT) return;
        readers.add(0, r);
    }
    public void addWriter(MessageBodyWriter<?> w) {
        jakarta.ws.rs.ConstrainedTo ct = w.getClass().getAnnotation(jakarta.ws.rs.ConstrainedTo.class);
        if (ct != null && ct.value() == jakarta.ws.rs.RuntimeType.CLIENT) return;
        writers.add(0, w);
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<MessageBodyReader<T>> findReader(Class<T> type, Type genericType,
                                                         Annotation[] anns, MediaType mt) {
        Class<?> boxed = box(type);
        List<MessageBodyReader<?>> candidates = new ArrayList<>();
        for (MessageBodyReader<?> r : readers) {
            // §4.2.1 : pré-filtrer par le type générique T du MBR.
            Class<?> rType = resolveProviderType(r.getClass(), MessageBodyReader.class);
            if (rType != null && rType != Object.class && !rType.isAssignableFrom(boxed)) continue;
            if (r.isReadable(type, genericType, anns, mt)) candidates.add(r);
        }
        if (candidates.isEmpty()) return Optional.empty();
        // §4.2.4 step 1 : trier par spécificité du media type @Consumes.
        // Sort stable → en cas d'égalité, les providers en tête de liste
        // (user-provided, insérés à l'index 0) gagnent sur les built-in (en fin).
        candidates.sort(Comparator.comparingInt(
                (MessageBodyReader<?> r) -> consumesSpecificity(r.getClass().getAnnotation(Consumes.class), mt)
        ).reversed());
        return Optional.of((MessageBodyReader<T>) candidates.get(0));
    }

    @SuppressWarnings("unchecked")
    public <T> Optional<MessageBodyWriter<T>> findWriter(Class<?> type, Type genericType,
                                                         Annotation[] anns, MediaType mt) {
        Class<?> boxed = box(type);
        List<MessageBodyWriter<?>> candidates = new ArrayList<>();
        for (MessageBodyWriter<?> w : writers) {
            Class<?> wType = resolveProviderType(w.getClass(), MessageBodyWriter.class);
            if (wType != null && wType != Object.class && !wType.isAssignableFrom(boxed)) continue;
            if (w.isWriteable(type, genericType, anns, mt)) candidates.add(w);
        }
        if (candidates.isEmpty()) return Optional.empty();
        candidates.sort(Comparator.comparingInt(
                (MessageBodyWriter<?> w) -> producesSpecificity(w.getClass().getAnnotation(Produces.class), mt)
        ).reversed());
        return Optional.of((MessageBodyWriter<T>) candidates.get(0));
    }

    // §4.2.4 step 1 : spécificité du match entre @Consumes déclaré et le media type demandé.
    // 0 = wildcard (ou pas d'annotation), 1 = type/*, 2 = type/subtype exact.
    private static int consumesSpecificity(Consumes consumes, MediaType requested) {
        if (consumes == null || requested == null) return 0;
        int best = 0;
        for (String s : consumes.value()) {
            MediaType declared = MediaType.valueOf(s);
            if (!declared.isCompatible(requested)) continue;
            if (!declared.isWildcardType() && !declared.isWildcardSubtype()) best = Math.max(best, 2);
            else if (!declared.isWildcardType()) best = Math.max(best, 1);
        }
        return best;
    }

    private static int producesSpecificity(Produces produces, MediaType requested) {
        if (produces == null || requested == null) return 0;
        int best = 0;
        for (String s : produces.value()) {
            MediaType declared = MediaType.valueOf(s);
            if (!declared.isCompatible(requested)) continue;
            if (!declared.isWildcardType() && !declared.isWildcardSubtype()) best = Math.max(best, 2);
            else if (!declared.isWildcardType()) best = Math.max(best, 1);
        }
        return best;
    }

    private void registerBuiltins() {
        // Built-in providers : ajoutés à la FIN (les providers user, ajoutés
        // via addReader/addWriter, sont à l'index 0 et gagnent à spécificité égale).
        writers.add(new ByteArrayWriter());
        writers.add(new StringWriter());
        writers.add(new StreamingOutputWriter());
        writers.add(new InputStreamWriter());
        writers.add(new FileWriter());
        writers.add(new ReaderWriter());
        writers.add(new SourceWriter());
        if (activationAvailable()) writers.add(new DataSourceWriter());
        if (jaxbAvailable()) writers.add(new JaxbWriter());
        writers.add(new FormUrlEncodedWriter());
        writers.add(new PrimitiveWriter());
        writers.add(new FallbackToStringWriter());

        readers.add(new ByteArrayReader());
        readers.add(new StringReader());
        readers.add(new InputStreamReaderMBR());
        readers.add(new ReaderReaderMBR());
        readers.add(new FileReader());
        readers.add(new SourceReader());
        if (activationAvailable()) readers.add(new DataSourceReader());
        if (jaxbAvailable()) readers.add(new JaxbReader());
        readers.add(new FormUrlEncodedReader());
        readers.add(new PrimitiveReader());
    }

    /** JAXB est optionnel : si {@code jakarta.xml.bind} n'est pas sur le
     *  classpath, on n'enregistre pas les MBR/MBW associés. */
    private static boolean jaxbAvailable() {
        return classPresent("jakarta.xml.bind.JAXBContext");
    }

    /** Jakarta Activation est optionnel : si {@code jakarta.activation} n'est
     *  pas sur le classpath, on n'enregistre pas les MBR/MBW {@code DataSource}. */
    private static boolean activationAvailable() {
        return classPresent("jakarta.activation.DataSource");
    }

    private static boolean classPresent(String fqcn) {
        try {
            Class.forName(fqcn, false, MessageBodyRegistry.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static Charset charset(MediaType mt) {
        if (mt == null) return StandardCharsets.UTF_8;
        String cs = mt.getParameters().get("charset");
        try {
            return cs == null ? StandardCharsets.UTF_8 : Charset.forName(cs);
        } catch (Exception e) {
            return StandardCharsets.UTF_8;
        }
    }

    // ---- String ----
    static final class StringWriter implements MessageBodyWriter<String> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return String.class.isAssignableFrom(t); }
        @Override public void writeTo(String v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            s.write(v.getBytes(charset(mt)));
        }
    }
    static final class StringReader implements MessageBodyReader<String> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return String.class == t; }
        @Override public String readFrom(Class<String> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) throws IOException {
            return new String(in.readAllBytes(), charset(mt));
        }
    }

    // ---- byte[] ----
    static final class ByteArrayWriter implements MessageBodyWriter<byte[]> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return byte[].class == t; }
        @Override public void writeTo(byte[] v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            s.write(v);
        }
    }
    static final class ByteArrayReader implements MessageBodyReader<byte[]> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return byte[].class == t; }
        @Override public byte[] readFrom(Class<byte[]> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) throws IOException {
            return in.readAllBytes();
        }
    }

    // ---- InputStream ----
    static final class InputStreamWriter implements MessageBodyWriter<InputStream> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return InputStream.class.isAssignableFrom(t); }
        @Override public void writeTo(InputStream v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            v.transferTo(s);
        }
    }
    static final class InputStreamReaderMBR implements MessageBodyReader<InputStream> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return InputStream.class == t; }
        @Override public InputStream readFrom(Class<InputStream> t, Type gt, Annotation[] a, MediaType mt,
                                              MultivaluedMap<String, String> h, InputStream in) throws IOException {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            in.transferTo(bos);
            return new java.io.ByteArrayInputStream(bos.toByteArray());
        }
    }

    // ---- Reader ----
    static final class ReaderReaderMBR implements MessageBodyReader<Reader> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return Reader.class == t; }
        @Override public Reader readFrom(Class<Reader> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) {
            return new BufferedReader(new InputStreamReader(in, charset(mt)));
        }
    }

    // ---- StreamingOutput ----
    static final class StreamingOutputWriter implements MessageBodyWriter<StreamingOutput> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return StreamingOutput.class.isAssignableFrom(t); }
        @Override public void writeTo(StreamingOutput v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            v.write(s);
        }
    }

    // ---- File reader (écrit dans un fichier temporaire) ----
    static final class FileReader implements MessageBodyReader<File> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return File.class.isAssignableFrom(t); }
        @Override public File readFrom(Class<File> t, Type gt, Annotation[] a, MediaType mt,
                                       MultivaluedMap<String, String> h, InputStream in) throws IOException {
            java.nio.file.Path tmp = java.nio.file.Files.createTempFile("cassini-upload-", ".bin");
            try (OutputStream os = java.nio.file.Files.newOutputStream(tmp)) {
                in.transferTo(os);
            }
            File f = tmp.toFile();
            f.deleteOnExit();
            return f;
        }
    }

    // ---- MultivaluedMap<String,String> pour application/x-www-form-urlencoded ----
    @Consumes("application/x-www-form-urlencoded")
    static final class FormUrlEncodedReader implements MessageBodyReader<MultivaluedMap<String, String>> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return MultivaluedMap.class.isAssignableFrom(t)
                    && (mt == null || "application/x-www-form-urlencoded".equalsIgnoreCase(
                            mt.getType() + "/" + mt.getSubtype()));
        }
        @Override public MultivaluedMap<String, String> readFrom(Class<MultivaluedMap<String, String>> t, Type gt,
                                                                  Annotation[] a, MediaType mt,
                                                                  MultivaluedMap<String, String> h, InputStream in) throws IOException {
            var parsed = FormDecoder.decode(in.readAllBytes());
            MultivaluedMap<String, String> out = new MultivaluedHashMap<>();
            for (var e : parsed.entrySet()) for (String v : e.getValue()) out.add(e.getKey(), v);
            return out;
        }
    }
    @Produces("application/x-www-form-urlencoded")
    static final class FormUrlEncodedWriter implements MessageBodyWriter<MultivaluedMap<String, String>> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return MultivaluedMap.class.isAssignableFrom(t);
        }
        @Override public void writeTo(MultivaluedMap<String, String> v, Class<?> t, Type gt, Annotation[] a,
                                      MediaType mt, MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            StringBuilder sb = new StringBuilder();
            for (var e : v.entrySet()) {
                for (String val : e.getValue()) {
                    if (sb.length() > 0) sb.append('&');
                    sb.append(java.net.URLEncoder.encode(e.getKey() == null ? "" : e.getKey(), StandardCharsets.UTF_8))
                      .append('=').append(java.net.URLEncoder.encode(val == null ? "" : val, StandardCharsets.UTF_8));
                }
            }
            s.write(sb.toString().getBytes(charset(mt)));
        }
    }

    // ---- Fallback toString ----
    /** Dernier recours : sérialise toute entity via {@code String.valueOf(v).getBytes(UTF-8)}.
     *  Couvre les types applicatifs quelconques (beans simples, enums, etc.) quand le
     *  client n'a pas enregistré de MBW dédié. Conforme à l'esprit de
     *  StringMessageBodyWriter étendu à tout Object. */
    static final class FallbackToStringWriter implements MessageBodyWriter<Object> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return true; }
        @Override public void writeTo(Object v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            s.write(String.valueOf(v).getBytes(charset(mt)));
        }
    }

    // ---- File ----
    static final class FileWriter implements MessageBodyWriter<File> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) { return File.class.isAssignableFrom(t); }
        @Override public void writeTo(File v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            try (InputStream in = new FileInputStream(v)) { in.transferTo(s); }
        }
    }

    // ---- Reader (writer) ----
    static final class ReaderWriter implements MessageBodyWriter<Reader> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return Reader.class.isAssignableFrom(t);
        }
        @Override public void writeTo(Reader v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            OutputStreamWriter w = new OutputStreamWriter(s, charset(mt));
            char[] buf = new char[4096]; int n;
            while ((n = v.read(buf)) > 0) w.write(buf, 0, n);
            w.flush();
        }
    }

    // ---- javax.xml.transform.Source ----
    @Produces({"application/xml", "text/xml", "application/*+xml"})
    static final class SourceWriter implements MessageBodyWriter<javax.xml.transform.Source> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return javax.xml.transform.Source.class.isAssignableFrom(t);
        }
        @Override public void writeTo(javax.xml.transform.Source v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            try {
                javax.xml.transform.Transformer tr = javax.xml.transform.TransformerFactory.newInstance().newTransformer();
                tr.transform(v, new javax.xml.transform.stream.StreamResult(s));
            } catch (javax.xml.transform.TransformerException e) {
                throw new IOException(e);
            }
        }
    }
    @Consumes({"application/xml", "text/xml", "application/*+xml"})
    static final class SourceReader implements MessageBodyReader<javax.xml.transform.Source> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return javax.xml.transform.Source.class.isAssignableFrom(t);
        }
        @Override public javax.xml.transform.Source readFrom(Class<javax.xml.transform.Source> t, Type gt,
                                                             Annotation[] a, MediaType mt,
                                                             MultivaluedMap<String, String> h, InputStream in) {
            return new javax.xml.transform.stream.StreamSource(in);
        }
    }

    // ---- jakarta.activation.DataSource ----
    static final class DataSourceWriter implements MessageBodyWriter<jakarta.activation.DataSource> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return jakarta.activation.DataSource.class.isAssignableFrom(t);
        }
        @Override public void writeTo(jakarta.activation.DataSource v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            try (InputStream in = v.getInputStream()) { in.transferTo(s); }
        }
    }
    static final class DataSourceReader implements MessageBodyReader<jakarta.activation.DataSource> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return jakarta.activation.DataSource.class.isAssignableFrom(t);
        }
        @Override public jakarta.activation.DataSource readFrom(Class<jakarta.activation.DataSource> t, Type gt,
                                                                 Annotation[] a, MediaType mt,
                                                                 MultivaluedMap<String, String> h, InputStream in) throws IOException {
            final byte[] bytes = in.readAllBytes();
            final String ct = mt == null ? "application/octet-stream" : mt.toString();
            return new jakarta.activation.DataSource() {
                @Override public InputStream getInputStream() { return new java.io.ByteArrayInputStream(bytes); }
                @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
                @Override public String getContentType() { return ct; }
                @Override public String getName() { return ""; }
            };
        }
    }

    // ---- JAXB (@XmlRootElement et JAXBElement) ----
    @Produces({"application/xml", "text/xml", "application/*+xml"})
    static final class JaxbWriter implements MessageBodyWriter<Object> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            if (mt == null) return false;
            if (!isXmlMediaType(mt)) return false;
            return t.isAnnotationPresent(jakarta.xml.bind.annotation.XmlRootElement.class)
                    || jakarta.xml.bind.JAXBElement.class.isAssignableFrom(t);
        }
        @Override public void writeTo(Object v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            try {
                Class<?> ctxClass = t;
                if (v instanceof jakarta.xml.bind.JAXBElement<?> el) {
                    ctxClass = el.getDeclaredType();
                }
                jakarta.xml.bind.JAXBContext ctx = jakarta.xml.bind.JAXBContext.newInstance(ctxClass);
                jakarta.xml.bind.Marshaller m = ctx.createMarshaller();
                m.setProperty(jakarta.xml.bind.Marshaller.JAXB_ENCODING, charset(mt).name());
                m.marshal(v, s);
            } catch (jakarta.xml.bind.JAXBException e) {
                throw new IOException(e);
            }
        }
    }
    @Consumes({"application/xml", "text/xml", "application/*+xml"})
    static final class JaxbReader implements MessageBodyReader<Object> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            if (mt == null) return false;
            if (!isXmlMediaType(mt)) return false;
            return t.isAnnotationPresent(jakarta.xml.bind.annotation.XmlRootElement.class)
                    || jakarta.xml.bind.JAXBElement.class.isAssignableFrom(t);
        }
        @Override public Object readFrom(Class<Object> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) throws IOException {
            try {
                if (jakarta.xml.bind.JAXBElement.class.isAssignableFrom(t) && gt instanceof java.lang.reflect.ParameterizedType pt
                        && pt.getActualTypeArguments().length == 1
                        && pt.getActualTypeArguments()[0] instanceof Class<?> innerCls) {
                    jakarta.xml.bind.JAXBContext ctx = jakarta.xml.bind.JAXBContext.newInstance(innerCls);
                    return ctx.createUnmarshaller().unmarshal(
                            javax.xml.stream.XMLInputFactory.newInstance().createXMLStreamReader(in), innerCls);
                }
                jakarta.xml.bind.JAXBContext ctx = jakarta.xml.bind.JAXBContext.newInstance(t);
                return ctx.createUnmarshaller().unmarshal(in);
            } catch (jakarta.xml.bind.UnmarshalException e) {
                throw new jakarta.ws.rs.BadRequestException("Invalid XML body: " + e.getMessage());
            } catch (Exception e) {
                throw new jakarta.ws.rs.BadRequestException("Failed to parse XML: " + e.getMessage());
            }
        }
    }

    // ---- Primitives / wrappers / BigDecimal / BigInteger / Character ----
    /** §4.2.3 : Number, Boolean, Character, primitives, BigDecimal, BigInteger
     *  sérialisés en {@code text/plain} via {@code String.valueOf} / parse. */
    @Produces("text/plain")
    static final class PrimitiveWriter implements MessageBodyWriter<Object> {
        @Override public boolean isWriteable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return isPrimitiveLike(t);
        }
        @Override public void writeTo(Object v, Class<?> t, Type gt, Annotation[] a, MediaType mt,
                                      MultivaluedMap<String, Object> h, OutputStream s) throws IOException {
            s.write(String.valueOf(v).getBytes(charset(mt)));
        }
    }
    @Consumes("text/plain")
    static final class PrimitiveReader implements MessageBodyReader<Object> {
        @Override public boolean isReadable(Class<?> t, Type gt, Annotation[] a, MediaType mt) {
            return isPrimitiveLike(t);
        }
        @Override public Object readFrom(Class<Object> t, Type gt, Annotation[] a, MediaType mt,
                                         MultivaluedMap<String, String> h, InputStream in) throws IOException {
            String s = new String(in.readAllBytes(), charset(mt));
            Class<?> c = t;
            try {
                if (c == Boolean.class || c == boolean.class) {
                    // Boolean.valueOf est permissif — il faut rejeter les non "true/false" pour
                    // être conforme §4.2.3 (un body vide/invalide → 400).
                    if (!("true".equalsIgnoreCase(s) || "false".equalsIgnoreCase(s))) {
                        throw new jakarta.ws.rs.BadRequestException(
                                "Invalid Boolean body: '" + s + "'");
                    }
                    return Boolean.valueOf(s);
                }
                if (c == Character.class || c == char.class) {
                    if (s.isEmpty()) throw new jakarta.ws.rs.BadRequestException(
                            "Empty Character body");
                    return Character.valueOf(s.charAt(0));
                }
                if (c == Byte.class || c == byte.class) return Byte.valueOf(s);
                if (c == Short.class || c == short.class) return Short.valueOf(s);
                if (c == Integer.class || c == int.class) return Integer.valueOf(s);
                if (c == Long.class || c == long.class) return Long.valueOf(s);
                if (c == Float.class || c == float.class) return Float.valueOf(s);
                if (c == Double.class || c == double.class) return Double.valueOf(s);
                if (c == java.math.BigDecimal.class) return new java.math.BigDecimal(s);
                if (c == java.math.BigInteger.class) return new java.math.BigInteger(s);
                if (c == Number.class) return new java.math.BigDecimal(s);
                return s;
            } catch (NumberFormatException | ArithmeticException e) {
                // §4.2.4 : un body inparsable pour un MBR standard → 400.
                throw new jakarta.ws.rs.BadRequestException(
                        "Invalid body for " + c.getSimpleName() + ": '" + s + "'");
            }
        }
    }

    private static boolean isPrimitiveLike(Class<?> t) {
        if (t.isPrimitive()) return true;
        return t == Boolean.class || t == Character.class || t == Byte.class
                || t == Short.class || t == Integer.class || t == Long.class
                || t == Float.class || t == Double.class
                || t == java.math.BigDecimal.class || t == java.math.BigInteger.class
                || t == Number.class;
    }

    private static boolean isXmlMediaType(MediaType mt) {
        if (mt == null) return false;
        String ty = mt.getType(), st = mt.getSubtype();
        if ("application".equalsIgnoreCase(ty) && ("xml".equalsIgnoreCase(st) || st.toLowerCase().endsWith("+xml"))) return true;
        if ("text".equalsIgnoreCase(ty) && "xml".equalsIgnoreCase(st)) return true;
        return false;
    }

    /** Fabrique un MultivaluedMap<String, String> depuis les headers Chappe. */
    public static MultivaluedMap<String, String> adaptHeaders(fr.vidocq.chappe.api.Headers h) {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (fr.vidocq.chappe.api.Headers.Entry e : h) {
            m.add(e.name(), e.value());
        }
        return m;
    }

    /** Utilitaire : force un MultivaluedMap<String, Object> pour sortie MBW. */
    public static MultivaluedMap<String, Object> outHeaders() {
        return new MultivaluedHashMap<>();
    }

    /** Helper qui suppress le type-cast nécessaire au writeTo typé. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void writeTo(MessageBodyWriter w, Object value, Class<?> type,
                               Type genericType, Annotation[] anns, MediaType mt,
                               MultivaluedMap<String, Object> headers, OutputStream os) throws IOException {
        w.writeTo(value, type, genericType, anns, mt, headers, os);
    }

    /** Résout le type générique T de {@code providerInterface} (MBR ou MBW) pour {@code cls}. */
    static Class<?> resolveProviderType(Class<?> cls, Class<?> providerInterface) {
        for (java.lang.reflect.Type iface : cls.getGenericInterfaces()) {
            if (iface instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getRawType() == providerInterface
                    && pt.getActualTypeArguments().length == 1) {
                java.lang.reflect.Type arg = pt.getActualTypeArguments()[0];
                if (arg instanceof Class<?> c) return c;
                if (arg instanceof java.lang.reflect.WildcardType) return Object.class;
                return Object.class;
            }
        }
        Class<?> sup = cls.getSuperclass();
        return (sup == null || sup == Object.class) ? null : resolveProviderType(sup, providerInterface);
    }

    /** Boxing primitif → wrapper pour comparaison de type MBR/MBW. */
    static Class<?> box(Class<?> t) {
        if (t == boolean.class) return Boolean.class;
        if (t == byte.class)    return Byte.class;
        if (t == short.class)   return Short.class;
        if (t == int.class)     return Integer.class;
        if (t == long.class)    return Long.class;
        if (t == float.class)   return Float.class;
        if (t == double.class)  return Double.class;
        if (t == char.class)    return Character.class;
        return t;
    }
}
