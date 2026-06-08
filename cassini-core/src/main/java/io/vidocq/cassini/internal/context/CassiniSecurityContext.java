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
 * If an {@link AuthInfo} has been placed in a ThreadLocal (by an upstream BASIC filter),
 * it is used to expose userPrincipal / userInRole / scheme.
 */
public final class CassiniSecurityContext implements SecurityContext {

    /**
     * Per-request authentication information, set by the test BASIC bridge
     * ({@code BasicAuthHandler}) or any upstream adapter.
     */
    public record AuthInfo(String username, String authScheme, java.util.Set<String> roles) {}

    // InheritableThreadLocal: inherited by virtual threads created in the adapter (M2h).
    public static final ThreadLocal<AuthInfo> CURRENT_AUTH = new InheritableThreadLocal<>();

    private final CassiniHttpExchange exchange;

    public CassiniSecurityContext(CassiniHttpExchange exchange) {
        this.exchange = exchange;
    }

    @Override public Principal getUserPrincipal() {
        AuthInfo a = CURRENT_AUTH.get();
        if (a == null || a.username() == null) return null;
        return () -> a.username();
    }

    @Override public boolean isUserInRole(String role) {
        AuthInfo a = CURRENT_AUTH.get();
        return a != null && a.roles() != null && a.roles().contains(role);
    }

    @Override public boolean isSecure() { return exchange != null && exchange.isSecure(); }

    @Override public String getAuthenticationScheme() {
        AuthInfo a = CURRENT_AUTH.get();
        return a == null ? null : a.authScheme();
    }
}
