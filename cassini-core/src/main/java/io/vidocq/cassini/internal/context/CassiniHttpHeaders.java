package io.vidocq.cassini.internal.context;

import fr.vidocq.chappe.api.Request;
import io.vidocq.cassini.internal.MediaTypes;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Implémentation {@link HttpHeaders} adossée à {@link Request} Chappe.
 */
public final class CassiniHttpHeaders implements HttpHeaders {

    private final Request request;

    public CassiniHttpHeaders(Request request) {
        this.request = request;
    }

    @Override public List<String> getRequestHeader(String name) { return request.headers().all(name); }

    @Override public String getHeaderString(String name) {
        List<String> all = request.headers().all(name);
        return all.isEmpty() ? null : String.join(",", all);
    }

    @Override public boolean containsHeaderString(String n, String valueSep, java.util.function.Predicate<String> match) {
        for (String v : request.headers().all(n)) {
            for (String tok : v.split(valueSep)) if (match.test(tok.trim())) return true;
        }
        return false;
    }

    @Override public boolean containsHeaderString(String n, java.util.function.Predicate<String> match) {
        return containsHeaderString(n, ",", match);
    }

    @Override public MultivaluedMap<String, String> getRequestHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : request.headers()) m.add(e.name(), e.value());
        return m;
    }

    @Override public List<MediaType> getAcceptableMediaTypes() {
        return MediaTypes.parseList(request.headers().firstOrNull("Accept"));
    }

    @Override public List<Locale> getAcceptableLanguages() {
        String raw = request.headers().firstOrNull("Accept-Language");
        if (raw == null || raw.isBlank()) return List.of();
        java.util.List<Locale> out = new java.util.ArrayList<>();
        for (String tok : raw.split(",")) {
            int semi = tok.indexOf(';');
            String tag = (semi < 0 ? tok : tok.substring(0, semi)).trim();
            if (!tag.isEmpty()) out.add(Locale.forLanguageTag(tag));
        }
        return out;
    }

    @Override public MediaType getMediaType() {
        return MediaTypes.parse(request.headers().firstOrNull("Content-Type"));
    }

    @Override public Locale getLanguage() {
        String raw = request.headers().firstOrNull("Content-Language");
        if (raw == null) return null;
        // §4.3 : on accepte aussi la convention Java "en_US" en plus du
        // RFC 5646 "en-US" — Locale.forLanguageTag attend les dashes,
        // sinon on tombe sur Locale.ROOT.
        return Locale.forLanguageTag(raw.replace('_', '-'));
    }

    @Override public Map<String, Cookie> getCookies() {
        // §4.3.2 / RFC 2109 : un Cookie header peut combiner plusieurs cookies
        // séparés par ';' avec attributs $Version/$Path/$Domain qui s'appliquent
        // au cookie suivant ($Version) ou précédent ($Path/$Domain).
        Map<String, Cookie> out = new HashMap<>();
        for (String header : request.headers().all("Cookie")) {
            int currentVersion = 0;
            String pendingName = null, pendingValue = null;
            String pendingPath = null, pendingDomain = null;
            for (String pair : header.split(";")) {
                int eq = pair.indexOf('=');
                if (eq < 0) continue;
                String n = pair.substring(0, eq).trim();
                String v = pair.substring(eq + 1).trim();
                if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
                    v = v.substring(1, v.length() - 1);
                }
                if ("$Version".equalsIgnoreCase(n)) {
                    try { currentVersion = Integer.parseInt(v); } catch (NumberFormatException ignored) {}
                } else if ("$Path".equalsIgnoreCase(n)) {
                    pendingPath = v;
                } else if ("$Domain".equalsIgnoreCase(n)) {
                    pendingDomain = v;
                } else {
                    if (pendingName != null) {
                        flushCookie(out, pendingName, pendingValue, currentVersion, pendingPath, pendingDomain);
                        pendingPath = null;
                        pendingDomain = null;
                    }
                    pendingName = n;
                    pendingValue = v;
                }
            }
            if (pendingName != null) {
                flushCookie(out, pendingName, pendingValue, currentVersion, pendingPath, pendingDomain);
            }
        }
        return out;
    }

    private static void flushCookie(Map<String, Cookie> out, String name, String value,
                                    int version, String path, String domain) {
        Cookie.Builder b = new Cookie.Builder(name).value(value).version(version);
        if (path != null) b.path(path);
        if (domain != null) b.domain(domain);
        out.put(name, b.build());
    }

    @Override public Date getDate() {
        String raw = request.headers().firstOrNull("Date");
        if (raw == null) return null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            return fmt.parse(raw);
        } catch (Exception e) { return null; }
    }

    @Override public int getLength() {
        String raw = request.headers().firstOrNull("Content-Length");
        if (raw == null) return -1;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return -1; }
    }
}
