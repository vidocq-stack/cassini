package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.MediaTypes;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.List;

/**
 * Implémentation {@link jakarta.ws.rs.core.Request} avec support
 * {@link #evaluatePreconditions} (§4.5) et {@link #selectVariant} (§5.1).
 */
public final class CassiniRequest implements jakarta.ws.rs.core.Request {

    /** §5.1 / Javadoc Request#selectVariant : "this method also sets the Vary
     *  header field on the response". On collecte ici les dimensions sur
     *  lesquelles selectVariant a négocié pour qu'Invoker les écrive
     *  ensuite dans les headers de la response (au moment du marshal).
     *
     *  <p>TODO(M2h) : migrer vers un attribut du {@code CassiniHttpExchange}
     *  (ou ScopedValue) — ce ThreadLocal casse en virtual-thread async. */
    public static final ThreadLocal<java.util.Set<String>> PENDING_VARY =
            ThreadLocal.withInitial(java.util.LinkedHashSet::new);

    private final String method;
    private final CassiniHttpExchange delegate;

    public CassiniRequest(String method) {
        this.method = method;
        this.delegate = null;
    }

    public CassiniRequest(CassiniHttpExchange delegate) {
        this.method = delegate.method();
        this.delegate = delegate;
    }

    @Override public String getMethod() { return method; }

    @Override public Variant selectVariant(List<Variant> variants) {
        if (variants == null || variants.isEmpty()) throw new IllegalArgumentException("variants");
        if (delegate == null) return null;
        List<MediaType> accepts = MediaTypes.parseList(delegate.firstHeader("Accept"));
        String acceptLang = delegate.firstHeader("Accept-Language");
        boolean langWildcard = acceptLang != null && containsWildcard(acceptLang);
        List<Locale> langs = acceptLang == null ? List.of() : parseLocales(acceptLang);
        String acceptEnc = delegate.firstHeader("Accept-Encoding");
        boolean encWildcard = acceptEnc != null && containsWildcard(acceptEnc);

        // §5.1 : dimensions de négociation = toutes celles présentes sur
        // au moins un Variant. On set Vary pour ces dimensions, indépendamment
        // de la sélection finale (le client doit savoir comment varier sa requête).
        boolean anyMedia = variants.stream().anyMatch(v -> v.getMediaType() != null);
        boolean anyLang = variants.stream().anyMatch(v -> v.getLanguage() != null);
        boolean anyEnc = variants.stream().anyMatch(v -> v.getEncoding() != null);
        java.util.Set<String> vary = PENDING_VARY.get();
        if (anyMedia) vary.add("Accept");
        if (anyLang) vary.add("Accept-Language");
        if (anyEnc) vary.add("Accept-Encoding");

        Variant best = null;
        for (Variant v : variants) {
            if (v.getMediaType() != null && !accepts.isEmpty()) {
                boolean ok = accepts.stream().anyMatch(a -> MediaTypes.matches(a, v.getMediaType()));
                if (!ok) continue;
            }
            if (v.getLanguage() != null && !langs.isEmpty() && !langWildcard) {
                boolean ok = langs.stream().anyMatch(l -> sameLang(l, v.getLanguage()));
                if (!ok) continue;
            }
            if (v.getEncoding() != null && acceptEnc != null && !encWildcard) {
                if (!acceptEnc.toLowerCase().contains(v.getEncoding().toLowerCase())) continue;
            }
            best = v;
            break;
        }
        return best;
    }

    /** RFC 7231 §5.3 : un Accept-Language/Encoding peut contenir "*" qui matche
     *  toute valeur (ou comme part de liste, ex "en-US, *;q=0.5"). */
    private static boolean containsWildcard(String header) {
        for (String tok : header.split(",")) {
            int semi = tok.indexOf(';');
            String v = (semi < 0 ? tok : tok.substring(0, semi)).trim();
            if ("*".equals(v)) return true;
        }
        return false;
    }

    private static boolean sameLang(Locale a, Locale b) {
        return a.getLanguage().equalsIgnoreCase(b.getLanguage());
    }
    private static List<Locale> parseLocales(String raw) {
        List<Locale> out = new java.util.ArrayList<>();
        for (String tok : raw.split(",")) {
            int semi = tok.indexOf(';');
            String tag = (semi < 0 ? tok : tok.substring(0, semi)).trim();
            if (tag.isEmpty() || "*".equals(tag)) continue;
            out.add(Locale.forLanguageTag(tag));
        }
        return out;
    }

    @Override public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        if (eTag == null) throw new IllegalArgumentException("eTag");
        if (delegate == null) return null;
        String ifMatch = delegate.firstHeader("If-Match");
        String ifNoneMatch = delegate.firstHeader("If-None-Match");
        if (ifMatch != null && !matchesEtag(ifMatch, eTag)) {
            return Response.status(Response.Status.PRECONDITION_FAILED).tag(eTag);
        }
        if (ifNoneMatch != null && matchesEtag(ifNoneMatch, eTag)) {
            boolean safe = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
            return Response.status(safe ? Response.Status.NOT_MODIFIED
                    : Response.Status.PRECONDITION_FAILED).tag(eTag);
        }
        return null;
    }

    @Override public Response.ResponseBuilder evaluatePreconditions(Date lastModified) {
        if (lastModified == null) throw new IllegalArgumentException("lastModified");
        if (delegate == null) return null;
        Date ifModSince = parseHttpDate(delegate.firstHeader("If-Modified-Since"));
        Date ifUnmodSince = parseHttpDate(delegate.firstHeader("If-Unmodified-Since"));
        if (ifUnmodSince != null && lastModified.after(ifUnmodSince)) {
            return Response.status(Response.Status.PRECONDITION_FAILED);
        }
        if (ifModSince != null && !lastModified.after(ifModSince)) {
            boolean safe = "GET".equalsIgnoreCase(method) || "HEAD".equalsIgnoreCase(method);
            if (safe) return Response.notModified();
        }
        return null;
    }

    @Override public Response.ResponseBuilder evaluatePreconditions(Date lastModified, EntityTag eTag) {
        Response.ResponseBuilder r = evaluatePreconditions(eTag);
        if (r != null) return r;
        return evaluatePreconditions(lastModified);
    }

    @Override public Response.ResponseBuilder evaluatePreconditions() {
        if (delegate == null) return null;
        String ifMatch = delegate.firstHeader("If-Match");
        if (ifMatch != null) {
            return Response.status(Response.Status.PRECONDITION_FAILED);
        }
        return null;
    }

    private static boolean matchesEtag(String header, EntityTag eTag) {
        if (header.trim().equals("*")) return true;
        String tag = eTag.toString();
        for (String tok : header.split(",")) {
            if (tok.trim().equals(tag)) return true;
            // ignore weak/strong distinction in MVP
            if (tok.trim().replace("W/", "").equals(tag.replace("W/", ""))) return true;
        }
        return false;
    }

    private static Date parseHttpDate(String s) {
        if (s == null) return null;
        for (String fmt : new String[] {
                "EEE, dd MMM yyyy HH:mm:ss zzz",
                "EEEE, dd-MMM-yy HH:mm:ss zzz",
                "EEE MMM d HH:mm:ss yyyy"}) {
            try { return new SimpleDateFormat(fmt, Locale.US).parse(s); }
            catch (java.text.ParseException ignored) {}
        }
        return null;
    }
}
