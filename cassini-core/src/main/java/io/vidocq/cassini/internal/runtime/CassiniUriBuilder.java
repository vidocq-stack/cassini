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

import jakarta.ws.rs.core.UriBuilder;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link UriBuilder} implementation aligned with RFC 3986 and §6 of the
 * Jakarta REST 4.0 spec. Encoding is applied component by component:
 * PATH, QUERY_PARAM, MATRIX_PARAM, FRAGMENT. {@code %XX} triplets
 * are preserved (template mode) for values passed to
 * {@code xxxParam}/{@code path} methods, but re-encoded for template values
 * substituted in non-encoded {@code build*()} mode.
 */
public final class CassiniUriBuilder extends UriBuilder {

    private enum Comp { PATH, QUERY_PARAM, MATRIX_PARAM, FRAGMENT }

    private String scheme;
    private String ssp;            // pour URI opaques (mailto:, urn:, …)
    private String userInfo;
    private String host;
    private int port = -1;
    private StringBuilder path = new StringBuilder();
    private final Map<String, List<String>> query = new LinkedHashMap<>();
    private String fragment;
    /** Resolved values + whether the value is already encoded (FromEncoded). */
    private final Map<String, Object> resolvedTemplates = new LinkedHashMap<>();
    private final Map<String, Boolean> resolvedTemplatesEncoded = new LinkedHashMap<>();
    /** Per variable: should '/' characters contained in the value be encoded? */
    private final Map<String, Boolean> resolvedTemplatesEncodeSlash = new LinkedHashMap<>();

    public CassiniUriBuilder() {}

    // --- Factories ---

    public static UriBuilder fromResource(Class<?> resource) {
        if (resource == null) throw new IllegalArgumentException("resource is null");
        jakarta.ws.rs.Path p = resource.getAnnotation(jakarta.ws.rs.Path.class);
        if (p == null) throw new IllegalArgumentException("resource not @Path annotated: " + resource.getName());
        CassiniUriBuilder b = new CassiniUriBuilder();
        b.path.append(p.value());
        return b;
    }

    public static UriBuilder fromUri(URI uri) {
        if (uri == null) throw new IllegalArgumentException("uri is null");
        CassiniUriBuilder b = new CassiniUriBuilder();
        b.copyFrom(uri);
        return b;
    }

    public static UriBuilder fromUri(String uri) {
        if (uri == null) throw new IllegalArgumentException("uri is null");
        try { return fromUri(new URI(uri)); }
        catch (URISyntaxException e) { throw new IllegalArgumentException(e); }
    }

    private void copyFrom(URI uri) {
        // §6.4.2: uri(URI) merge — non-null components from the new URI
        // replace the current state, null components leave it unchanged.
        if (uri.getScheme() != null) this.scheme = uri.getScheme();
        if (uri.isOpaque()) {
            this.ssp = uri.getRawSchemeSpecificPart();
            this.userInfo = null; this.host = null; this.port = -1;
            this.path.setLength(0); this.query.clear();
        } else {
            // Switching to hierarchical → the ssp of a former opaque URI
            // (e.g. mailto:foo@bar) becomes obsolete: its content is carried
            // through the path.
            this.ssp = null;
            if (uri.getHost() != null) {
                this.userInfo = uri.getRawUserInfo();
                this.host = uri.getHost();
                this.port = uri.getPort();
            }
            if (uri.getRawPath() != null && !uri.getRawPath().isEmpty()) {
                this.path.setLength(0); this.path.append(uri.getRawPath());
            }
            String q = uri.getRawQuery();
            if (q != null) { this.query.clear(); parseQueryInto(q); }
        }
        if (uri.getRawFragment() != null) this.fragment = uri.getRawFragment();
    }

    private void parseQueryInto(String q) {
        for (String pair : q.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            // null = pair without '=' (e.g. "foo"), "" = pair with empty '=' (e.g. "foo=").
            String v = eq < 0 ? null : pair.substring(eq + 1);
            query.computeIfAbsent(k, x -> new ArrayList<>()).add(v);
        }
    }

    @Override public UriBuilder clone() {
        CassiniUriBuilder b = new CassiniUriBuilder();
        b.scheme = scheme; b.ssp = ssp;
        b.userInfo = userInfo; b.host = host; b.port = port;
        b.path = new StringBuilder(path);
        for (var e : query.entrySet()) b.query.put(e.getKey(), new ArrayList<>(e.getValue()));
        b.fragment = fragment;
        b.resolvedTemplates.putAll(resolvedTemplates);
        b.resolvedTemplatesEncoded.putAll(resolvedTemplatesEncoded);
        b.resolvedTemplatesEncodeSlash.putAll(resolvedTemplatesEncodeSlash);
        return b;
    }

    // --- URI/uriString ---

    @Override public UriBuilder uri(URI uri) {
        if (uri == null) throw new IllegalArgumentException("uri is null");
        copyFrom(uri);
        return this;
    }

    @Override public UriBuilder uri(String uriTemplate) {
        if (uriTemplate == null) throw new IllegalArgumentException("uri is null");
        // §11.1: must throw IllegalArgumentException if the string is not
        // a valid URI (and is not a template containing {name}).
        try {
            URI u = new URI(uriTemplate);
            return uri(u);
        } catch (URISyntaxException e) {
            if (!uriTemplate.contains("{")) {
                throw new IllegalArgumentException("Invalid URI: " + uriTemplate, e);
            }
            return parseUriTemplate(uriTemplate);
        }
    }

    private UriBuilder parseUriTemplate(String s) {
        // Minimal parser: scheme://authority/path?query#fragment
        int fragIdx = s.indexOf('#');
        String body = fragIdx < 0 ? s : s.substring(0, fragIdx);
        String frag = fragIdx < 0 ? null : s.substring(fragIdx + 1);
        int qIdx = body.indexOf('?');
        String beforeQ = qIdx < 0 ? body : body.substring(0, qIdx);
        String q = qIdx < 0 ? null : body.substring(qIdx + 1);
        int colonIdx = beforeQ.indexOf(':');
        int slashSlash = beforeQ.indexOf("//");
        String sch = null, rest;
        if (colonIdx > 0 && (slashSlash < 0 || colonIdx < slashSlash)) {
            sch = beforeQ.substring(0, colonIdx);
            rest = beforeQ.substring(colonIdx + 1);
        } else { rest = beforeQ; }
        String auth = null, pathPart;
        if (rest.startsWith("//")) {
            int slash = rest.indexOf('/', 2);
            if (slash < 0) { auth = rest.substring(2); pathPart = ""; }
            else { auth = rest.substring(2, slash); pathPart = rest.substring(slash); }
        } else { pathPart = rest; }
        this.scheme = sch;
        if (auth != null) {
            int at = auth.indexOf('@');
            String hostPort = at < 0 ? auth : auth.substring(at + 1);
            this.userInfo = at < 0 ? null : auth.substring(0, at);
            int colon = hostPort.lastIndexOf(':');
            if (colon >= 0) {
                this.host = hostPort.substring(0, colon);
                try { this.port = Integer.parseInt(hostPort.substring(colon + 1)); }
                catch (NumberFormatException nfe) { this.port = -1; }
            } else { this.host = hostPort; }
        }
        this.path.setLength(0);
        if (!pathPart.isEmpty()) this.path.append(pathPart);
        if (q != null) { this.query.clear(); parseQueryInto(q); }
        if (frag != null) this.fragment = frag;
        return this;
    }

    @Override public UriBuilder scheme(String scheme) { this.scheme = scheme; return this; }

    @Override public UriBuilder schemeSpecificPart(String ssp) {
        if (ssp == null) throw new IllegalArgumentException("ssp is null");
        // Minimal validation: must parse as a URI (scheme:ssp).
        try {
            URI u = new URI((scheme == null ? "http" : scheme) + ":" + ssp);
            if (u.isOpaque()) {
                this.ssp = u.getRawSchemeSpecificPart();
                this.userInfo = null; this.host = null; this.port = -1;
                this.path.setLength(0); this.query.clear();
            } else {
                // Hierarchical URI: propagate authority/path/query.
                this.ssp = null;
                if (u.getRawUserInfo() != null) this.userInfo = u.getRawUserInfo();
                if (u.getHost() != null) this.host = u.getHost();
                if (u.getPort() != -1) this.port = u.getPort();
                if (u.getRawPath() != null) { this.path.setLength(0); this.path.append(u.getRawPath()); }
                if (u.getRawQuery() != null) { this.query.clear(); parseQueryInto(u.getRawQuery()); }
            }
        } catch (URISyntaxException e) { throw new IllegalArgumentException(e); }
        return this;
    }

    @Override public UriBuilder userInfo(String ui) { this.userInfo = ui; return this; }

    @Override public UriBuilder host(String host) {
        if (host != null && host.isEmpty()) throw new IllegalArgumentException("host is empty");
        if (host != null) {
            // Validate that scheme://host can be formed without a syntax error.
            try { new URI(scheme == null ? "http" : scheme, null, host, -1, "/", null, null); }
            catch (URISyntaxException e) { throw new IllegalArgumentException(e); }
        }
        this.host = host;
        return this;
    }

    @Override public UriBuilder port(int port) {
        if (port < -1) throw new IllegalArgumentException("port must be >= -1");
        this.port = port;
        return this;
    }

    // --- Path ---

    @Override public UriBuilder replacePath(String p) { this.path = new StringBuilder(p == null ? "" : p); return this; }

    @Override public UriBuilder path(String segment) {
        if (segment == null) throw new IllegalArgumentException("path is null");
        if (segment.isEmpty()) return this;
        // §6: avoid double slashes when joining. Add a separator if
        // needed and deduplicate any leading slash on the segment.
        boolean endsWithSlash = path.length() > 0 && path.charAt(path.length() - 1) == '/';
        boolean startsWithSlash = segment.startsWith("/");
        if (path.length() > 0 && !endsWithSlash && !startsWithSlash) {
            path.append('/');
        } else if (endsWithSlash && startsWithSlash) {
            segment = segment.substring(1);
        }
        path.append(segment);
        return this;
    }

    @Override @SuppressWarnings("rawtypes") public UriBuilder path(Class resource) {
        if (resource == null) throw new IllegalArgumentException("resource is null");
        jakarta.ws.rs.Path p = (jakarta.ws.rs.Path) resource.getAnnotation(jakarta.ws.rs.Path.class);
        if (p == null) throw new IllegalArgumentException("resource is not @Path annotated: " + resource.getName());
        path(p.value());
        return this;
    }

    @Override @SuppressWarnings({"rawtypes","unchecked"}) public UriBuilder path(Class resource, String method) {
        if (resource == null) throw new IllegalArgumentException("resource is null");
        if (method == null) throw new IllegalArgumentException("method is null");
        java.lang.reflect.Method found = null;
        for (java.lang.reflect.Method m : resource.getMethods()) {
            if (!m.getName().equals(method)) continue;
            if (m.getAnnotation(jakarta.ws.rs.Path.class) == null) continue;
            if (found != null) throw new IllegalArgumentException(
                    "Multiple methods named '" + method + "' annotated with @Path on " + resource.getName());
            found = m;
        }
        if (found == null) throw new IllegalArgumentException(
                "No method '" + method + "' with @Path on " + resource.getName());
        path(found.getAnnotation(jakarta.ws.rs.Path.class).value());
        return this;
    }

    @Override public UriBuilder path(java.lang.reflect.Method method) {
        if (method == null) throw new IllegalArgumentException("method is null");
        jakarta.ws.rs.Path p = method.getAnnotation(jakarta.ws.rs.Path.class);
        if (p == null) throw new IllegalArgumentException("method is not @Path annotated: " + method);
        path(p.value());
        return this;
    }

    @Override public UriBuilder segment(String... segments) {
        if (segments == null) throw new IllegalArgumentException("segments is null");
        for (String s : segments) {
            if (s == null) throw new IllegalArgumentException("segment is null");
            // Encode the segment as a path segment: '/' is always encoded as %2F
            String enc = encode(s, Comp.PATH, true, true);
            if (path.length() > 0 && path.charAt(path.length() - 1) != '/') path.append('/');
            path.append(enc);
        }
        return this;
    }

    // --- Matrix params ---

    @Override public UriBuilder replaceMatrix(String m) {
        int lastSlash = path.lastIndexOf("/");
        int keepFrom = lastSlash < 0 ? 0 : lastSlash;
        int semi = path.indexOf(";", keepFrom);
        if (semi >= 0) path.setLength(semi);
        if (m != null && !m.isEmpty()) path.append(';').append(m);
        return this;
    }

    @Override public UriBuilder matrixParam(String name, Object... values) {
        if (name == null) throw new IllegalArgumentException("name is null");
        if (values == null) throw new IllegalArgumentException("values is null");
        for (Object v : values) {
            if (v == null) throw new IllegalArgumentException("matrix value is null");
            path.append(';').append(encode(name, Comp.MATRIX_PARAM, true, true))
                .append('=').append(encode(String.valueOf(v), Comp.MATRIX_PARAM, true, true));
        }
        return this;
    }

    @Override public UriBuilder replaceMatrixParam(String name, Object... values) {
        if (name == null) throw new IllegalArgumentException("name is null");
        int lastSlash = path.lastIndexOf("/");
        int start = lastSlash < 0 ? 0 : lastSlash;
        StringBuilder rebuilt = new StringBuilder();
        rebuilt.append(path, 0, start);
        String segment = path.substring(start);
        String[] parts = segment.split(";");
        boolean first = true;
        String encName = encode(name, Comp.MATRIX_PARAM, true, true);
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (!first && (p.startsWith(encName + "=") || p.equals(encName))) continue;
            if (!first) rebuilt.append(';');
            rebuilt.append(p);
            first = false;
        }
        path.setLength(0);
        path.append(rebuilt);
        if (values != null && values.length > 0) matrixParam(name, values);
        return this;
    }

    // --- Query params ---

    @Override public UriBuilder replaceQuery(String q) {
        query.clear();
        if (q == null || q.isEmpty()) return this;
        parseQueryInto(q);
        return this;
    }

    @Override public UriBuilder queryParam(String name, Object... values) {
        if (name == null) throw new IllegalArgumentException("name is null");
        if (values == null) throw new IllegalArgumentException("values is null");
        for (Object v : values) {
            if (v == null) throw new IllegalArgumentException("query value is null");
            // §3.7.4.4: queryParam values encode spaces as '+' (HTML form style,
            // confirmed by TCK queryParamTest5). replaceQuery, by contrast,
            // keeps the raw value and encode-rebuilds it as %20.
            String formEncoded = String.valueOf(v).replace(" ", "+");
            query.computeIfAbsent(encode(name, Comp.QUERY_PARAM, true, true), k -> new ArrayList<>())
                 .add(encode(formEncoded, Comp.QUERY_PARAM, true, true));
        }
        return this;
    }

    @Override public UriBuilder replaceQueryParam(String name, Object... values) {
        if (name == null) throw new IllegalArgumentException("name is null");
        query.remove(encode(name, Comp.QUERY_PARAM, true, true));
        if (values != null && values.length > 0) queryParam(name, values);
        return this;
    }

    @Override public UriBuilder fragment(String f) { this.fragment = f; return this; }

    // --- Resolve template ---

    @Override public UriBuilder resolveTemplate(String name, Object value) {
        return resolveTemplate(name, value, true);
    }

    @Override public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) {
        if (name == null) throw new IllegalArgumentException("name is null");
        if (value == null) throw new IllegalArgumentException("value is null");
        resolvedTemplates.put(name, value);
        resolvedTemplatesEncoded.put(name, false);
        resolvedTemplatesEncodeSlash.put(name, encodeSlashInPath);
        return this;
    }

    @Override public UriBuilder resolveTemplateFromEncoded(String name, Object value) {
        if (name == null) throw new IllegalArgumentException("name is null");
        if (value == null) throw new IllegalArgumentException("value is null");
        resolvedTemplates.put(name, value);
        resolvedTemplatesEncoded.put(name, true);
        resolvedTemplatesEncodeSlash.put(name, false);
        return this;
    }

    @Override public UriBuilder resolveTemplates(Map<String, Object> values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        for (var e : values.entrySet()) resolveTemplate(e.getKey(), e.getValue());
        return this;
    }

    @Override public UriBuilder resolveTemplates(Map<String, Object> values, boolean encodeSlashInPath) {
        if (values == null) throw new IllegalArgumentException("values is null");
        for (var e : values.entrySet()) resolveTemplate(e.getKey(), e.getValue(), encodeSlashInPath);
        return this;
    }

    @Override public UriBuilder resolveTemplatesFromEncoded(Map<String, Object> values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        for (var e : values.entrySet()) resolveTemplateFromEncoded(e.getKey(), e.getValue());
        return this;
    }

    // --- Build ---

    @Override public URI buildFromMap(Map<String, ?> values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(null, values, true, false);
    }
    @Override public URI buildFromMap(Map<String, ?> values, boolean encodeSlashInPath) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(null, values, encodeSlashInPath, false);
    }
    @Override public URI buildFromEncodedMap(Map<String, ?> values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(null, values, true, true);
    }

    @Override public URI build(Object... values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(values, null, true, false);
    }
    @Override public URI build(Object[] values, boolean encodeSlashInPath) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(values, null, encodeSlashInPath, false);
    }
    @Override public URI buildFromEncoded(Object... values) {
        if (values == null) throw new IllegalArgumentException("values is null");
        return buildInternal(values, null, true, true);
    }

    private URI buildInternal(Object[] values, Map<String, ?> valueMap,
                              boolean encodeSlash, boolean valuesAlreadyEncoded) {
        Map<String, Object> seen = new LinkedHashMap<>();
        int[] posIdx = new int[]{0};
        String pathOut = substituteLiteralAndTemplates(path.toString(), values, valueMap,
                seen, posIdx, Comp.PATH, encodeSlash, valuesAlreadyEncoded);
        String fragmentOut = substituteLiteralAndTemplates(fragment, values, valueMap,
                seen, posIdx, Comp.FRAGMENT, true, valuesAlreadyEncoded);
        StringBuilder q = new StringBuilder();
        for (var e : query.entrySet()) {
            for (String v : e.getValue()) {
                if (q.length() > 0) q.append('&');
                q.append(e.getKey());
                if (v != null) {
                    String subst = substituteLiteralAndTemplates(v, values, valueMap,
                            seen, posIdx, Comp.QUERY_PARAM, true, valuesAlreadyEncoded);
                    q.append('=').append(subst);
                }
            }
        }
        String queryOut = q.length() == 0 ? null : q.toString();

        // Raw assembly (the parts are already encoded).
        StringBuilder sb = new StringBuilder();
        if (scheme != null) sb.append(scheme).append(':');
        if (ssp != null && !ssp.isEmpty() && host == null) {
            sb.append(ssp);
        } else {
            boolean hasAuthority = host != null && !host.isEmpty();
            if (hasAuthority) {
                sb.append("//");
                if (userInfo != null) sb.append(userInfo).append('@');
                sb.append(host);
                if (port >= 0) sb.append(':').append(port);
            }
            if (pathOut != null) sb.append(pathOut);
            if (queryOut != null) sb.append('?').append(queryOut);
        }
        if (fragmentOut != null) sb.append('#').append(fragmentOut);
        try { return new URI(sb.toString()); }
        catch (URISyntaxException e) { throw new jakarta.ws.rs.core.UriBuilderException(e); }
    }

    /** Walks the template: literal segments encoded while preserving
     *  {@code %XX} triplets (already typed), with {name} values encoded according to {@code valuesAlreadyEncoded}. */
    private String substituteLiteralAndTemplates(String tpl, Object[] values, Map<String, ?> valueMap,
                                                 Map<String, Object> seen, int[] posIdx,
                                                 Comp comp, boolean encodeSlash,
                                                 boolean valuesAlreadyEncoded) {
        if (tpl == null || tpl.isEmpty()) return tpl;
        StringBuilder out = new StringBuilder();
        int i = 0;
        int litStart = 0;
        while (i < tpl.length()) {
            char c = tpl.charAt(i);
            if (c == '{') {
                int end = tpl.indexOf('}', i);
                if (end < 0) break;
                // Flush literal
                if (i > litStart) out.append(encode(tpl.substring(litStart, i), comp, true, false));
                String inside = tpl.substring(i + 1, end).trim();
                int colon = inside.indexOf(':');
                String name = colon < 0 ? inside : inside.substring(0, colon).trim();
                Object val;
                boolean valEncoded = valuesAlreadyEncoded;
                // §6: when the value is already pre-encoded (buildFromEncoded*),
                // any '/' it contains must be preserved — it
                // represents what the caller chose to emit.
                boolean valEncodeSlash = valuesAlreadyEncoded ? false : encodeSlash;
                if (resolvedTemplates.containsKey(name)) {
                    val = resolvedTemplates.get(name);
                    valEncoded = resolvedTemplatesEncoded.getOrDefault(name, false);
                    valEncodeSlash = resolvedTemplatesEncodeSlash.getOrDefault(name, encodeSlash);
                } else if (valueMap != null && valueMap.containsKey(name)) {
                    val = valueMap.get(name);
                } else if (seen.containsKey(name)) {
                    val = seen.get(name);
                } else if (values != null && posIdx[0] < values.length) {
                    val = values[posIdx[0]++];
                    seen.put(name, val);
                } else {
                    throw new IllegalArgumentException(
                            "No value supplied for template parameter " + name);
                }
                if (val == null) throw new IllegalArgumentException(
                        "Null value supplied for template parameter " + name);
                out.append(encode(String.valueOf(val), comp, valEncoded, valEncodeSlash));
                i = end + 1;
                litStart = i;
            } else { i++; }
        }
        if (litStart < tpl.length()) out.append(encode(tpl.substring(litStart), comp, true, false));
        return out.toString();
    }

    @Override public String toTemplate() {
        StringBuilder sb = new StringBuilder();
        if (scheme != null) sb.append(scheme).append(':');
        if (host != null) {
            sb.append("//");
            if (userInfo != null) sb.append(userInfo).append('@');
            sb.append(host);
            if (port >= 0) sb.append(':').append(port);
        }
        sb.append(resolveStoredTemplates(path.toString()));
        if (!query.isEmpty()) {
            sb.append('?');
            boolean first = true;
            for (var e : query.entrySet())
                for (String v : e.getValue()) {
                    if (!first) sb.append('&'); first = false;
                    sb.append(e.getKey());
                    if (v != null) sb.append('=').append(resolveStoredTemplates(v));
                }
        }
        if (fragment != null) sb.append('#').append(resolveStoredTemplates(fragment));
        return sb.toString();
    }

    private String resolveStoredTemplates(String tpl) {
        return resolveStoredTemplates(tpl, Comp.PATH);
    }

    private String resolveStoredTemplates(String tpl, Comp comp) {
        if (tpl == null || tpl.isEmpty() || resolvedTemplates.isEmpty()) return tpl;
        StringBuilder out = new StringBuilder();
        int i = 0;
        while (i < tpl.length()) {
            char c = tpl.charAt(i);
            if (c == '{') {
                int end = tpl.indexOf('}', i);
                if (end < 0) { out.append(tpl, i, tpl.length()); break; }
                String inside = tpl.substring(i + 1, end).trim();
                int colon = inside.indexOf(':');
                String name = colon < 0 ? inside : inside.substring(0, colon).trim();
                if (resolvedTemplates.containsKey(name)) {
                    boolean enc = resolvedTemplatesEncoded.getOrDefault(name, false);
                    boolean encSlash = resolvedTemplatesEncodeSlash.getOrDefault(name, true);
                    out.append(encode(String.valueOf(resolvedTemplates.get(name)), comp, enc, encSlash));
                } else out.append('{').append(inside).append('}');
                i = end + 1;
            } else { out.append(c); i++; }
        }
        return out.toString();
    }

    // --- Encoding helpers ---

    private static final String UNRESERVED_EXTRA = "-._~";

    private static boolean isUnreserved(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                || UNRESERVED_EXTRA.indexOf(c) >= 0;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'A' && c <= 'F') || (c >= 'a' && c <= 'f');
    }

    private static boolean allowed(Comp comp, char c, boolean encodeSlash) {
        // Sous-delims selon composant.
        switch (comp) {
            case PATH:
                if (c == '/') return !encodeSlash;
                // pchar = unreserved / pct-encoded / sub-delims / ":" / "@"
                return "!$&'()*+,;=:@".indexOf(c) >= 0;
            case QUERY_PARAM:
                // query chars, but '&' and '=' are separators → encode
                return "!$'()*+,;:@/?".indexOf(c) >= 0;
            case FRAGMENT:
                return "!$&'()*+,;=:@/?".indexOf(c) >= 0;
            case MATRIX_PARAM:
                // ';' and '=' are separators → encode
                return "!$&'()*+,:@/".indexOf(c) >= 0;
        }
        return false;
    }

    /**
     * Encodes {@code s} for the given component.
     *
     * @param preservePercent if {@code true}, a valid already-encoded {@code %XX}
     *                       sequence is preserved without re-encoding. If {@code false},
     *                       {@code %} is encoded as {@code %25}.
     * @param encodeSlash    applique seulement au composant PATH : {@code true}
     *                       encode {@code /} en {@code %2F}.
     */
    private static String encode(String s, Comp comp, boolean preservePercent, boolean encodeSlash) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (preservePercent && c == '%' && i + 2 < s.length()
                    && isHex(s.charAt(i + 1)) && isHex(s.charAt(i + 2))) {
                out.append('%').append(s.charAt(i + 1)).append(s.charAt(i + 2));
                i += 3;
                continue;
            }
            // §3.7.4.4 : RFC 3986 dans une query d'URI exige '%20' (et non '+'
            // qui est la convention application/x-www-form-urlencoded).
            // Le test TCK replaceQueryTest3 valide explicitement %20.
            if (isUnreserved(c) || allowed(comp, c, encodeSlash)) {
                out.append(c);
                i++;
            } else {
                byte[] bytes = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    out.append('%');
                    out.append(Character.toUpperCase(Character.forDigit((b >> 4) & 0xF, 16)));
                    out.append(Character.toUpperCase(Character.forDigit(b & 0xF, 16)));
                }
                i++;
            }
        }
        return out.toString();
    }
}
