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
package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.internal.MediaTypes;
import io.vidocq.cassini.internal.runtime.HeaderValues;
import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Variant;

import java.util.Date;
import java.util.Locale;
import java.util.List;

/**
 * {@link jakarta.ws.rs.core.Request} implementation with support for
 * {@link #evaluatePreconditions} (§4.5) and {@link #selectVariant} (§5.1).
 */
public final class CassiniRequest implements jakarta.ws.rs.core.Request {

    /** Exchange attribute key for the Vary dimensions collected by selectVariant (M2h). */
    public static final String ATTR_PENDING_VARY = "cassini.pending_vary";

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

        // §5.1: negotiation dimensions = all those present on at least one Variant.
        // We set Vary for these dimensions, independently of the final selection
        // (the client must know how to vary its request).
        boolean anyMedia = variants.stream().anyMatch(v -> v.getMediaType() != null);
        boolean anyLang = variants.stream().anyMatch(v -> v.getLanguage() != null);
        boolean anyEnc = variants.stream().anyMatch(v -> v.getEncoding() != null);
        addPendingVary(anyMedia, anyLang, anyEnc);

        for (Variant v : variants) {
            if (acceptable(v, accepts, langWildcard ? List.of() : langs, encWildcard ? null : acceptEnc)) return v;
        }
        return null;
    }

    /** Records the negotiated dimensions for the {@code Vary} response header. */
    private void addPendingVary(boolean anyMedia, boolean anyLang, boolean anyEnc) {
        @SuppressWarnings("unchecked")
        java.util.Set<String> vary = (java.util.Set<String>) delegate.getAttribute(ATTR_PENDING_VARY);
        if (vary == null) {
            vary = new java.util.LinkedHashSet<>();
            delegate.setAttribute(ATTR_PENDING_VARY, vary);
        }
        if (anyMedia) vary.add("Accept");
        if (anyLang) vary.add("Accept-Language");
        if (anyEnc) vary.add("Accept-Encoding");
    }

    /** True when every dimension {@code v} declares is accepted; an empty {@code langs} or a
     *  {@code null} {@code acceptEnc} accepts any language or encoding. */
    private static boolean acceptable(Variant v, List<MediaType> accepts, List<Locale> langs, String acceptEnc) {
        if (v.getMediaType() != null && !accepts.isEmpty()
                && accepts.stream().noneMatch(a -> MediaTypes.matches(a, v.getMediaType()))) {
            return false;
        }
        if (v.getLanguage() != null && !langs.isEmpty()
                && langs.stream().noneMatch(l -> sameLang(l, v.getLanguage()))) {
            return false;
        }
        return v.getEncoding() == null || acceptEnc == null
                || acceptEnc.toLowerCase().contains(v.getEncoding().toLowerCase());
    }

    /** RFC 7231 §5.3: an Accept-Language/Encoding may contain "*" which matches
     *  any value (or as part of a list, e.g. "en-US, *;q=0.5"). */
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
        String tag = HeaderValues.format(eTag);
        for (String tok : header.split(",")) {
            if (tok.trim().equals(tag)) return true;
            // ignore weak/strong distinction in MVP
            if (tok.trim().replace("W/", "").equals(tag.replace("W/", ""))) return true;
        }
        return false;
    }

    private static Date parseHttpDate(String s) {
        return HeaderValues.httpDate(s);
    }
}
