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
