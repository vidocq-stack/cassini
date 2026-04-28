package io.vidocq.cassini.internal.json;

import io.vidocq.cassini.internal.ParamExtractor;
import jakarta.json.bind.Jsonb;
import jakarta.json.bind.JsonbBuilder;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.ContextResolver;
import jakarta.ws.rs.ext.MessageBodyReader;
import jakarta.ws.rs.ext.MessageBodyWriter;
import jakarta.ws.rs.ext.Providers;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;

/**
 * §4.2.3 / Core Profile : MBR/MBW pour {@code application/json} (et compatible
 * suffix {@code application/*+json}) basé sur Jakarta JSON Binding (Yasson).
 *
 * <p>§9.2 : si l'application fournit un {@link ContextResolver}{@code <Jsonb>}
 * pour le type cible, son {@link Jsonb} est utilisé. Sinon, on utilise une
 * instance par défaut créée avec {@link JsonbBuilder#create()}.</p>
 */
@Consumes({"application/json", "application/*+json", "text/json"})
@Produces({"application/json", "application/*+json", "text/json"})
public final class CassiniJsonbReaderWriter
        implements MessageBodyReader<Object>, MessageBodyWriter<Object> {

    private static final Jsonb DEFAULT = JsonbBuilder.create();

    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJson(mediaType) && !isExcluded(type);
    }

    @Override
    public Object readFrom(Class<Object> type, Type genericType, Annotation[] annotations,
                           MediaType mediaType, MultivaluedMap<String, String> httpHeaders,
                           InputStream entityStream) throws IOException, WebApplicationException {
        Jsonb jsonb = resolveJsonb(type, mediaType);
        return jsonb.fromJson(entityStream, genericType == null ? type : genericType);
    }

    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType) {
        return isJson(mediaType) && !isExcluded(type);
    }

    @Override
    public void writeTo(Object o, Class<?> type, Type genericType, Annotation[] annotations,
                        MediaType mediaType, MultivaluedMap<String, Object> httpHeaders,
                        OutputStream entityStream) throws IOException, WebApplicationException {
        Jsonb jsonb = resolveJsonb(type, mediaType);
        jsonb.toJson(o, genericType == null ? type : genericType, entityStream);
    }

    private Jsonb resolveJsonb(Class<?> type, MediaType mt) {
        // Lookup direct via le ThreadLocal Providers exposé par ParamExtractor —
        // évite la dépendance au cycle d'injection @Context (qui peut ne pas
        // avoir lieu pour certains chemins, notamment writeFromContext).
        Providers providers = ParamExtractor.currentProviders();
        if (providers != null) {
            try {
                ContextResolver<Jsonb> r = providers.getContextResolver(Jsonb.class, mt);
                if (r != null) {
                    Jsonb j = r.getContext(type);
                    if (j != null) return j;
                }
            } catch (RuntimeException ignored) {}
        }
        return DEFAULT;
    }

    private static boolean isJson(MediaType mt) {
        if (mt == null) return false;
        // Refuse les wildcards (*/* ou type/*) : §4.2.3 Core Profile, le MBR/MBW
        // JSON-B builtin ne doit s'engager que sur des media types JSON explicites,
        // pour ne pas masquer les MBW user-provided sans @Produces (= wildcard).
        if (mt.isWildcardType() || mt.isWildcardSubtype()) return false;
        if (mt.isCompatible(MediaType.APPLICATION_JSON_TYPE)) return true;
        // Suffix +json (RFC 6839) : application/foo+json, etc.
        String sub = mt.getSubtype();
        return sub != null && sub.toLowerCase().endsWith("+json");
    }

    /** Évite de capter les types qui ont leur MBR/MBW dédié — sinon le scan
     *  Cassini risque de prioriser ce JSON sur les builtins (String, byte[],
     *  InputStream, etc) à cause de @Consumes/@Produces matching.
     *  §4.2.4 : on utilise isAssignableFrom pour couvrir aussi les sous-types
     *  (ex. ByteArrayInputStream, FileInputStream...). */
    private static boolean isExcluded(Class<?> type) {
        if (type == null) return true;
        if (type == byte[].class) return true;
        if (CharSequence.class.isAssignableFrom(type)) return true;
        if (InputStream.class.isAssignableFrom(type)) return true;
        if (java.io.Reader.class.isAssignableFrom(type)) return true;
        if (jakarta.ws.rs.core.StreamingOutput.class.isAssignableFrom(type)) return true;
        if (java.io.File.class.isAssignableFrom(type)) return true;
        if (javax.xml.transform.Source.class.isAssignableFrom(type)) return true;
        // §4.2.3 : DataSource a son MBR/MBW dédié.
        try {
            Class<?> dataSource = Class.forName("jakarta.activation.DataSource");
            if (dataSource.isAssignableFrom(type)) return true;
        } catch (ClassNotFoundException ignored) {}
        return false;
    }
}
