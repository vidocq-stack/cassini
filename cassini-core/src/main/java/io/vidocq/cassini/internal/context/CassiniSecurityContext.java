package io.vidocq.cassini.internal.context;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

/**
 * Contexte de sécurité par défaut : anonyme, non sécurisé (HTTP).
 * Si un {@link AuthInfo} a été placé en ThreadLocal (par un filtre BASIC
 * en amont), on l'utilise pour exposer userPrincipal / userInRole / scheme.
 */
public final class CassiniSecurityContext implements SecurityContext {

    /**
     * Information d'authentification per-request, posée par le bridge de
     * test BASIC ({@code BasicAuthHandler}) ou tout adaptateur amont.
     */
    public record AuthInfo(String username, String authScheme, java.util.Set<String> roles) {}

    public static final ThreadLocal<AuthInfo> CURRENT_AUTH = new ThreadLocal<>();

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
