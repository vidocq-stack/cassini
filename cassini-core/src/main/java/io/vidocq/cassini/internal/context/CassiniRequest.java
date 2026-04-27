package io.vidocq.cassini.internal.context;

import fr.vidocq.chappe.api.Request;
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

    private final String method;
    private final Request delegate;

    public CassiniRequest(String method) {
        this.method = method;
        this.delegate = null;
    }

    public CassiniRequest(Request delegate) {
        this.method = delegate.method().name();
        this.delegate = delegate;
    }

    @Override public String getMethod() { return method; }

    @Override public Variant selectVariant(List<Variant> variants) {
        if (variants == null || variants.isEmpty()) throw new IllegalArgumentException("variants");
        if (delegate == null) return null;
        List<MediaType> accepts = MediaTypes.parseList(delegate.headers().firstOrNull("Accept"));
        String acceptLang = delegate.headers().firstOrNull("Accept-Language");
        List<Locale> langs = acceptLang == null ? List.of() : parseLocales(acceptLang);
        String acceptEnc = delegate.headers().firstOrNull("Accept-Encoding");

        Variant best = null;
        for (Variant v : variants) {
            if (v.getMediaType() != null && !accepts.isEmpty()) {
                boolean ok = accepts.stream().anyMatch(a -> MediaTypes.matches(a, v.getMediaType()));
                if (!ok) continue;
            }
            if (v.getLanguage() != null && !langs.isEmpty()) {
                boolean ok = langs.stream().anyMatch(l -> sameLang(l, v.getLanguage()));
                if (!ok) continue;
            }
            if (v.getEncoding() != null && acceptEnc != null) {
                if (!acceptEnc.toLowerCase().contains(v.getEncoding().toLowerCase())) continue;
            }
            best = v;
            break;
        }
        return best;
    }

    private static boolean sameLang(Locale a, Locale b) {
        return a.getLanguage().equalsIgnoreCase(b.getLanguage());
    }
    private static List<Locale> parseLocales(String raw) {
        List<Locale> out = new java.util.ArrayList<>();
        for (String tok : raw.split(",")) {
            int semi = tok.indexOf(';');
            String tag = (semi < 0 ? tok : tok.substring(0, semi)).trim();
            if (!tag.isEmpty()) out.add(Locale.forLanguageTag(tag));
        }
        return out;
    }

    @Override public Response.ResponseBuilder evaluatePreconditions(EntityTag eTag) {
        if (eTag == null) throw new IllegalArgumentException("eTag");
        if (delegate == null) return null;
        String ifMatch = delegate.headers().firstOrNull("If-Match");
        String ifNoneMatch = delegate.headers().firstOrNull("If-None-Match");
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
        Date ifModSince = parseHttpDate(delegate.headers().firstOrNull("If-Modified-Since"));
        Date ifUnmodSince = parseHttpDate(delegate.headers().firstOrNull("If-Unmodified-Since"));
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
        String ifMatch = delegate.headers().firstOrNull("If-Match");
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
