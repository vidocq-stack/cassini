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
package io.vidocq.cassini.servlet;

import jakarta.servlet.ServletOutputStream;
import jakarta.servlet.WriteListener;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Hand-written doubles of the Servlet request and response: no mock library, only what the adapter reads. */
final class Fakes {

    private Fakes() { }

    /** What a request answers; every field is a plain value the test sets. */
    static final class Req {
        String method = "GET";
        String contextPath = "";
        String servletPath = "";
        String requestUri = "/";
        String queryString;
        boolean secure;
        String authType;
        Principal principal;
        Set<String> roles = Set.of();
        byte[] body = new byte[0];
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final Map<String, Object> attributes = new LinkedHashMap<>();

        Req header(String name, String value) {
            headers.computeIfAbsent(name, k -> new ArrayList<>()).add(value);
            return this;
        }

        HttpServletRequest proxy() {
            InvocationHandler h = (p, m, a) -> switch (m.getName()) {
                case "getMethod" -> method;
                case "getContextPath" -> contextPath;
                case "getServletPath" -> servletPath;
                case "getRequestURI" -> requestUri;
                case "getQueryString" -> queryString;
                case "isSecure" -> secure;
                case "getAuthType" -> authType;
                case "getUserPrincipal" -> principal;
                case "isUserInRole" -> roles.contains((String) a[0]);
                case "getRemoteAddr" -> "127.0.0.1";
                case "getRemotePort" -> 4711;
                case "getHeaderNames" -> Collections.enumeration(headers.keySet());
                case "getHeaders" -> Collections.enumeration(
                        headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase((String) a[0]))
                                .flatMap(e -> e.getValue().stream()).toList());
                case "getInputStream" -> new TestInput(body);
                case "setAttribute" -> { attributes.put((String) a[0], a[1]); yield null; }
                case "getAttribute" -> attributes.get((String) a[0]);
                case "removeAttribute" -> attributes.remove((String) a[0]);
                default -> throw new UnsupportedOperationException(m.getName());
            };
            return (HttpServletRequest) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                    new Class<?>[] {HttpServletRequest.class}, h);
        }
    }

    /** What a response received. */
    static final class Res {
        int status = 200;
        boolean committed;
        int flushes;
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        HttpServletResponse proxy() {
            InvocationHandler h = (p, m, a) -> switch (m.getName()) {
                case "setStatus" -> { status = (Integer) a[0]; yield null; }
                case "getStatus" -> status;
                case "addHeader" -> { headers.computeIfAbsent((String) a[0], k -> new ArrayList<>()).add((String) a[1]); yield null; }
                case "setHeader" -> { headers.put((String) a[0], new ArrayList<>(List.of((String) a[1]))); yield null; }
                case "getOutputStream" -> new TestOutput(body, this);
                case "flushBuffer" -> { flushes++; committed = true; yield null; }
                case "isCommitted" -> committed;
                default -> throw new UnsupportedOperationException(m.getName());
            };
            return (HttpServletResponse) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                    new Class<?>[] {HttpServletResponse.class}, h);
        }
    }

    /** A servlet context that records what the initializer registers. */
    static final class Ctx {
        final Map<String, Object> servlets = new LinkedHashMap<>();
        final Map<String, List<String>> mappings = new LinkedHashMap<>();
        final List<String> asyncSupported = new ArrayList<>();
        final Map<String, Object> attributes = new LinkedHashMap<>();

        jakarta.servlet.ServletContext proxy() {
            InvocationHandler h = (p, m, a) -> switch (m.getName()) {
                case "addServlet" -> {
                    String name = (String) a[0];
                    servlets.put(name, a[1]);
                    yield Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                            new Class<?>[] {jakarta.servlet.ServletRegistration.Dynamic.class}, (q, mm, aa) ->
                            switch (mm.getName()) {
                                case "addMapping" -> {
                                    mappings.computeIfAbsent(name, k -> new ArrayList<>())
                                            .addAll(List.of((String[]) aa[0]));
                                    yield Set.of();
                                }
                                case "setAsyncSupported" -> {
                                    if ((Boolean) aa[0]) {
                                        asyncSupported.add(name);
                                    }
                                    yield null;
                                }
                                case "setLoadOnStartup" -> null;
                                default -> throw new UnsupportedOperationException(mm.getName());
                            });
                }
                case "getServletRegistration" -> servlets.containsKey((String) a[0]) ? Proxy.newProxyInstance(
                        Fakes.class.getClassLoader(), new Class<?>[] {jakarta.servlet.ServletRegistration.class},
                        (q, mm, aa) -> null) : null;
                case "getAttribute" -> attributes.get((String) a[0]);
                case "setAttribute" -> { attributes.put((String) a[0], a[1]); yield null; }
                default -> throw new UnsupportedOperationException(m.getName());
            };
            return (jakarta.servlet.ServletContext) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                    new Class<?>[] {jakarta.servlet.ServletContext.class}, h);
        }
    }

    static jakarta.servlet.ServletConfig config(jakarta.servlet.ServletContext context, String name) {
        return (jakarta.servlet.ServletConfig) Proxy.newProxyInstance(Fakes.class.getClassLoader(),
                new Class<?>[] {jakarta.servlet.ServletConfig.class}, (p, m, a) -> switch (m.getName()) {
                    case "getServletContext" -> context;
                    case "getServletName" -> name;
                    case "getInitParameter" -> null;
                    case "getInitParameterNames" -> Collections.emptyEnumeration();
                    default -> throw new UnsupportedOperationException(m.getName());
                });
    }

    private static final class TestInput extends jakarta.servlet.ServletInputStream {
        private final ByteArrayInputStream in;
        TestInput(byte[] data) { this.in = new ByteArrayInputStream(data); }
        @Override public int read() { return in.read(); }
        @Override public boolean isFinished() { return in.available() == 0; }
        @Override public boolean isReady() { return true; }
        @Override public void setReadListener(jakarta.servlet.ReadListener l) { throw new UnsupportedOperationException(); }
    }

    private static final class TestOutput extends ServletOutputStream {
        private final ByteArrayOutputStream out;
        private final Res res;
        TestOutput(ByteArrayOutputStream out, Res res) { this.out = out; this.res = res; }
        @Override public void write(int b) { res.committed = true; out.write(b); }
        @Override public void flush() throws IOException { res.flushes++; res.committed = true; }
        @Override public boolean isReady() { return true; }
        @Override public void setWriteListener(WriteListener l) { throw new UnsupportedOperationException(); }
    }
}
