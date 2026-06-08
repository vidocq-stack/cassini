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
        // clearly invalid characters (backslash, espace, etc.).
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

    /** True if {@code a} and {@code b} are compatible (wildcard included). */
    public static boolean matches(MediaType a, MediaType b) {
        if (a == null || b == null) return false;
        if (!a.isWildcardType() && !b.isWildcardType() && !a.getType().equalsIgnoreCase(b.getType())) return false;
        return a.isWildcardSubtype() || b.isWildcardSubtype()
                || a.getSubtype().equalsIgnoreCase(b.getSubtype());
    }

    /** Takes client Accept + @Produces method, returns the best match (most specific). */
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
                // Prioritize higher qs, then specificity.
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

    /** Request Content-Type vs @Consumes method. */
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
             // Jersey/RestEasy allow @Consumes/@Produces("a,b") — a single
             // String containing multiple media-types separated by comma.
             if (r != null && r.indexOf(',') >= 0 && r.indexOf(';') < 0) {
                for (String tok : r.split(",")) if (!tok.isBlank()) out.add(parse(tok));
            } else {
                out.add(parse(r));
            }
        }
        return Collections.unmodifiableList(out);
    }

    /**
     * Serializes a {@link MediaType} without going through
     * {@link jakarta.ws.rs.ext.RuntimeDelegate} (which Cassini does not
     * provide before M2e).
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
