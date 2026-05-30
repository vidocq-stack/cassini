package io.vidocq.cassini.internal;

import java.util.List;
import java.util.Map;

/**
 * Result of a {@link UriRouter} match: the chosen {@link ResourceMethod}
 * and the values captured by the URI templates. Each logical name can have
 * several values (repeated template: /{id}/{id}/{id}) to support
 * {@code @PathParam List<String>} injection (§3.3.1).
 *
 * <p>{@code pathParams} contains values without matrix params (for most
 * types), {@code rawPathParams} keeps the original segments with their
 * matrix params (used for {@link jakarta.ws.rs.core.PathSegment} injection).</p>
 */
public record MatchResult(ResourceMethod method,
                          Map<String, List<String>> pathParams,
                          Map<String, List<String>> rawPathParams) {

    public MatchResult(ResourceMethod method, Map<String, List<String>> pathParams) {
        this(method, pathParams, pathParams);
    }
}
