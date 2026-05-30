package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

/**
 * Default security context: anonymous, not secure (HTTP).
 * If an {@link AuthInfo} was placed in the ThreadLocal (by an upstream
 * BASIC filter), it is used to expose userPrincipal / userInRole / scheme.
 */
public final class CassiniSecurityContext implements SecurityContext {

    /**
     * Per-request authentication information, set by the BASIC test bridge
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
