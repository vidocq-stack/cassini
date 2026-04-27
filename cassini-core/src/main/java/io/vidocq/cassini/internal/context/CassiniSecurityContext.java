package io.vidocq.cassini.internal.context;

import fr.vidocq.chappe.api.Request;
import jakarta.ws.rs.core.SecurityContext;

import java.security.Principal;

/**
 * Contexte de sécurité par défaut : anonyme, non sécurisé (HTTP).
 * Les extensions future pourront surcharger via une ChappeSecurityAdapter.
 */
public final class CassiniSecurityContext implements SecurityContext {

    private final Request request;

    public CassiniSecurityContext(Request request) {
        this.request = request;
    }

    @Override public Principal getUserPrincipal() { return null; }
    @Override public boolean isUserInRole(String role) { return false; }
    @Override public boolean isSecure() { return request != null && request.isSecure(); }
    @Override public String getAuthenticationScheme() { return null; }
}
