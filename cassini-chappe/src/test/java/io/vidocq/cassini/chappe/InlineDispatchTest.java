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
package io.vidocq.cassini.chappe;

import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.chappe.api.Body;
import io.vidocq.chappe.api.Headers;
import io.vidocq.chappe.api.HttpMethod;
import io.vidocq.chappe.api.HttpVersion;
import io.vidocq.chappe.api.Request;
import io.vidocq.chappe.api.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Every request used to start a virtual thread for its dispatch, and the connection thread
 * waited for it: two scheduler hand-offs and a thread start per request. The thread is needed
 * only when a dispatch may hand its response to Chappe as a stream before it ends (a resource
 * method with an {@code SseEventSink}); an engine that says it never does is dispatched on the
 * calling thread.
 */
class InlineDispatchTest {

    /** An engine that answers "hello" and records the thread it ran on. */
    private static final class RecordingEngine implements CassiniHttpAdapter {
        final AtomicReference<Thread> ranOn = new AtomicReference<>();
        final boolean mayStream;
        final RuntimeException failure;

        RecordingEngine(boolean mayStream, RuntimeException failure) {
            this.mayStream = mayStream;
            this.failure = failure;
        }

        @Override
        public CompletionStage<Void> dispatch(CassiniHttpExchange exchange) {
            ranOn.set(Thread.currentThread());
            if (failure != null) throw failure;
            exchange.setStatus(200);
            exchange.responseHeaders().put("Content-Type", List.of("text/plain"));
            try {
                exchange.responseBody().write("hello".getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public boolean mayStream() {
            return mayStream;
        }
    }

    /** A request scope hook, as the Vidocq runtime installs one: it bounds the scope by the call. */
    private static final ChappeHttpAdapter.Scoped SCOPE = Runnable::run;

    @Test
    void anEngineThatNeverStreamsIsDispatchedOnTheCallingThread() throws Exception {
        var engine = new RecordingEngine(false, null);
        Response response = new ChappeHttpAdapter(engine, SCOPE).handle(request());
        assertSame(Thread.currentThread(), engine.ranOn.get());
        assertEquals(200, response.status().code());
        assertEquals("hello", new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertEquals("text/plain", response.headers().firstOrNull("Content-Type"));
    }

    @Test
    void anEngineThatMayStreamKeepsItsOwnThread() throws Exception {
        var engine = new RecordingEngine(true, null);
        Response response = new ChappeHttpAdapter(engine, SCOPE).handle(request());
        assertNotSame(Thread.currentThread(), engine.ranOn.get());
        assertEquals(200, response.status().code());
    }

    /**
     * Without a scope hook, a CDI container may keep the request context on the thread (Weld's
     * {@code RequestContextController}, activated by a request filter): the thread started per
     * request is what discards it, so it stays.
     */
    @Test
    void withoutARequestScopeHookTheDispatchKeepsItsOwnThread() throws Exception {
        var engine = new RecordingEngine(false, null);
        new ChappeHttpAdapter(engine).handle(request());
        assertNotSame(Thread.currentThread(), engine.ranOn.get());
    }

    @Test
    void aFailedInlineDispatchFailsTheRequestAsBefore() {
        var failure = new IllegalStateException("boom");
        var adapter = new ChappeHttpAdapter(new RecordingEngine(false, failure), SCOPE);
        var request = request();
        assertSame(failure, assertThrows(IllegalStateException.class, () -> adapter.handle(request)));
    }

    @Test
    void theRequestScopeHookStillWrapsAnInlineDispatch() throws Exception {
        var engine = new RecordingEngine(false, null);
        var scopes = new AtomicReference<Thread>();
        ChappeHttpAdapter.Scoped scoped = action -> {
            scopes.set(Thread.currentThread());
            action.run();
        };
        new ChappeHttpAdapter(engine, scoped).handle(request());
        assertSame(Thread.currentThread(), scopes.get());
        assertSame(Thread.currentThread(), engine.ranOn.get());
    }

    private static Request request() {
        return new Request() {
            @Override public HttpMethod method() { return HttpMethod.GET; }
            @Override public URI uri() { return URI.create("/things"); }
            @Override public String path() { return "/things"; }
            @Override public String query() { return null; }
            @Override public HttpVersion version() { return HttpVersion.HTTP_1_1; }
            @Override public Headers headers() { return Headers.empty(); }
            @Override public Body body() { return Body.empty(); }
            @Override public Map<String, String> pathParams() { return Map.of(); }
            @Override public Map<String, String> queryParams() { return Map.of(); }
        };
    }
}
