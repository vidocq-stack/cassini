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
package io.vidocq.cassini.spi.http;

import java.util.concurrent.CompletionStage;

/**
 * Server entry point for an HTTP transport that drives Cassini.
 *
 * <p>The adapter (Chappe, JDK HttpServer, ...) delegates each incoming request to the
 * Cassini runtime by calling {@link #dispatch(CassiniHttpExchange)}. The return value
 * is a {@link CompletionStage} to allow correct propagation of processing completion
 * (M2h: non-blocking, virtual threads, async @Suspended).
 *
 * <p>Until M2h, the current implementation may return an already-completed stage —
 * the signature remains async to avoid breaking the contract later.
 */
public interface CassiniHttpAdapter {

    /**
     * Processes the incoming request and writes the response.
     *
     * @return a stage that completes when the response body is fully written
     *         (or when the connection is closed on error)
     */
    CompletionStage<Void> dispatch(CassiniHttpExchange exchange);
}
