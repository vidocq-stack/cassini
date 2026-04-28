package io.vidocq.cassini.internal;

import java.util.List;
import java.util.Map;

/**
 * Résultat d'un match {@link UriRouter} : la {@link ResourceMethod} choisie
 * et les valeurs capturées par les templates URI. Chaque nom logique peut
 * avoir plusieurs valeurs (template répété : /{id}/{id}/{id}) pour supporter
 * l'injection {@code @PathParam List<String>} (§3.3.1).
 *
 * <p>{@code pathParams} contient les valeurs sans matrix params (pour la plupart
 * des types), {@code rawPathParams} conserve les segments originaux avec leurs
 * matrix params (utilisé pour l'injection {@link jakarta.ws.rs.core.PathSegment}).</p>
 */
public record MatchResult(ResourceMethod method,
                          Map<String, List<String>> pathParams,
                          Map<String, List<String>> rawPathParams) {

    public MatchResult(ResourceMethod method, Map<String, List<String>> pathParams) {
        this(method, pathParams, pathParams);
    }
}
