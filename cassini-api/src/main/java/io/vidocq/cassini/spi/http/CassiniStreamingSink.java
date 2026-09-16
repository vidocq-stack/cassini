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
 * Contract for pushing chunks as they become available while the resource
 * method is still executing.
 *
 * <p>Used by:
 * <ul>
 *   <li><b>SSE</b> (JAX-RS §11) — each {@code SseEventSink#send} writes a
 *       {@code event:/data:/...} chunk.</li>
 *   <li><b>StreamingOutput async</b> — progressive push of a chunked-transfer body.</li>
 * </ul>
 *
 * <p><b>Contract</b>: chunks reach the wire incrementally, as {@link
 * #writeChunk} and {@link #flush} are called — not buffered and emitted in
 * one go at the end of the resource method. The response stays open until
 * {@link #close}. This holds for both backends: {@code cassini-chappe}
 * pushes via a pipe-backed chunked-transfer body, and {@code
 * cassini-jdk-http} sends headers with {@code sendResponseHeaders(status, 0)}
 * (chunked) and writes directly to the exchange's response body.
 */
public interface CassiniStreamingSink {

    CompletionStage<Void> writeChunk(byte[] data);

    CompletionStage<Void> flush();

    CompletionStage<Void> close();

    boolean isOpen();
}
