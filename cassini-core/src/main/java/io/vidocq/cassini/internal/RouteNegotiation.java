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

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import jakarta.ws.rs.core.MediaType;

import java.util.List;

/**
 * §3.7.2 route negotiation scoring, extracted from {@link Invoker} (which
 * stays the facade and delegates): among the candidates matching the same
 * path + verb, picks the route whose {@code @Consumes} matches Content-Type
 * and whose {@code @Produces} best matches Accept — URI-template specificity
 * first, then consumes specificity, then the Accept q / server-side qs /
 * produces-specificity triple.
 */
final class RouteNegotiation {

    private RouteNegotiation() {
    }

    static boolean hasRequestBody(CassiniHttpExchange request) {
        long len = request.contentLength();
        if (len > 0) return true;
        // chunked encoding → contentLength may be -1 ; rely on presence of Content-Type
        return request.firstHeader("Content-Type") != null && len != 0;
    }


    /** §3.7.2: among the candidates (same path+verb), choose the one whose
     *  @Consumes matches Content-Type AND whose @Produces matches Accept (maximum
     *  specificity). If none matches, return the first one (the Invoker will raise
     *  415 or 406 later). */
    /** Returns the highest qs among the route's @Produces. */
    private static double sourceQuality(java.util.List<MediaType> produces) {
        double best = 0;
        for (MediaType p : produces) {
            String qs = p.getParameters().get("qs");
            double v = 1.0;
            if (qs != null) try { v = Double.parseDouble(qs); } catch (Exception ignored) {}
            if (v > best) best = v;
        }
        return best;
    }

    static MatchResult pickBestMatch(java.util.List<MatchResult> candidates, CassiniHttpExchange request) {
        if (candidates.size() == 1) return candidates.get(0);
        MediaType ct = MediaTypes.parse(request.firstHeader("Content-Type"));
        java.util.List<MediaType> accepts = MediaTypes.parseList(request.firstHeader("Accept"));
        MatchResult best = null;
        double bestScore = -1;
        for (MatchResult c : candidates) {
            var cons = MediaTypes.fromSet(c.method().consumes());
            if (hasRequestBody(request) && !cons.isEmpty() && !MediaTypes.consumesMatches(ct, cons)) continue;
            var prod = MediaTypes.fromSet(c.method().produces());
            // §3.7.2: @Consumes specificity dominates @Produces (scaled ×10).
            // text/plain > text/* > */* > absence of @Consumes (if ct is present).
            double consScore = consumesSpecificity(ct, cons);
            double prodScore = 0;
            if (!prod.isEmpty()) {
                var pick = MediaTypes.pickProduced(accepts, prod);
                if (pick.isEmpty()) continue;
                // §3.7.2 / JAXRS:SPEC:25.11 + 26.8: order
                //   primary   = q-value of the MOST SPECIFIC matching Accept
                //               (cf. bestAcceptQuality)
                //   secondary = qs-value (source quality, server-side)
                //   tertiary  = @Produces specificity (tie-break)
                double acceptQ = bestAcceptQuality(accepts, prod);
                double qs = sourceQuality(prod);
                double spec = producesAnnotationSpecificity(accepts, prod);
                prodScore = acceptQ * 1_000_000 + qs * 1_000 + spec;
            }
            // §3.7.2: URI template specificity dominates first (literalChars
            // desc, totalCaptures desc, defaultCaptures asc), then @Consumes,
            // then @Produces. Scales: classPathLiterals (×1e8) > template
            // literalChars (×1e6) > totalCaptures (×1e3) > inverse defaultCaptures
            // (×1) > consumes (×10) > produces.
            int classLits = c.method().classPathLiterals();
            int tplLits = c.method().template().literalChars();
            int totalCaps = c.method().template().totalCaptures();
            int defaultCaps = c.method().template().defaultCaptures();
            double score = classLits * 1e8
                    + tplLits * 1e6
                    + totalCaps * 1e3
                    + (1000 - defaultCaps)
                    + consScore * 10 + prodScore;
            if (score > bestScore) { best = c; bestScore = score; }
        }
        return best != null ? best : candidates.get(0);
    }

    /** Specificity of the best-ranked @Produces matching an Accept, computed
     *  from the annotation (not from the type resolved after wildcard expansion). */
    private static double producesAnnotationSpecificity(List<MediaType> accepts, List<MediaType> produces) {
        double best = 0;
        for (MediaType a : accepts) {
            for (MediaType p : produces) {
                if (!MediaTypes.matches(a, p)) continue;
                double spec = (!p.isWildcardType() ? 2 : 0) + (!p.isWildcardSubtype() ? 1 : 0);
                if (spec > best) best = spec;
            }
        }
        return best;
    }

    /** q-value of the MOST SPECIFIC Accept matching an @Produces.
     *  §3.7.2 / §3.8: to resolve {@code clientImagePreferenceTest}
     *  (Accept "image/something;q=0.1, image/*;q=0.9" + @Produces "image/*"),
     *  we must keep the most precise matching Accept: for @Produces
     *  image/*, that is image/something (concrete > wildcard) → q=0.1, not
     *  the wildcard's q=0.9. This allows @Produces image/png (which only matches
     *  image/* with q=0.9) to win. */
    private static double bestAcceptQuality(List<MediaType> accepts, List<MediaType> produces) {
        double bestQ = 0;
        int bestSpec = -1;
        for (MediaType a : accepts) {
            for (MediaType p : produces) {
                if (!MediaTypes.matches(a, p)) continue;
                int aSpec = (!a.isWildcardType() ? 2 : 0) + (!a.isWildcardSubtype() ? 1 : 0);
                double q = MediaTypes.quality(a);
                if (aSpec > bestSpec || (aSpec == bestSpec && q > bestQ)) {
                    bestSpec = aSpec;
                    bestQ = q;
                }
            }
        }
        return bestQ;
    }

    /** Returns the specificity of the most precise @Consumes that matches ct. */
    private static double consumesSpecificity(MediaType ct, java.util.List<MediaType> consumes) {
        if (ct == null || consumes.isEmpty()) return 0;
        double best = 0;
        for (MediaType c : consumes) {
            if (!MediaTypes.consumesMatches(ct, java.util.List.of(c))) continue;
            double spec = (!c.isWildcardType() ? 2 : 0) + (!c.isWildcardSubtype() ? 1 : 0);
            if (spec > best) best = spec;
        }
        return best;
    }
}
