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
package io.vidocq.cassini.chappe;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.Server;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.SeBootstrap;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.core.EntityPart;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.UriBuilder;
import jakarta.ws.rs.core.Variant;
import jakarta.ws.rs.ext.RuntimeDelegate;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cassini {@link RuntimeDelegate} providing SE bootstrap through Chappe.
 *
 * <p>Selected through the system property:
 * {@code -Djakarta.ws.rs.ext.RuntimeDelegate=io.vidocq.cassini.chappe.ChappeRuntimeDelegate}
 *
 * <p>Standalone implementation (without inheriting from cassini-core): all logic
 * from {@code CassiniRuntimeDelegate} is duplicated here so that
 * {@code cassini-chappe} does not depend on {@code cassini-core}.
 */
public final class ChappeRuntimeDelegate extends RuntimeDelegate {

    // ── RuntimeDelegate implementation ───────────────────────────────────────

    @Override
    public UriBuilder createUriBuilder() {
        return new CassiniUriBuilderShim();
    }

    @Override
    public jakarta.ws.rs.core.Response.ResponseBuilder createResponseBuilder() {
        return new CassiniResponseBuilderShim();
    }

    @Override
    public Variant.VariantListBuilder createVariantListBuilder() {
        return new StubVariantListBuilder();
    }

    @Override
    public <T> T createEndpoint(Application application, Class<T> endpointType) {
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

    @Override
    public Link.Builder createLinkBuilder() {
        return new StubLinkBuilder();
    }

    @Override
    public EntityPart.Builder createEntityPartBuilder(String name) {
        // Delegates via reflection to the cassini-core implementation if available,
        // otherwise throws an exception.
        try {
            Class<?> cls = Class.forName(
                    "io.vidocq.cassini.internal.multipart.CassiniEntityPartBuilder",
                    true, Thread.currentThread().getContextClassLoader());
            return (EntityPart.Builder) cls.getDeclaredConstructor(String.class)
                    .newInstance(name);
        } catch (ReflectiveOperationException e) {
            throw new UnsupportedOperationException(
                    "EntityPart requires cassini-core on the classpath", e);
        }
    }

    @Override
    public SeBootstrap.Configuration.Builder createConfigurationBuilder() {
        return new CassiniBootstrapConfigBuilder();
    }

    // ── SeBootstrap ───────────────────────────────────────────────────────────

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Application application,
                                                           SeBootstrap.Configuration config) {
        return CompletableFuture.supplyAsync(() -> new ChappeSeBootstrapInstance(application, config));
    }

    @Override
    public CompletionStage<SeBootstrap.Instance> bootstrap(Class<? extends Application> clazz,
                                                            SeBootstrap.Configuration config) {
        try {
            return bootstrap(clazz.getDeclaredConstructor().newInstance(), config);
        } catch (ReflectiveOperationException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    // ── Bootstrap support ─────────────────────────────────────────────────────

    private record CassiniBootstrapConfig(java.util.Map<String, Object> props)
            implements SeBootstrap.Configuration {
        @Override public Object property(String name) { return props.get(name); }
    }

    private static final class CassiniBootstrapConfigBuilder
            implements SeBootstrap.Configuration.Builder {
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
        @Override
        public <T> SeBootstrap.Configuration.Builder from(
                java.util.function.BiFunction<String, Class<T>, java.util.Optional<T>> src) {
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
        private <T, V> void tryRead(
                java.util.function.BiFunction<String, Class<T>, java.util.Optional<T>> src,
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

    // ── SeBootstrap instance ──────────────────────────────────────────────────

    private static final class ChappeSeBootstrapInstance implements SeBootstrap.Instance {
        private final SeBootstrap.Configuration config;
        private volatile Server server;

        ChappeSeBootstrapInstance(Application application,
                                  SeBootstrap.Configuration requested) {
            Object p = requested.property(SeBootstrap.Configuration.PORT);
            int reqPort = p instanceof Number n ? n.intValue() : -1;
            if (reqPort <= 0) {
                try (var ss = new java.net.ServerSocket(
                        0, 50, java.net.InetAddress.getByName("127.0.0.1"))) {
                    reqPort = ss.getLocalPort();
                } catch (java.io.IOException e) { throw new RuntimeException(e); }
            }
            java.util.Map<String, Object> effective = new java.util.HashMap<>();
            for (String k : new String[]{
                    SeBootstrap.Configuration.PROTOCOL,
                    SeBootstrap.Configuration.HOST,
                    SeBootstrap.Configuration.PORT,
                    SeBootstrap.Configuration.ROOT_PATH,
                    SeBootstrap.Configuration.SSL_CLIENT_AUTHENTICATION,
                    SeBootstrap.Configuration.SSL_CONTEXT}) {
                Object v = requested.property(k);
                if (v != null) effective.put(k, v);
            }
            effective.put(SeBootstrap.Configuration.PORT, reqPort);
            effective.put(SeBootstrap.Configuration.HOST, "localhost");
            this.config = new CassiniBootstrapConfig(java.util.Map.copyOf(effective));

            try {
                // Bootstrap through the public SPI — no direct access to internals.
                var stack = CassiniStack.builder().application(application).build();
                var bridge = new ChappeHttpAdapter(stack.adapter());

                String rootPath = (String) requested.property(
                        SeBootstrap.Configuration.ROOT_PATH);
                String appPath = "";
                jakarta.ws.rs.ApplicationPath ap =
                        application.getClass().getAnnotation(
                                jakarta.ws.rs.ApplicationPath.class);
                if (ap != null) {
                    appPath = ap.value();
                    if (!appPath.isEmpty() && !appPath.startsWith("/"))
                        appPath = "/" + appPath;
                    if (appPath.length() > 1 && appPath.endsWith("/"))
                        appPath = appPath.substring(0, appPath.length() - 1);
                }
                String rootNorm = (rootPath == null || "/".equals(rootPath)
                        || rootPath.isEmpty())
                        ? "" : (rootPath.startsWith("/") ? rootPath : "/" + rootPath);
                if (rootNorm.length() > 1 && rootNorm.endsWith("/"))
                    rootNorm = rootNorm.substring(0, rootNorm.length() - 1);
                final String prefix = rootNorm + appPath;

                Handler handler = prefix.isEmpty() ? bridge
                        : req -> {
                    String pth = req.path() == null ? "/" : req.path();
                    if (!pth.startsWith(prefix)) {
                        return Response.builder()
                                .status(StatusCode.NOT_FOUND)
                                .body(Body.empty()).build();
                    }
                    String stripped = pth.substring(prefix.length());
                    if (stripped.isEmpty()) stripped = "/";
                    final String newPath = stripped;
                    Request remapped = new Request() {
                        @Override public io.vidocq.chappe.api.HttpMethod method() { return req.method(); }
                        @Override public java.net.URI uri() { return req.uri(); }
                        @Override public String path() { return newPath; }
                        @Override public String query() { return req.query(); }
                        @Override public io.vidocq.chappe.api.HttpVersion version() { return req.version(); }
                        @Override public io.vidocq.chappe.api.Headers headers() { return req.headers(); }
                        @Override public Body body() { return req.body(); }
                        @Override public java.util.Map<String, String> pathParams() { return req.pathParams(); }
                        @Override public java.util.Map<String, String> queryParams() { return req.queryParams(); }
                        @Override public String contextPath() { return prefix; }
                        @Override public String pathInfo() { return newPath; }
                        // Delegate per-request attributes to the wrapped request — the
                        // interface defaults are no-ops and would silently drop state
                        // set by upstream handlers (e.g. cassini.auth from BASIC auth).
                        @Override public Object attribute(String key) { return req.attribute(key); }
                        @Override public Request attribute(String key, Object value) { req.attribute(key, value); return this; }
                    };
                    return bridge.handle(remapped);
                };

                Server s = Server.builder()
                        .host("127.0.0.1").port(reqPort).handler(handler).build();
                s.start();
                this.server = s;
            } catch (RuntimeException e) {
                this.server = null;
                throw e;
            }
        }

        @Override public SeBootstrap.Configuration configuration() { return config; }

        @Override
        public CompletionStage<SeBootstrap.Instance.StopResult> stop() {
            return CompletableFuture.supplyAsync(() -> {
                if (server != null) {
                    try { server.stop(); } catch (RuntimeException ignored) {}
                }
                return new SeBootstrap.Instance.StopResult() {
                    @Override public <T> T unwrap(Class<T> nativeClass) {
                        throw new IllegalArgumentException();
                    }
                };
            });
        }

        @Override public <T> T unwrap(Class<T> nativeClass) {
            if (nativeClass.isInstance(server)) return nativeClass.cast(server);
            throw new IllegalArgumentException("Cannot unwrap to " + nativeClass);
        }
    }

    // ── MediaType helpers (inlined, no dependency on cassini-core) ───────────

    private static MediaType parseMediaType(String raw) {
        if (raw == null || raw.isBlank()) return MediaType.WILDCARD_TYPE;
        String[] parts = raw.split(";");
        String[] ts = parts[0].trim().split("/", 2);
        if (ts.length < 2) throw new IllegalArgumentException("Invalid media type: " + raw);
        String type = ts[0].trim().isEmpty() ? "*" : ts[0].trim();
        String subtype = ts[1].trim().isEmpty() ? "*" : ts[1].trim();
        if (!isValidMediaTypeToken(type) || !isValidMediaTypeToken(subtype)) {
            throw new IllegalArgumentException("Invalid media type: " + raw);
        }
        java.util.Map<String, String> params = new java.util.HashMap<>();
        for (int i = 1; i < parts.length; i++) {
            String seg = parts[i].trim();
            if (seg.isEmpty()) continue;
            int eq = seg.indexOf('=');
            if (eq < 0) params.put(seg, "");
            else params.put(seg.substring(0, eq).trim(),
                    stripQuotes(seg.substring(eq + 1).trim()));
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

    private static String formatMediaType(MediaType mt) {
        if (mt == null) return "*/*";
        StringBuilder sb = new StringBuilder();
        sb.append(mt.getType()).append('/').append(mt.getSubtype());
        for (var e : mt.getParameters().entrySet()) {
            sb.append(';').append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }

    // ── Header delegates ──────────────────────────────────────────────────────

    private static final class MediaTypeDelegate implements HeaderDelegate<MediaType> {
        @Override public MediaType fromString(String value) {
            if (value == null) throw new IllegalArgumentException("value is null");
            return parseMediaType(value);
        }
        @Override public String toString(MediaType value) {
            if (value == null) throw new IllegalArgumentException("value is null");
            return formatMediaType(value);
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

    private static final class NewCookieDelegate
            implements HeaderDelegate<jakarta.ws.rs.core.NewCookie> {
        @Override public jakarta.ws.rs.core.NewCookie fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            String[] parts = s.split(";");
            String name = null, value = null, path = null, domain = null, comment = null;
            int maxAge = -1; boolean secure = false, httpOnly = false;
            int version = 1;
            for (int i = 0; i < parts.length; i++) {
                String part = parts[i].trim();
                int eq = part.indexOf('=');
                String k = eq < 0 ? part : part.substring(0, eq).trim();
                String v = eq < 0 ? "" : stripQuotes(part.substring(eq + 1).trim());
                if (i == 0) { name = k; value = v; continue; }
                switch (k.toLowerCase(java.util.Locale.ROOT)) {
                    case "path" -> path = v;
                    case "domain" -> domain = v;
                    case "comment" -> comment = v;
                    case "max-age" -> { try { maxAge = Integer.parseInt(v); } catch (Exception ignored) {} }
                    case "version" -> { try { version = Integer.parseInt(v); } catch (Exception ignored) {} }
                    case "secure" -> secure = true;
                    case "httponly" -> httpOnly = true;
                }
            }
            return new jakarta.ws.rs.core.NewCookie.Builder(name)
                    .value(value).path(path).domain(domain).comment(comment)
                    .maxAge(maxAge).version(version).secure(secure).httpOnly(httpOnly).build();
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

    private static final class CookieDelegate
            implements HeaderDelegate<jakarta.ws.rs.core.Cookie> {
        @Override public jakarta.ws.rs.core.Cookie fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            String name = null, value = null, path = null, domain = null;
            int version = 0;
            for (String pair : s.split(";")) {
                String part = pair.trim();
                if (part.isEmpty()) continue;
                int eq = part.indexOf('=');
                String k = eq < 0 ? part : part.substring(0, eq).trim();
                String v = eq < 0 ? "" : stripQuotes(part.substring(eq + 1).trim());
                if ("$Version".equalsIgnoreCase(k)) {
                    try { version = Integer.parseInt(v); } catch (Exception ignored) {}
                } else if ("$Path".equalsIgnoreCase(k)) {
                    path = v;
                } else if ("$Domain".equalsIgnoreCase(k)) {
                    domain = v;
                } else if (name == null) {
                    name = k; value = v;
                }
            }
            if (name == null) name = s.trim();
            jakarta.ws.rs.core.Cookie.Builder b =
                    new jakarta.ws.rs.core.Cookie.Builder(name).value(value).version(version);
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

    private static final class EntityTagDelegate
            implements HeaderDelegate<jakarta.ws.rs.core.EntityTag> {
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

    private static final class CacheControlDelegate
            implements HeaderDelegate<jakarta.ws.rs.core.CacheControl> {
        @Override public jakarta.ws.rs.core.CacheControl fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            jakarta.ws.rs.core.CacheControl cc = new jakarta.ws.rs.core.CacheControl();
            cc.setNoTransform(false);
            for (String tok : s.split(",")) {
                String t = tok.trim();
                if (t.isEmpty()) continue;
                int eq = t.indexOf('=');
                String k = eq < 0 ? t : t.substring(0, eq).trim();
                String v = eq < 0 ? "" : stripQuotes(t.substring(eq + 1).trim());
                switch (k.toLowerCase(java.util.Locale.ROOT)) {
                    case "no-cache" -> { cc.setNoCache(true); if (!v.isEmpty()) cc.getNoCacheFields().add(stripQuotes(v)); }
                    case "no-store" -> cc.setNoStore(true);
                    case "no-transform" -> cc.setNoTransform(true);
                    case "private" -> { cc.setPrivate(true); if (!v.isEmpty()) cc.getPrivateFields().add(stripQuotes(v)); }
                    case "must-revalidate" -> cc.setMustRevalidate(true);
                    case "proxy-revalidate" -> cc.setProxyRevalidate(true);
                    case "max-age" -> { try { cc.setMaxAge(Integer.parseInt(v)); } catch (Exception ignored) {} }
                    case "s-maxage" -> { try { cc.setSMaxAge(Integer.parseInt(v)); } catch (Exception ignored) {} }
                    default -> cc.getCacheExtension().put(k, v);
                }
            }
            return cc;
        }
        @Override public String toString(jakarta.ws.rs.core.CacheControl c) {
            if (c == null) throw new IllegalArgumentException("value is null");
            StringBuilder sb = new StringBuilder();
            if (c.isPrivate()) appendCC(sb, c.getPrivateFields().isEmpty() ? "private"
                    : "private=\"" + String.join(",", c.getPrivateFields()) + "\"");
            if (c.isNoCache()) appendCC(sb, c.getNoCacheFields().isEmpty() ? "no-cache"
                    : "no-cache=\"" + String.join(",", c.getNoCacheFields()) + "\"");
            if (c.isNoStore()) appendCC(sb, "no-store");
            if (c.isNoTransform()) appendCC(sb, "no-transform");
            if (c.isMustRevalidate()) appendCC(sb, "must-revalidate");
            if (c.isProxyRevalidate()) appendCC(sb, "proxy-revalidate");
            if (c.getMaxAge() != -1) appendCC(sb, "max-age=" + c.getMaxAge());
            if (c.getSMaxAge() != -1) appendCC(sb, "s-maxage=" + c.getSMaxAge());
            for (var e : c.getCacheExtension().entrySet()) {
                String v = e.getValue();
                appendCC(sb, e.getKey() + (v == null || v.isEmpty() ? "" : "=" + v));
            }
            return sb.toString();
        }
        private static void appendCC(StringBuilder sb, String v) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
        }
    }

    private static final class LinkDelegate implements HeaderDelegate<jakarta.ws.rs.core.Link> {
        @Override public jakarta.ws.rs.core.Link fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            String trimmed = s.trim();
            int gt = trimmed.indexOf('>');
            String uri = trimmed.startsWith("<") && gt > 0
                    ? trimmed.substring(1, gt) : trimmed;
            jakarta.ws.rs.core.Link.Builder b = jakarta.ws.rs.core.Link.fromUri(uri);
            if (gt > 0 && gt < trimmed.length() - 1) {
                for (String part : trimmed.substring(gt + 1).split(";")) {
                    String pp = part.trim();
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
        private static final java.text.SimpleDateFormat FMT;
        static {
            FMT = new java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'",
                    java.util.Locale.US);
            FMT.setTimeZone(java.util.TimeZone.getTimeZone("GMT"));
        }
        @Override public synchronized java.util.Date fromString(String s) {
            if (s == null) throw new IllegalArgumentException("value is null");
            try { return FMT.parse(s); } catch (Exception e) { return null; }
        }
        @Override public synchronized String toString(java.util.Date d) { return FMT.format(d); }
    }

    // ── Variant list builder ──────────────────────────────────────────────────

    private static final class StubVariantListBuilder extends Variant.VariantListBuilder {
        private final java.util.List<Variant> variants = new java.util.ArrayList<>();
        private final java.util.List<MediaType> mediaTypes = new java.util.ArrayList<>();
        private final java.util.List<java.util.Locale> languages = new java.util.ArrayList<>();
        private final java.util.List<String> encodings = new java.util.ArrayList<>();

        @Override public java.util.List<Variant> build() { add(); return java.util.List.copyOf(variants); }

        @Override public Variant.VariantListBuilder add() {
            if (mediaTypes.isEmpty() && languages.isEmpty() && encodings.isEmpty()) return this;
            var mts = mediaTypes.isEmpty() ? java.util.Collections.singletonList((MediaType) null) : mediaTypes;
            var ls = languages.isEmpty() ? java.util.Collections.singletonList((java.util.Locale) null) : languages;
            var encs = encodings.isEmpty() ? java.util.Collections.singletonList((String) null) : encodings;
            for (MediaType m : mts) for (java.util.Locale l : ls) for (String e : encs)
                variants.add(new Variant(m, l, e));
            mediaTypes.clear(); languages.clear(); encodings.clear();
            return this;
        }

        @Override public Variant.VariantListBuilder languages(java.util.Locale... langs) {
            for (java.util.Locale l : langs) languages.add(l); return this;
        }
        @Override public Variant.VariantListBuilder encodings(String... enc) {
            for (String e : enc) encodings.add(e); return this;
        }
        @Override public Variant.VariantListBuilder mediaTypes(MediaType... mts) {
            for (MediaType m : mts) mediaTypes.add(m); return this;
        }
    }

    // ── UriBuilder shim ───────────────────────────────────────────────────────

    /**
     * Minimal UriBuilder for ChappeRuntimeDelegate's needs.
     * Delegates to the cassini-core implementation via reflection if available,
     * otherwise provides a basic fallback implementation.
     */
    private static final class CassiniUriBuilderShim extends UriBuilder {
        private final UriBuilder delegate;

        CassiniUriBuilderShim() {
            UriBuilder d = null;
            try {
                Class<?> cls = Class.forName(
                        "io.vidocq.cassini.internal.runtime.CassiniUriBuilder",
                        true, Thread.currentThread().getContextClassLoader());
                d = (UriBuilder) cls.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ignored) {}
            this.delegate = d;
        }

        private CassiniUriBuilderShim(UriBuilder delegate) { this.delegate = delegate; }

        private UriBuilder req() {
            if (delegate == null)
                throw new UnsupportedOperationException(
                        "UriBuilder requires cassini-core on the module path");
            return delegate;
        }

        @Override public UriBuilder clone() {
            if (delegate == null) throw new UnsupportedOperationException("clone requires cassini-core");
            return new CassiniUriBuilderShim(delegate.clone());
        }
        @Override public UriBuilder uri(java.net.URI uri) { return req().uri(uri); }
        @Override public UriBuilder uri(String uriTemplate) { return req().uri(uriTemplate); }
        @Override public UriBuilder scheme(String scheme) { return req().scheme(scheme); }
        @Override public UriBuilder schemeSpecificPart(String ssp) { return req().schemeSpecificPart(ssp); }
        @Override public UriBuilder userInfo(String ui) { return req().userInfo(ui); }
        @Override public UriBuilder host(String host) { return req().host(host); }
        @Override public UriBuilder port(int port) { return req().port(port); }
        @Override public UriBuilder replacePath(String path) { return req().replacePath(path); }
        @Override public UriBuilder path(String path) { return req().path(path); }
        @Override public UriBuilder path(Class resource) { return req().path(resource); }
        @Override public UriBuilder path(Class resource, String method) { return req().path(resource, method); }
        @Override public UriBuilder path(java.lang.reflect.Method method) { return req().path(method); }
        @Override public UriBuilder segment(String... segments) { return req().segment(segments); }
        @Override public UriBuilder replaceMatrix(String matrix) { return req().replaceMatrix(matrix); }
        @Override public UriBuilder matrixParam(String name, Object... values) { return req().matrixParam(name, values); }
        @Override public UriBuilder replaceMatrixParam(String name, Object... values) { return req().replaceMatrixParam(name, values); }
        @Override public UriBuilder replaceQuery(String query) { return req().replaceQuery(query); }
        @Override public UriBuilder queryParam(String name, Object... values) { return req().queryParam(name, values); }
        @Override public UriBuilder replaceQueryParam(String name, Object... values) { return req().replaceQueryParam(name, values); }
        @Override public UriBuilder fragment(String fragment) { return req().fragment(fragment); }
        @Override public UriBuilder resolveTemplate(String name, Object value) { return req().resolveTemplate(name, value); }
        @Override public UriBuilder resolveTemplate(String name, Object value, boolean encodeSlashInPath) { return req().resolveTemplate(name, value, encodeSlashInPath); }
        @Override public UriBuilder resolveTemplateFromEncoded(String name, Object value) { return req().resolveTemplateFromEncoded(name, value); }
        @Override public UriBuilder resolveTemplates(java.util.Map<String, Object> templateValues) { return req().resolveTemplates(templateValues); }
        @Override public UriBuilder resolveTemplates(java.util.Map<String, Object> templateValues, boolean encodeSlashInPath) { return req().resolveTemplates(templateValues, encodeSlashInPath); }
        @Override public UriBuilder resolveTemplatesFromEncoded(java.util.Map<String, Object> templateValues) { return req().resolveTemplatesFromEncoded(templateValues); }
        @Override public java.net.URI buildFromMap(java.util.Map<String, ?> values) { return req().buildFromMap(values); }
        @Override public java.net.URI buildFromMap(java.util.Map<String, ?> values, boolean encodeSlashInPath) { return req().buildFromMap(values, encodeSlashInPath); }
        @Override public java.net.URI buildFromEncodedMap(java.util.Map<String, ?> values) { return req().buildFromEncodedMap(values); }
        @Override public java.net.URI build(Object... values) { return req().build(values); }
        @Override public java.net.URI build(Object[] values, boolean encodeSlashInPath) { return req().build(values, encodeSlashInPath); }
        @Override public java.net.URI buildFromEncoded(Object... values) { return req().buildFromEncoded(values); }
        @Override public String toTemplate() { return req().toTemplate(); }
    }

    // ── ResponseBuilder shim ──────────────────────────────────────────────────

    private static final class CassiniResponseBuilderShim
            extends jakarta.ws.rs.core.Response.ResponseBuilder {
        private final jakarta.ws.rs.core.Response.ResponseBuilder delegate;

        CassiniResponseBuilderShim() {
            jakarta.ws.rs.core.Response.ResponseBuilder d = null;
            try {
                Class<?> cls = Class.forName(
                        "io.vidocq.cassini.internal.runtime.CassiniResponseBuilder",
                        true, Thread.currentThread().getContextClassLoader());
                d = (jakarta.ws.rs.core.Response.ResponseBuilder)
                        cls.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException ignored) {}
            this.delegate = d;
        }

        private CassiniResponseBuilderShim(
                jakarta.ws.rs.core.Response.ResponseBuilder delegate) {
            this.delegate = delegate;
        }

        private jakarta.ws.rs.core.Response.ResponseBuilder req() {
            if (delegate == null)
                throw new UnsupportedOperationException(
                        "ResponseBuilder requires cassini-core on the module path");
            return delegate;
        }

        @Override public jakarta.ws.rs.core.Response build() { return req().build(); }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder clone() {
            if (delegate == null) throw new UnsupportedOperationException("clone requires cassini-core");
            return new CassiniResponseBuilderShim(delegate.clone());
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder status(int status) {
            return req().status(status);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder status(int status, String reasonPhrase) {
            return req().status(status, reasonPhrase);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder entity(Object entity) {
            return req().entity(entity);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder entity(Object entity, java.lang.annotation.Annotation[] annotations) {
            return req().entity(entity, annotations);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder allow(String... methods) {
            return req().allow(methods);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder allow(java.util.Set<String> methods) {
            return req().allow(methods);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder cacheControl(jakarta.ws.rs.core.CacheControl cacheControl) {
            return req().cacheControl(cacheControl);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder encoding(String encoding) {
            return req().encoding(encoding);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder header(String name, Object value) {
            return req().header(name, value);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder replaceAll(jakarta.ws.rs.core.MultivaluedMap<String, Object> headers) {
            return req().replaceAll(headers);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder language(String language) {
            return req().language(language);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder language(java.util.Locale language) {
            return req().language(language);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder type(MediaType type) {
            return req().type(type);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder type(String type) {
            return req().type(type);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder variant(Variant variant) {
            return req().variant(variant);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder contentLocation(java.net.URI location) {
            return req().contentLocation(location);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder cookie(jakarta.ws.rs.core.NewCookie... cookies) {
            return req().cookie(cookies);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder expires(java.util.Date expires) {
            return req().expires(expires);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder lastModified(java.util.Date lastModified) {
            return req().lastModified(lastModified);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder location(java.net.URI location) {
            return req().location(location);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder tag(jakarta.ws.rs.core.EntityTag tag) {
            return req().tag(tag);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder tag(String tag) {
            return req().tag(tag);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder variants(Variant... variants) {
            return req().variants(variants);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder variants(java.util.List<Variant> variants) {
            return req().variants(variants);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder links(Link... links) {
            return req().links(links);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder link(java.net.URI uri, String rel) {
            return req().link(uri, rel);
        }

        @Override public jakarta.ws.rs.core.Response.ResponseBuilder link(String uri, String rel) {
            return req().link(uri, rel);
        }
    }

    // ── Link builder ──────────────────────────────────────────────────────────

    private static final class StubLinkBuilder implements Link.Builder {
        private java.net.URI uri;
        private UriBuilder uriBuilder;
        private java.net.URI baseUri;
        private final java.util.Map<String, String> params = new java.util.LinkedHashMap<>();

        @Override public Link.Builder link(Link link) {
            this.uri = link.getUri(); params.clear(); params.putAll(link.getParams()); return this;
        }
        @Override public Link.Builder link(String link) {
            if (link == null) throw new IllegalArgumentException("link");
            String s = link.trim(); params.clear();
            int gt = s.indexOf('>');
            if (s.startsWith("<") && gt > 0) {
                if (gt == 1) throw new IllegalArgumentException("empty URI in Link: " + link);
                if (s.indexOf('<', 1) >= 0 || s.indexOf('>', gt + 1) >= 0)
                    throw new IllegalArgumentException("malformed Link header: " + link);
                uri(s.substring(1, gt));
                String rest = gt + 1 < s.length() ? s.substring(gt + 1) : "";
                for (String p : rest.split(";")) {
                    String pp = p.trim(); int eq = pp.indexOf('=');
                    if (eq < 0) continue;
                    String k = pp.substring(0, eq).trim();
                    String v = pp.substring(eq + 1).trim();
                    if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"')
                        v = v.substring(1, v.length() - 1);
                    params.put(k, v);
                }
            } else { uri(s); }
            return this;
        }
        @Override public Link.Builder uri(java.net.URI u) { if (u == null) throw new IllegalArgumentException("uri"); this.uri = u; return this; }
        @Override public Link.Builder uri(String u) {
            if (u == null) throw new IllegalArgumentException("uri");
            if (u.indexOf('{') >= 0 || u.indexOf('}') >= 0) {
                String enc = u.replace("{", "%7B").replace("}", "%7D");
                try { return uri(new java.net.URI(enc)); } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException(e); }
            }
            try { return uri(new java.net.URI(u)); } catch (java.net.URISyntaxException e) { throw new IllegalArgumentException(e); }
        }
        @Override public Link.Builder baseUri(java.net.URI u) { this.baseUri = u; return this; }
        @Override public Link.Builder baseUri(String u) { this.baseUri = java.net.URI.create(u); return this; }
        @Override public Link.Builder uriBuilder(UriBuilder ub) { this.uriBuilder = ub; return this; }
        @Override public Link.Builder rel(String rel) { if (rel == null) throw new IllegalArgumentException("rel"); String ex = params.get("rel"); params.put("rel", ex == null ? rel : ex + " " + rel); return this; }
        @Override public Link.Builder param(String name, String value) { if (name == null) throw new IllegalArgumentException("name"); params.put(name, value); return this; }
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
            if (substituted.indexOf('{') >= 0)
                throw new IllegalArgumentException("value not supplied for template in link uri: " + decoded);
            java.net.URI effective;
            try { effective = new java.net.URI(substituted); }
            catch (java.net.URISyntaxException e) { throw new jakarta.ws.rs.core.UriBuilderException(e); }
            if (baseUri != null) effective = baseUri.resolve(effective);
            if (effective.getScheme() != null && effective.getAuthority() != null
                    && effective.getHost() == null && !effective.getRawAuthority().isEmpty())
                throw new jakarta.ws.rs.core.UriBuilderException("malformed URI: " + effective);
            return new StubLink(effective, java.util.Collections.unmodifiableMap(
                    new java.util.LinkedHashMap<>(params)));
        }

        @Override public Link buildRelativized(java.net.URI base, Object... values) {
            if (base == null) throw new IllegalArgumentException("base");
            Link l = build(values);
            return new StubLink(base.relativize(l.getUri()), l.getParams());
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
                    int colon = name.indexOf(':'); if (colon >= 0) name = name.substring(0, colon).trim();
                    Object val;
                    if (seen.containsKey(name)) val = seen.get(name);
                    else if (pos < values.length) { val = values[pos++]; seen.put(name, val); }
                    else { out.append('{').append(tpl, i + 1, end).append('}'); i = end + 1; continue; }
                    out.append(val); i = end + 1;
                } else { out.append(c); i++; }
            }
            return out.toString();
        }
    }

    private static final class StubLink extends Link {
        private final java.net.URI uri;
        private final java.util.Map<String, String> params;
        StubLink(java.net.URI uri, java.util.Map<String, String> params) { this.uri = uri; this.params = params; }
        @Override public java.net.URI getUri() { return uri; }
        @Override public UriBuilder getUriBuilder() { return new CassiniUriBuilderShim().uri(uri); }
        @Override public String getRel() { return params.get("rel"); }
        @Override public java.util.List<String> getRels() { String r = params.get("rel"); return r == null ? java.util.List.of() : java.util.List.of(r.split("\\s+")); }
        @Override public String getTitle() { return params.get("title"); }
        @Override public String getType() { return params.get("type"); }
        @Override public java.util.Map<String, String> getParams() { return params; }
        @Override public String toString() { StringBuilder sb = new StringBuilder("<").append(uri).append('>'); for (var e : params.entrySet()) sb.append(';').append(e.getKey()).append("=\"").append(e.getValue()).append('"'); return sb.toString(); }
        @Override public boolean equals(Object o) { if (this == o) return true; if (!(o instanceof Link l)) return false; return java.util.Objects.equals(uri, l.getUri()) && java.util.Objects.equals(params, l.getParams()); }
        @Override public int hashCode() { return java.util.Objects.hash(uri, params); }
    }

    // ── String helpers ────────────────────────────────────────────────────────

    private static String stripQuotes(String v) {
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\""))
            return v.substring(1, v.length() - 1);
        return v;
    }

    private static String quoteIfNeeded(String v) {
        if (v == null) return "";
        if (v.contains(" ") || v.contains(";") || v.contains(",")) return "\"" + v + "\"";
        return v;
    }
}
