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

import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.net.URI;
import java.util.List;

/**
 * Per-request state carrier, bound to the request's virtual thread via a
 * {@link ScopedValue} (M2h). Replaces the per-request {@code ThreadLocal}s
 * that used to be scattered across {@code Invoker}, {@code ParamExtractor}
 * and {@code CassiniResponseBuilder}: one binding per dispatch, mutable
 * slots written as the request progresses, and a guaranteed bounded
 * lifetime (the binding ends when the dispatch returns — nothing can leak
 * onto a pooled carrier thread).
 *
 * <p>The binding is established by {@code DefaultCassiniHttpAdapter.dispatch}
 * (one per request) and defensively by the public {@code Invoker} entry
 * points ({@code invoke}, {@code runPreMatching}, {@code renderThrowable})
 * when they are called outside a dispatch. Nested entries reuse the
 * existing binding, so mid-flow writes (e.g. the matched route) stay
 * visible across the whole request.
 *
 * <p>Threads spawned by resource methods (async {@code resume()},
 * SSE producers) do <em>not</em> inherit the binding — values they need
 * are captured by reference at injection time (e.g. the exchange inside
 * {@code CassiniSecurityContext}) or travel through the
 * {@link CassiniHttpExchange} attribute store, never through thread state.
 */
public final class RequestScope {

    private static final ScopedValue<RequestScope> SCOPE = ScopedValue.newInstance();

    // Mutable per-request slots — written by the request virtual thread.
    MatchResult match;
    CassiniHttpExchange request;
    jakarta.ws.rs.ext.Providers providers;
    List<jakarta.ws.rs.ext.ParamConverterProvider> paramConverterProviders;
    jakarta.ws.rs.core.Application application;
    io.vidocq.cassini.internal.sse.CassiniSseEventSink sink;
    private URI baseUri;

    private RequestScope() {
    }

    /** @return the scope bound to the current thread, or {@code null} outside a dispatch. */
    public static RequestScope current() {
        // Not orElse(null): ScopedValue.orElse rejects a null fallback.
        return SCOPE.isBound() ? SCOPE.get() : null;
    }

    /**
     * Runs {@code body} inside a request scope: reuses the current binding when
     * one is active (nested entry), otherwise binds a fresh scope for the call.
     */
    public static <T, X extends Throwable> T call(ScopedValue.CallableOp<T, X> body) throws X {
        if (SCOPE.isBound()) {
            return body.call();
        }
        return ScopedValue.where(SCOPE, new RequestScope()).call(body);
    }

    /** Base URI used to resolve relative URIs per §6.7 (null outside a request). */
    public URI baseUri() {
        return baseUri;
    }

    public void baseUri(URI base) {
        this.baseUri = base;
    }
}
