package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.internal.MediaTypes;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
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
 * {@link HttpHeaders} implementation backed by a {@link CassiniHttpExchange}.
 */
public final class CassiniHttpHeaders implements HttpHeaders {

    private final CassiniHttpExchange exchange;

    public CassiniHttpHeaders(CassiniHttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override public List<String> getRequestHeader(String name) { return exchange.headers(name); }

    @Override public String getHeaderString(String name) {
        List<String> all = exchange.headers(name);
        return all.isEmpty() ? null : String.join(",", all);
    }

    @Override public boolean containsHeaderString(String n, String valueSep, java.util.function.Predicate<String> match) {
        for (String v : exchange.headers(n)) {
            for (String tok : v.split(valueSep)) if (match.test(tok.trim())) return true;
        }
        return false;
    }

    @Override public boolean containsHeaderString(String n, java.util.function.Predicate<String> match) {
        return containsHeaderString(n, ",", match);
    }

    @Override public MultivaluedMap<String, String> getRequestHeaders() {
        MultivaluedMap<String, String> m = new MultivaluedHashMap<>();
        for (var e : exchange.requestHeaders().entrySet()) {
            for (String v : e.getValue()) m.add(e.getKey(), v);
        }
        return m;
    }

    @Override public List<MediaType> getAcceptableMediaTypes() {
        // §6.7.4.7: returned list is sorted by descending q-value.
        List<MediaType> list = MediaTypes.parseList(exchange.firstHeader("Accept"));
        java.util.List<MediaType> mut = new java.util.ArrayList<>(list);
        mut.sort((a, b) -> Double.compare(qValue(b), qValue(a)));
        return mut;
    }

    @Override public List<Locale> getAcceptableLanguages() {
        // §6.7.4.7: sort by descending q-value.
        String raw = exchange.firstHeader("Accept-Language");
        if (raw == null || raw.isBlank()) return List.of();
        java.util.List<java.util.Map.Entry<Locale, Double>> entries = new java.util.ArrayList<>();
        for (String tok : raw.split(",")) {
            int semi = tok.indexOf(';');
            String tag = (semi < 0 ? tok : tok.substring(0, semi)).trim();
            if (tag.isEmpty()) continue;
            double q = 1.0;
            if (semi >= 0) {
                for (String p : tok.substring(semi + 1).split(";")) {
                    String pt = p.trim();
                    if (pt.startsWith("q=")) {
                        try { q = Double.parseDouble(pt.substring(2)); } catch (NumberFormatException ignored) {}
                    }
                }
            }
            entries.add(java.util.Map.entry(Locale.forLanguageTag(tag), q));
        }
        entries.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        java.util.List<Locale> out = new java.util.ArrayList<>(entries.size());
        for (var e : entries) out.add(e.getKey());
        return out;
    }

    private static double qValue(MediaType mt) {
        String q = mt.getParameters().get("q");
        if (q == null) return 1.0;
        try { return Double.parseDouble(q); } catch (NumberFormatException e) { return 1.0; }
    }

    @Override public MediaType getMediaType() {
        // §3.6.4 / §6.7.4: null if no Content-Type
        // (do NOT fall back to WILDCARD like MediaTypes.parse).
        String raw = exchange.firstHeader("Content-Type");
        if (raw == null || raw.isBlank()) return null;
        return MediaTypes.parse(raw);
    }

    @Override public Locale getLanguage() {
        String raw = exchange.firstHeader("Content-Language");
        if (raw == null) return null;
        // §4.3: also accept the Java convention "en_US" in addition to
        // RFC 5646 "en-US" — Locale.forLanguageTag expects dashes,
        // otherwise it falls back to Locale.ROOT.
        return Locale.forLanguageTag(raw.replace('_', '-'));
    }

    @Override public Map<String, Cookie> getCookies() {
        // §4.3.2 / RFC 2109: a Cookie header may combine several cookies
        // separated by ';' with $Version/$Path/$Domain attributes that apply
        // to the next cookie ($Version) or the previous one ($Path/$Domain).
        Map<String, Cookie> out = new HashMap<>();
        for (String header : exchange.headers("Cookie")) {
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
        String raw = exchange.firstHeader("Date");
        if (raw == null) return null;
        try {
            SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
            return fmt.parse(raw);
        } catch (Exception e) { return null; }
    }

    @Override public int getLength() {
        String raw = exchange.firstHeader("Content-Length");
        if (raw == null) return -1;
        try { return Integer.parseInt(raw); } catch (NumberFormatException e) { return -1; }
    }
}
