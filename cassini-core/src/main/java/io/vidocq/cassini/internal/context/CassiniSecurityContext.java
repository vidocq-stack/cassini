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
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

/**
 * Default security context: anonymous, unsecured (HTTP).
 * If an {@link AuthInfo} has been attached to the exchange (attribute
 * {@link #ATTR_AUTH}, set by an upstream BASIC filter or any transport
 * adapter), it is used to expose userPrincipal / userInRole / scheme.
 *
 * <p>M2h: the exchange reference is captured at construction, so threads
 * spawned by an async resource method still see the request's
 * authentication — no thread-bound state involved.
 */
public final class CassiniSecurityContext implements SecurityContext {

    /**
     * Per-request authentication information, set by the test BASIC bridge
     * ({@code BasicAuthHandler}) or any upstream adapter.
     */
    public record AuthInfo(String username, String authScheme, java.util.Set<String> roles) {}

    /** Exchange attribute carrying the request's {@link AuthInfo}. */
    public static final String ATTR_AUTH = "cassini.auth";

    private final CassiniHttpExchange exchange;

    public CassiniSecurityContext(CassiniHttpExchange exchange) {
        this.exchange = exchange;
    }

    private AuthInfo authInfo() {
        return exchange == null ? null : (AuthInfo) exchange.getAttribute(ATTR_AUTH);
    }

    @Override public Principal getUserPrincipal() {
        AuthInfo a = authInfo();
        if (a == null || a.username() == null) return null;
        return () -> a.username();
    }

    @Override public boolean isUserInRole(String role) {
        AuthInfo a = authInfo();
        return a != null && a.roles() != null && a.roles().contains(role);
    }

    @Override public boolean isSecure() { return exchange != null && exchange.isSecure(); }

    @Override public String getAuthenticationScheme() {
        AuthInfo a = authInfo();
        return a == null ? null : a.authScheme();
    }
}
