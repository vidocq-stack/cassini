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

import io.vidocq.cassini.internal.MediaTypes;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Implémentation minimale de {@link RuntimeDelegate} pour débloquer
 * {@code Response.ok()}, {@code MediaType.toString()} et
 * {@link UriBuilder} côté code utilisateur.
 */
public class CassiniRuntimeDelegate extends RuntimeDelegate {

    @Override public UriBuilder createUriBuilder() { return new CassiniUriBuilder(); }

    // Expose fromResource/fromMethod via UriBuilder.fromResource side (static fallback).
    // UriBuilder.fromResource() calls createUriBuilder().uri(...) internally in spec,
    // but CassiniUriBuilder.fromResource(Class) is our own helper.

    @Override public Response.ResponseBuilder createResponseBuilder() { return new CassiniResponseBuilder(); }

    @Override public Variant.VariantListBuilder createVariantListBuilder() {
        return new StubVariantListBuilder();
    }

    @Override public <T> T createEndpoint(Application application, Class<T> endpointType) {
        if (application == null) throw new IllegalArgumentException("application is null");
        if (endpointType == null) throw new IllegalArgumentException("endpointType is null");
        throw new UnsupportedOperationException("createEndpoint not supported");
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HeaderDelegate<T> createHeaderDelegate(Class<T> type) {
        if (type == null) throw new IllegalArgumentException("type is null");
        if (type == MediaType.class) return (HeaderDelegate<T>) new MediaTypeDelegate();
        if (type == jakarta.ws.rs.core.NewCookie.class) return (HeaderDelegate<T>) new NewCookieDelegate();
        if (type == jakarta.ws.rs.core.Cookie.class) return (HeaderDelegate<T>) new CookieDelegate();
        if (type == jakarta.ws.rs.core.EntityTag.class) return (HeaderDelegate<T>) new EntityTagDelegate();
        if (type == jakarta.ws.rs.core.CacheControl.class) return (HeaderDelegate<T>) new CacheControlDelegate();
        if (type == jakarta.ws.rs.core.Link.class) return (HeaderDelegate<T>) new LinkDelegate();
        if (type == java.util.Date.class) return (HeaderDelegate<T>) new DateDelegate();
        return (HeaderDelegate<T>) new ToStringDelegate();
    }

    @Override public Link.Builder createLinkBuilder() {
        return new StubLinkBuilder();
    }

    @Override public EntityPart.Builder createEntityPartBuilder(String name) {
        return new io.vidocq.cassini.internal.multipart.CassiniEntityPartBuilder(name);
    }

    @Override public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        return new CassiniBootstrapConfigBuilder();
    }

    @Override public CompletionStage<SeBootstrap.Instance> bootstrap(Application application, SeBootstrap.Configuration config) {
        // SE-Bootstrap requires an HTTP transport — provided by cassini-chappe or
        // cassini-jdk-http which override this method via a subclass.
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "SeBootstrap requires a transport adapter (cassini-chappe or cassini-jdk-http) on the classpath"));
    }

    @Override public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz, SeBootstrap.Configuration config) {
        try {
            return bootstrap(clazz.getDeclaredConstructor().newInstance(), config);
        } catch (ReflectiveOperationException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private static final class CassiniBootstrapConfigBuilder implements SeBootstrap.Configuration.Builder {
        private final java.util.Map<String, Object> props = new java.util.HashMap<>();
        CassiniBootstrapConfigBuilder() {
            props.put(SeBootstrap.Configuration.PROTOCOL, "HTTP");
            props.put(SeBootstrap.Configuration.HOST, "localhost");
            props.put(SeBootstrap.Configuration.PORT, -1);
            props.put(SeBootstrap.Configuration.ROOT_PATH, "/");
        }
        @Override public SeBootstrap.Configuration.Builder property(String name, Object value) {
            props.put(name, value); return this;
        }
        @Override public <T> SeBootstrap.Configuration.Builder from(java.util.function.BiFunction<String, Class<T>, java.util.Optional<T>> src) {
             // §3.10 : queries the external function for standard keys.
            tryRead(src, SeBootstrap.Configuration.PROTOCOL, String.class);
            tryRead(src, SeBootstrap.Configuration.HOST, String.class);
            tryRead(src, SeBootstrap.Configuration.PORT, Integer.class);
            tryRead(src, SeBootstrap.Configuration.ROOT_PATH, String.class);
            tryRead(src, SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION,
                    SeBootstrap.Configuration.SSLClientAuthentication.class);
            tryRead(src, SeBootstrap.Configuration.SSL_CONTEXT, javax.net.ssl.SSLContext.class);
            return this;
        }
        @SuppressWarnings({"unchecked", "rawtypes"})
        private <T, V> void tryRead(java.util.function.BiFunction<String, Class<T>, java.util.Optional<T>> src,
                                    String key, Class<V> type) {
            try {
                Object raw = ((java.util.function.BiFunction) src).apply(key, type);
                if (raw instanceof java.util.Optional<?> opt) {
                    opt.ifPresent(v -> props.put(key, v));
                }
            } catch (RuntimeException ignored) {}
        }
        @Override public SeBootstrap.Configuration build() {
            return new CassiniBootstrapConfig(java.util.Map.copyOf(props));
        }
    }

    private record CassiniBootstrapConfig(java.util.Map<String, Object> props) implements SeBootstrap.Configuration {
        @Override public Object property(String name) { return props.get(name); }
    }

    private static final class MediaTypeDelegate implements HeaderDelegate<MediaType> {
        @Override public MediaType fromString(String value) {
            if (value == null) throw new IllegalArgumentException("value is null");
            return MediaTypes.parse(value);
        }
        @Override public String toString(MediaType value) {
            if (value == null) throw new IllegalArgumentException("value is null");
            return MediaTypes.format(value);
        }
    }

    private static final class ToStringDelegate implements HeaderDelegate<Object> {
        @Override public Object fromString(String value) {
            if (value == null) throw new IllegalArgumentException("value is null");
            return value;
        }
        @Override public String toString(Object value) {
            if (value == null) throw new IllegalArgumentException("value is null");
            return value.toString();
        }
    }

    private static final class NewCookieDelegate implements HeaderDelegate<jakarta.ws.rs.core.NewCookie> {
        @Override public jakarta.ws.rs.core.NewCookie fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            // Parsing basique name=value; attr=val; ...
            String[] parts = s.split(";");
            String name = null, value = null, path = null, domain = null, comment = null;
            int maxAge = -1; boolean secure = false, httpOnly = false;
            int version = 1;
            for (int i = 0; i < parts.length; i++) {
                String p = parts[i].trim();
                int eq = p.indexOf('=');
                String k = eq < 0 ? p : p.substring(0, eq).trim();
                String v = eq < 0 ? "" : stripQuotes(p.substring(eq + 1).trim());
                if (i == 0) { name = k; value = v; continue; }
                switch (k.toLowerCase(java.util.Locale.ROOT)) {
                    case "path" -> path = v;
                    case "domain" -> domain = v;
                    case "comment" -> comment = v;
                    case "max-age" -> { try { maxAge = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
                    case "version" -> { try { version = Integer.parseInt(v); } catch (NumberFormatException ignored) {} }
                    case "secure" -> secure = true;
                    case "httponly" -> httpOnly = true;
                    default -> { /* unknown attribute: ignored, like before */ }
                }
            }
            return new jakarta.ws.rs.core.NewCookie.Builder(name).value(value).path(path)
                    .domain(domain).comment(comment).maxAge(maxAge).version(version)
                    .secure(secure).httpOnly(httpOnly).build();
        }
        @Override public String toString(jakarta.ws.rs.core.NewCookie c) {
            if (c == null) throw new IllegalArgumentException("value is null");
            StringBuilder sb = new StringBuilder();
            sb.append(c.getName()).append('=').append(quoteIfNeeded(c.getValue()));
            if (c.getVersion() > 0) sb.append(";Version=").append(c.getVersion());
            if (c.getPath() != null) sb.append(";Path=").append(quoteIfNeeded(c.getPath()));
            if (c.getDomain() != null) sb.append(";Domain=").append(quoteIfNeeded(c.getDomain()));
            if (c.getMaxAge() != -1) sb.append(";Max-Age=").append(c.getMaxAge());
            if (c.getComment() != null) sb.append(";Comment=").append(quoteIfNeeded(c.getComment()));
            if (c.isSecure()) sb.append(";Secure");
            if (c.isHttpOnly()) sb.append(";HttpOnly");
            return sb.toString();
        }
    }

    private static final class CookieDelegate implements HeaderDelegate<jakarta.ws.rs.core.Cookie> {
        @Override public jakarta.ws.rs.core.Cookie fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            // Parsing RFC 2965 / 6265 : $Version=1; NAME=VALUE; $Path="/"; $Domain=".."
            // §4.3 : preserves the case of name and value (the test
            // checkCreatedHeaderDelegateCookieTest does a round-trip).
            String name = null, value = null, path = null, domain = null;
            int version = 0;
            for (String pair : s.split(";")) {
                String p = pair.trim();
                if (p.isEmpty()) continue;
                int eq = p.indexOf('=');
                String k = eq < 0 ? p : p.substring(0, eq).trim();
                String v = eq < 0 ? "" : stripQuotes(p.substring(eq + 1).trim());
                if ("$Version".equalsIgnoreCase(k)) {
                    try { version = Integer.parseInt(v); } catch (Exception ignored) {}
                } else if ("$Path".equalsIgnoreCase(k)) {
                    path = v;
                } else if ("$Domain".equalsIgnoreCase(k)) {
                    domain = v;
                } else if (name == null) {
                    name = k;
                    value = v;
                }
            }
            if (name == null) name = s.trim();
            jakarta.ws.rs.core.Cookie.Builder b = new jakarta.ws.rs.core.Cookie.Builder(name)
                    .value(value).version(version);
            if (path != null) b.path(path);
            if (domain != null) b.domain(domain);
            return b.build();
        }
        @Override public String toString(jakarta.ws.rs.core.Cookie c) {
            if (c == null) throw new IllegalArgumentException("value is null");
            StringBuilder sb = new StringBuilder();
            if (c.getVersion() > 0) sb.append("$Version=").append(c.getVersion()).append(";");
            sb.append(c.getName()).append('=').append(quoteIfNeeded(c.getValue()));
            if (c.getPath() != null) sb.append(";$Path=").append(quoteIfNeeded(c.getPath()));
            if (c.getDomain() != null) sb.append(";$Domain=").append(quoteIfNeeded(c.getDomain()));
            return sb.toString();
        }
    }

    private static final class EntityTagDelegate implements HeaderDelegate<jakarta.ws.rs.core.EntityTag> {
        @Override public jakarta.ws.rs.core.EntityTag fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            boolean weak = s.startsWith("W/");
            String tag = weak ? s.substring(2) : s;
            tag = stripQuotes(tag.trim());
            return new jakarta.ws.rs.core.EntityTag(tag, weak);
        }
        @Override public String toString(jakarta.ws.rs.core.EntityTag e) {
            if (e == null) throw new IllegalArgumentException("value is null");
            return (e.isWeak() ? "W/" : "") + "\"" + e.getValue() + "\"";
        }
    }

    private static final class CacheControlDelegate implements HeaderDelegate<jakarta.ws.rs.core.CacheControl> {
        @Override public jakarta.ws.rs.core.CacheControl fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            jakarta.ws.rs.core.CacheControl cc = new jakarta.ws.rs.core.CacheControl();
            cc.setNoTransform(false);
            for (String tok : splitDirectives(s)) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                int eq = t.indexOf('=');
                String name = eq < 0 ? t : t.substring(0, eq).trim();
                String value = eq < 0 ? "" : stripQuotes(t.substring(eq + 1).trim());
                switch (name.toLowerCase(java.util.Locale.ROOT)) {
                    case "no-cache" -> {
                        cc.setNoCache(true);
                        addFields(cc.getNoCacheFields(), value);
                    }
                    case "no-store" -> cc.setNoStore(true);
                    case "no-transform" -> cc.setNoTransform(true);
                    case "private" -> {
                        cc.setPrivate(true);
                        addFields(cc.getPrivateFields(), value);
                    }
                    case "must-revalidate" -> cc.setMustRevalidate(true);
                    case "proxy-revalidate" -> cc.setProxyRevalidate(true);
                    case "max-age" -> { try { cc.setMaxAge(Integer.parseInt(value)); } catch (NumberFormatException ignored) {} }
                    case "s-maxage" -> { try { cc.setSMaxAge(Integer.parseInt(value)); } catch (NumberFormatException ignored) {} }
                    // "public" and any unknown directive land in the extension map so
                    // they survive a round-trip (CacheControl does not model them).
                    default -> cc.getCacheExtension().put(name, value);
                }
            }
            return cc;
        }

        /** Splits on commas that are OUTSIDE double quotes (RFC 7234 §5.2:
         *  private/no-cache take a quoted comma-separated field list). */
        private static java.util.List<String> splitDirectives(String s) {
            java.util.List<String> out = new java.util.ArrayList<>();
            int start = 0;
            boolean inQuotes = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '"') inQuotes = !inQuotes;
                else if (c == ',' && !inQuotes) { out.add(s.substring(start, i)); start = i + 1; }
            }
            out.add(s.substring(start));
            return out;
        }

        /** A quoted field list ("a,b") expands to its individual field names. */
        private static void addFields(java.util.List<String> target, String value) {
            if (value.isEmpty()) return;
            for (String f : value.split(",")) {
                String trimmed = stripQuotes(f.trim());
                if (!trimmed.isEmpty()) target.add(trimmed);
            }
        }
        @Override public String toString(jakarta.ws.rs.core.CacheControl c) {
            if (c == null) throw new IllegalArgumentException("value is null");
            StringBuilder sb = new StringBuilder();
            if (c.isPrivate()) {
                if (c.getPrivateFields().isEmpty()) append(sb, "private");
                else append(sb, "private=\"" + String.join(",", c.getPrivateFields()) + "\"");
            }
            if (c.isNoCache()) {
                if (c.getNoCacheFields().isEmpty()) append(sb, "no-cache");
                else append(sb, "no-cache=\"" + String.join(",", c.getNoCacheFields()) + "\"");
            }
            if (c.isNoStore()) append(sb, "no-store");
            if (c.isNoTransform()) append(sb, "no-transform");
            if (c.isMustRevalidate()) append(sb, "must-revalidate");
            if (c.isProxyRevalidate()) append(sb, "proxy-revalidate");
            if (c.getMaxAge() != -1) append(sb, "max-age=" + c.getMaxAge());
            if (c.getSMaxAge() != -1) append(sb, "s-maxage=" + c.getSMaxAge());
            for (var e : c.getCacheExtension().entrySet()) {
                String v = e.getValue();
                append(sb, e.getKey() + (v == null || v.isEmpty() ? "" : "=" + v));
            }
            return sb.toString();
        }
        private static void append(StringBuilder sb, String v) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
        }
    }

    private static final class LinkDelegate implements HeaderDelegate<jakarta.ws.rs.core.Link> {
        @Override public jakarta.ws.rs.core.Link fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            // Format: <uri>; rel=xxx; title="yyy"
            String trimmed = s.trim();
            int gt = trimmed.indexOf('>');
            String uri = trimmed.startsWith("<") && gt > 0 ? trimmed.substring(1, gt) : trimmed;
            jakarta.ws.rs.core.Link.Builder b = jakarta.ws.rs.core.Link.fromUri(uri);
            if (gt > 0 && gt < trimmed.length() - 1) {
                String rest = trimmed.substring(gt + 1);
                for (String p : rest.split(";")) {
                    String pp = p.trim();
                    int eq = pp.indexOf('=');
                    if (eq < 0) continue;
                    b.param(pp.substring(0, eq).trim(),
                            stripQuotes(pp.substring(eq + 1).trim()));
                }
            }
            return b.build();
        }
        @Override public String toString(jakarta.ws.rs.core.Link l) {
            if (l == null) throw new IllegalArgumentException("value is null");
            StringBuilder sb = new StringBuilder("<").append(l.getUri()).append('>');
            for (var e : l.getParams().entrySet()) {
                sb.append(";").append(e.getKey()).append("=\"").append(e.getValue()).append('"');
            }
            return sb.toString();
        }
    }

    private static final class DateDelegate implements HeaderDelegate<java.util.Date> {
        // Immutable and thread-safe (no synchronized, virtual-thread friendly) —
        // IMF-fixdate (RFC 9110 §5.6.7), always GMT.
        private static final java.time.format.DateTimeFormatter FMT =
                java.time.format.DateTimeFormatter.ofPattern(
                        "EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US);
        @Override public java.util.Date fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            try {
                return java.util.Date.from(java.time.LocalDateTime.parse(s, FMT)
                        .toInstant(java.time.ZoneOffset.UTC));
            } catch (java.time.format.DateTimeParseException e) { return null; }
        }
        @Override public String toString(java.util.Date d) {
            return FMT.format(d.toInstant().atOffset(java.time.ZoneOffset.UTC));
        }
    }

    private static String stripQuotes(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }
    private static String quoteIfNeeded(String v) {
        if (v == null) return "";
        if (v.contains(" ") || v.contains(";") || v.contains(",")) return "\"" + v + "\"";
        return v;
    }

    /** Stub {@link Variant.VariantListBuilder} — collecte media types/langs/encodings
     *  et produit le produit cartésien via {@link #build()}. */
    private static final class StubVariantListBuilder extends Variant.VariantListBuilder {
        private final java.util.List<Variant> variants = new java.util.ArrayList<>();
        private final java.util.List<MediaType> mediaTypes = new java.util.ArrayList<>();
        private final java.util.List<java.util.Locale> languages = new java.util.ArrayList<>();
        private final java.util.List<String> encodings = new java.util.ArrayList<>();

        @Override public java.util.List<Variant> build() {
            add();
            return java.util.List.copyOf(variants);
        }

        @Override public Variant.VariantListBuilder add() {
            if (mediaTypes.isEmpty() && languages.isEmpty() && encodings.isEmpty()) return this;
            java.util.List<MediaType> mts = mediaTypes.isEmpty() ? java.util.Collections.singletonList(null) : mediaTypes;
            java.util.List<java.util.Locale> ls = languages.isEmpty() ? java.util.Collections.singletonList(null) : languages;
            java.util.List<String> encs = encodings.isEmpty() ? java.util.Collections.singletonList(null) : encodings;
            for (MediaType m : mts) for (java.util.Locale l : ls) for (String e : encs)
                variants.add(new Variant(m, l, e));
            mediaTypes.clear(); languages.clear(); encodings.clear();
            return this;
        }

        @Override public Variant.VariantListBuilder languages(java.util.Locale... langs) {
            for (java.util.Locale l : langs) languages.add(l);
            return this;
        }
        @Override public Variant.VariantListBuilder encodings(String... enc) {
            for (String e : enc) encodings.add(e);
            return this;
        }
        @Override public Variant.VariantListBuilder mediaTypes(MediaType... mts) {
            for (MediaType m : mts) mediaTypes.add(m);
            return this;
        }
    }

    /** Stub {@link Link.Builder} minimal: captures uri / rel / params, builds
     *  a Link returning exactly what was given. Sufficient for TCK tests that
     *  build a Link without checking its serialization to pass. */
    private static final class StubLinkBuilder implements Link.Builder {
        private java.net.URI uri;
        private jakarta.ws.rs.core.UriBuilder uriBuilder;
        private java.net.URI baseUri;
        private final java.util.Map<String, String> params = new java.util.LinkedHashMap<>();

        @Override public Link.Builder link(Link link) {
            this.uri = link.getUri();
            params.clear();
            params.putAll(link.getParams());
            return this;
        }
        @Override public Link.Builder link(String link) {
            // §4.3.4 : parse un Link-format header (<uri>;rel=...;title=...).
            if (link == null) throw new IllegalArgumentException("link");
            String s = link.trim();
            params.clear();
            int gt = s.indexOf('>');
            if (s.startsWith("<") && gt > 0) {
                // Format: <uri>; ... — URI must be non-empty; characters
                // after '>' must not contain other '<' or '>'.
                if (gt == 1) throw new IllegalArgumentException("empty URI in Link: " + link);
                if (s.indexOf('<', 1) >= 0 || s.indexOf('>', gt + 1) >= 0) {
                    throw new IllegalArgumentException("malformed Link header: " + link);
                }
                uri(s.substring(1, gt));
                String rest = gt + 1 < s.length() ? s.substring(gt + 1) : "";
                for (String p : rest.split(";")) {
                    String pp = p.trim();
                    int eq = pp.indexOf('=');
                    if (eq < 0) continue;
                    String name = pp.substring(0, eq).trim();
                    String value = pp.substring(eq + 1).trim();
                    if (value.length() >= 2 && value.charAt(0) == '"'
                            && value.charAt(value.length() - 1) == '"') {
                        value = value.substring(1, value.length() - 1);
                    }
                    params.put(name, value);
                }
            } else {
                uri(s);
            }
            return this;
        }
        @Override public Link.Builder uri(java.net.URI uri) {
            if (uri == null) throw new IllegalArgumentException("uri");
            // Validation deferred to build() so malformed URIs
            // throw UriBuilderException (§4.3.4) instead of IAE.
            this.uri = uri;
            return this;
        }
        @Override public Link.Builder uri(String uri) {
            if (uri == null) throw new IllegalArgumentException("uri");
            // JAX-RS templates {name} are not valid for java.net.URI.
            // Encode them as %7Bname%7D while parsing, then restore
            // the original form in the stored URI if it remains templated.
            if (uri.indexOf('{') >= 0 || uri.indexOf('}') >= 0) {
                String encoded = uri.replace("{", "%7B").replace("}", "%7D");
                try {
                    java.net.URI u = new java.net.URI(encoded);
                    return uri(u);
                } catch (java.net.URISyntaxException e) {
                    throw new IllegalArgumentException(e);
                }
            }
            try { return uri(new java.net.URI(uri)); }
            catch (java.net.URISyntaxException e) { throw new IllegalArgumentException(e); }
        }
        @Override public Link.Builder baseUri(java.net.URI uri) { this.baseUri = uri; return this; }
        @Override public Link.Builder baseUri(String uri) { this.baseUri = java.net.URI.create(uri); return this; }
        @Override public Link.Builder uriBuilder(jakarta.ws.rs.core.UriBuilder ub) { this.uriBuilder = ub; return this; }
        @Override public Link.Builder rel(String rel) {
            if (rel == null) throw new IllegalArgumentException("rel");
            String existing = params.get("rel");
            params.put("rel", existing == null ? rel : existing + " " + rel);
            return this;
        }
        @Override public Link.Builder param(String name, String value) {
            if (name == null) throw new IllegalArgumentException("name");
            params.put(name, value);
            return this;
        }
        @Override public Link.Builder title(String t) { params.put("title", t); return this; }
        @Override public Link.Builder type(String t) { params.put("type", t); return this; }

        @Override public Link build(Object... values) {
            if (values == null) throw new IllegalArgumentException("values");
            String uriStr;
            if (uri != null) uriStr = uri.toString();
            else if (uriBuilder != null) uriStr = uriBuilder.build(values).toString();
            else uriStr = "";
            String decoded = uriStr.replace("%7B", "{").replace("%7D", "}")
                .replace("%7b", "{").replace("%7d", "}");
            String substituted = substituteTemplates(decoded, values);
            // §4.3.4: unresolved template → IAE (not UriBuilderException).
            if (substituted.indexOf('{') >= 0) {
                throw new IllegalArgumentException(
                    "value not supplied for template in link uri: " + decoded);
            }
            // §4.3.4: malformed URI → UriBuilderException.
            java.net.URI effective;
            try {
                effective = new java.net.URI(substituted);
            } catch (java.net.URISyntaxException e) {
                throw new jakarta.ws.rs.core.UriBuilderException(e);
            }
            if (baseUri != null) effective = baseUri.resolve(effective);
            if (effective.getScheme() != null && effective.getAuthority() != null
                && effective.getHost() == null && !effective.getRawAuthority().isEmpty()) {
                throw new jakarta.ws.rs.core.UriBuilderException("malformed URI: " + effective);
            }
            // LinkedHashMap: preserves the insertion order of params
            // (unlike Map.copyOf which does not guarantee it).
            return new StubLink(effective, java.util.Collections.unmodifiableMap(
                new java.util.LinkedHashMap<>(params)));
        }

        @Override public Link buildRelativized(java.net.URI base, Object... values) {
            if (base == null) throw new IllegalArgumentException("base");
            Link l = build(values);
            java.net.URI rel = base.relativize(l.getUri());
            return new StubLink(rel, l.getParams());
        }

        private static String substituteTemplates(String tpl, Object[] values) {
            if (tpl == null || tpl.indexOf('{') < 0) return tpl;
            StringBuilder out = new StringBuilder();
            int i = 0, pos = 0;
            java.util.Map<String, Object> seen = new java.util.LinkedHashMap<>();
            while (i < tpl.length()) {
                char c = tpl.charAt(i);
                if (c == '{') {
                    int end = tpl.indexOf('}', i);
                    if (end < 0) { out.append(tpl, i, tpl.length()); break; }
                    String name = tpl.substring(i + 1, end).trim();
                    int colon = name.indexOf(':');
                    if (colon >= 0) name = name.substring(0, colon).trim();
                    Object val;
                    if (seen.containsKey(name)) val = seen.get(name);
                    else if (pos < values.length) { val = values[pos++]; seen.put(name, val); }
                    else { out.append('{').append(tpl, i + 1, end).append('}'); i = end + 1; continue; }
                    out.append(val);
                    i = end + 1;
                } else { out.append(c); i++; }
            }
            return out.toString();
        }
    }

    private static final class StubLink extends Link {
        private final java.net.URI uri;
        private final java.util.Map<String, String> params;

        StubLink(java.net.URI uri, java.util.Map<String, String> params) {
            this.uri = uri;
            this.params = params;
        }
        @Override public java.net.URI getUri() { return uri; }
        @Override public jakarta.ws.rs.core.UriBuilder getUriBuilder() { return new CassiniUriBuilder().uri(uri); }
        @Override public String getRel() { return params.get("rel"); }
        @Override public java.util.List<String> getRels() {
            String r = params.get("rel");
            return r == null ? java.util.List.of() : java.util.List.of(r.split("\\s+"));
        }
        @Override public String getTitle() { return params.get("title"); }
        @Override public String getType() { return params.get("type"); }
        @Override public java.util.Map<String, String> getParams() { return params; }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("<").append(uri).append('>');
            for (var e : params.entrySet()) sb.append(';').append(e.getKey()).append("=\"").append(e.getValue()).append('"');
            return sb.toString();
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Link l)) return false;
            return java.util.Objects.equals(uri, l.getUri())
                && java.util.Objects.equals(params, l.getParams());
        }
        @Override public int hashCode() {
            return java.util.Objects.hash(uri, params);
        }
    }
}
// JAX-RS templates {name} are not valid for java.net.URI.
// Encode them as %7Bname%7D while parsing, then restore
// the original form in the stored URI if it remains templated.

