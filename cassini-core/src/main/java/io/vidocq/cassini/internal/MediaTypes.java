package io.vidocq.cassini.internal;

import jakarta.ws.rs.core.MediaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Helpers pour {@link MediaType} : parsing, matching par wildcard, tri par
 * qualité et sélection best-match (§3.8 Content Negotiation).
 */
public final class MediaTypes {

    public static final MediaType WILDCARD = MediaType.WILDCARD_TYPE;

    private MediaTypes() {}

    /** Parse "type/subtype;p1=v1;q=0.8" en {@link MediaType}. Défaut wildcard. */
    public static MediaType parse(String raw) {
        if (raw == null || raw.isBlank()) return WILDCARD;
        String[] parts = raw.split(";");
        String[] ts = parts[0].trim().split("/", 2);
        if (ts.length < 2) {
            throw new IllegalArgumentException("Invalid media type: " + raw);
        }
        String type = ts[0].trim().isEmpty() ? "*" : ts[0].trim();
        String subtype = ts[1].trim().isEmpty() ? "*" : ts[1].trim();
        // RFC 7231 token : lettres, chiffres, et quelques symboles. On rejette
        // les caractères clairement invalides (backslash, espace, etc.).
        if (!isValidMediaTypeToken(type) || !isValidMediaTypeToken(subtype)) {
            throw new IllegalArgumentException("Invalid media type: " + raw);
        }
        Map<String, String> params = new HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) continue;
            int eq = seg.indexOf('=');
            if (eq < 0) params.put(seg, "");
            else params.put(seg.substring(0, eq).trim(), unquote(seg.substring(eq + 1).trim()));
        }
        return new MediaType(type, subtype, params);
    }

    private static boolean isValidMediaTypeToken(String s) {
        if (s == null || s.isEmpty()) return false;
        if ("*".equals(s)) return true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' || c == '"' || c == ',' || c == ';' || c == '=' || c == ' '
                    || c == '<' || c == '>' || c == '(' || c == ')' || c == '[' || c == ']'
                    || c <= 31 || c >= 127) return false;
        }
        return true;
    }

    public static List<MediaType> parseList(String raw) {
        if (raw == null || raw.isBlank()) return List.of(WILDCARD);
        List<MediaType> out = new ArrayList<>();
        for (String tok : raw.split(",")) {
            if (!tok.isBlank()) out.add(parse(tok));
        }
        return out;
    }

    /** Vrai si {@code a} et {@code b} sont compatibles (wildcard inclus). */
    public static boolean matches(MediaType a, MediaType b) {
        if (a == null || b == null) return false;
        if (!a.isWildcardType() && !b.isWildcardType() && !a.getType().equalsIgnoreCase(b.getType())) return false;
        return a.isWildcardSubtype() || b.isWildcardSubtype()
                || a.getSubtype().equalsIgnoreCase(b.getSubtype());
    }

    /** Prend Accept client + @Produces method, renvoie le best-match (le plus spécifique). */
    public static Optional<MediaType> pickProduced(List<MediaType> accepts,
                                                   List<MediaType> produces) {
        boolean producesExplicit = !produces.isEmpty();
        if (produces.isEmpty()) produces = List.of(WILDCARD);
        List<MediaType> effectiveAccepts = accepts.isEmpty() ? List.of(WILDCARD) : accepts;
        List<MediaType> sortedAccepts = new ArrayList<>(effectiveAccepts);
        sortedAccepts.sort(Comparator.comparingDouble(MediaTypes::quality).reversed());
        for (MediaType a : sortedAccepts) {
            MediaType best = null;
            double bestSourceQ = -1;
            int bestSpec = -1;
            for (MediaType p : produces) {
                if (!matches(a, p)) continue;
                MediaType candidate = p.isWildcardSubtype() || p.isWildcardType() ? a : p;
                double sq = sourceQuality(p);
                int spec = specificity(candidate);
                // Prioriser qs plus élevé, puis spécificité.
                if (sq > bestSourceQ || (sq == bestSourceQ && spec > bestSpec)) {
                    best = candidate;
                    bestSourceQ = sq;
                    bestSpec = spec;
                }
            }
            if (best == null) continue;
            if (producesExplicit && best.isWildcardSubtype()) continue;
            return Optional.of(best);
        }
        return Optional.empty();
    }

    private static double sourceQuality(MediaType mt) {
        String qs = mt.getParameters().get("qs");
        if (qs == null) return 1.0;
        try { return Double.parseDouble(qs); } catch (NumberFormatException e) { return 1.0; }
    }

    /** Content-Type requête vs @Consumes method. */
    public static boolean consumesMatches(MediaType contentType, List<MediaType> consumes) {
        if (consumes.isEmpty()) return true;
        for (MediaType c : consumes) if (matches(contentType, c)) return true;
        return false;
    }

    public static double quality(MediaType mt) {
        String q = mt.getParameters().get("q");
        if (q == null) return 1.0;
        try { return Double.parseDouble(q); } catch (NumberFormatException e) { return 1.0; }
    }

    private static int specificity(MediaType mt) {
        int s = 0;
        if (!mt.isWildcardType()) s += 2;
        if (!mt.isWildcardSubtype()) s += 1;
        return s;
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    public static List<MediaType> fromSet(java.util.Set<String> raws) {
        if (raws == null || raws.isEmpty()) return List.of();
        List<MediaType> out = new ArrayList<>(raws.size());
        for (String r : raws) {
            // Jersey/RestEasy tolèrent @Consumes/@Produces("a,b") — un seul
            // String contenant plusieurs media-types séparés par virgule.
            if (r != null && r.indexOf(',') >= 0 && r.indexOf(';') < 0) {
                for (String tok : r.split(",")) if (!tok.isBlank()) out.add(parse(tok));
            } else {
                out.add(parse(r));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Sérialise un {@link MediaType} sans passer par
     * {@link jakarta.ws.rs.ext.RuntimeDelegate} (que Cassini ne fournit
     * pas avant M2e).
     */
    public static String format(MediaType mt) {
        if (mt == null) return "*/*";
        StringBuilder sb = new StringBuilder();
        sb.append(mt.getType()).append('/').append(mt.getSubtype());
        for (Map.Entry<String, String> e : mt.getParameters().entrySet()) {
            sb.append(';').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
