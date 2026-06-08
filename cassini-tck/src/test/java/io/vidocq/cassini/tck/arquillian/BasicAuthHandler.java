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
package io.vidocq.cassini.tck.arquillian;

import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Handler;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import io.vidocq.chappe.api.StatusCode;
import io.vidocq.cassini.internal.context.CassiniSecurityContext;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Set;

/**
 * §6.1 BASIC auth for TCK archives that deploy a web.xml with
 * {@code <login-config><auth-method>BASIC</auth-method></login-config>}.
 * <p>Principal → roles mapping derived from the TCK sun-web.xml:
 * j2ee/j2ee → DIRECTOR; javajoe/javajoe → OTHERROLE.</p>
 */
final class BasicAuthHandler implements Handler {

    private static final String REALM = "TCK";

    private static final Map<Credentials, AuthEntry> USERS = Map.of(
            new Credentials("j2ee", "j2ee"),
            new AuthEntry("j2ee", Set.of("DIRECTOR", "Administrator", "Manager")),
            new Credentials("javajoe", "javajoe"),
            new AuthEntry("javajoe", Set.of("OTHERROLE", "VP", "Manager"))
    );

    private final Handler delegate;
    private final java.util.regex.Pattern protectedPath;

    BasicAuthHandler(Handler delegate, String protectedPathPattern) {
        this.delegate = delegate;
        this.protectedPath = java.util.regex.Pattern.compile(protectedPathPattern);
    }

    @Override public Response handle(Request request) throws Exception {
        String path = request.path() == null ? "/" : request.path();
        if (!protectedPath.matcher(path).find()) {
            return delegate.handle(request);
        }
        String header = request.headers().firstOrNull("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Basic ", 0, 6)) {
            return challenge();
        }
        String b64 = header.substring(6).trim();
        String decoded;
        try { decoded = new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8); }
        catch (IllegalArgumentException e) { return challenge(); }
        int colon = decoded.indexOf(':');
        if (colon < 0) return challenge();
        String user = decoded.substring(0, colon);
        String password = decoded.substring(colon + 1);
        AuthEntry entry = USERS.get(new Credentials(user, password));
        if (entry == null) return challenge();

        CassiniSecurityContext.CURRENT_AUTH.set(new CassiniSecurityContext.AuthInfo(
                entry.username(), "BASIC", entry.roles()));
        try {
            return delegate.handle(request);
        } finally {
            CassiniSecurityContext.CURRENT_AUTH.remove();
        }
    }

    private static Response challenge() {
        return Response.builder()
                .status(StatusCode.UNAUTHORIZED)
                .header("WWW-Authenticate", "Basic realm=\"" + REALM + "\"")
                .body(Body.empty())
                .build();
    }

    private record Credentials(String user, String password) {}
    private record AuthEntry(String username, Set<String> roles) {}
}
