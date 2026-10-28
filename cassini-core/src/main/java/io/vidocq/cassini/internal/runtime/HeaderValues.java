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
package io.vidocq.cassini.internal.runtime;

import jakarta.ws.rs.core.EntityTag;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.net.URI;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Typed readers of header values, shared by the server-side
 * {@link CassiniResponse} and the filter-side
 * {@code io.vidocq.cassini.internal.filter.CassiniResponseContext} (both expose
 * the same {@code getLength()}, {@code getEntityTag()}, {@code getLinks()}… accessors
 * over a {@code MultivaluedMap<String, Object>} of headers) and by the request-side
 * precondition evaluation.
 *
 * <p>Stateless static methods only: no allocation beyond what each accessor
 * already needs to build its result.
 */
public final class HeaderValues {

    /** Formats accepted for HTTP dates (RFC 9110 §5.6.7: IMF-fixdate, obsolete RFC 850, asctime). */
    private static final String[] HTTP_DATE_FORMATS = {
            "EEE, dd MMM yyyy HH:mm:ss zzz",
            "EEEE, dd-MMM-yy HH:mm:ss zzz",
            "EEE MMM d HH:mm:ss yyyy"};

    private HeaderValues() {
    }

    /** {@code Content-Language} as a {@link Locale}, or {@code null} when absent. */
    public static Locale language(MultivaluedMap<String, Object> headers) {
        Object v = headers.getFirst("Content-Language");
        return v == null ? null : Locale.forLanguageTag(v.toString());
    }

    /** {@code Content-Length}, or {@code -1} when absent or not a number. */
    public static int length(MultivaluedMap<String, Object> headers) {
        Object v = headers.getFirst("Content-Length");
        if (v == null) return -1;
        try {
            return Integer.parseInt(v.toString());
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /** {@code ETag}, parsed when it is held as a string; {@code null} when absent or malformed. */
    public static EntityTag entityTag(MultivaluedMap<String, Object> headers) {
        Object v = headers.getFirst("ETag");
        if (v == null) return null;
        if (v instanceof EntityTag et) return et;
        try {
            return RuntimeDelegate.getInstance().createHeaderDelegate(EntityTag.class).fromString(v.toString());
        } catch (RuntimeException _) {
            return null;
        }
    }

    /** {@code Location} as a {@link URI}, or {@code null} when absent. */
    public static URI location(MultivaluedMap<String, Object> headers) {
        Object v = headers.getFirst("Location");
        return v == null ? null : URI.create(v.toString());
    }

    /** Every {@code Link} header, parsed when held as a string; malformed values are skipped. */
    public static Set<Link> links(MultivaluedMap<String, Object> headers) {
        List<Object> vs = headers.get("Link");
        if (vs == null || vs.isEmpty()) return Set.of();
        Set<Link> out = new LinkedHashSet<>();
        for (Object v : vs) {
            Link parsed = v instanceof Link l ? l : parseLink(v.toString());
            if (parsed != null) out.add(parsed);
        }
        return out;
    }

    /** The first link of {@code links} whose {@code rel} is {@code relation}, or {@code null}. */
    public static Link link(Set<Link> links, String relation) {
        if (relation == null) return null;
        for (Link l : links) {
            if (relation.equals(l.getRel())) return l;
        }
        return null;
    }

    /** A date header value: a {@link Date} as is, or a string in one of the HTTP date formats; else {@code null}. */
    public static Date httpDate(Object v) {
        if (v == null) return null;
        if (v instanceof Date d) return d;
        String s = v.toString();
        for (String fmt : HTTP_DATE_FORMATS) {
            try {
                return new SimpleDateFormat(fmt, Locale.US).parse(s);
            } catch (ParseException _) {
                // not this format: try the next one
            }
        }
        return null;
    }

    /** The header form of {@code tag} ({@code "value"} or {@code W/"value"}). */
    public static String format(EntityTag tag) {
        return RuntimeDelegate.getInstance().createHeaderDelegate(EntityTag.class).toString(tag);
    }

    private static Link parseLink(String raw) {
        try {
            return Link.valueOf(raw);
        } catch (RuntimeException _) {
            return null;
        }
    }
}
