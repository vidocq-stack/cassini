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

import io.vidocq.cassini.internal.runtime.CassiniRuntimeDelegate;
import io.vidocq.cassini.spi.http.CassiniHttpExchange;
import io.vidocq.cassini.spi.http.CassiniStack;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Application;
import jakarta.ws.rs.ext.RuntimeDelegate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The entity of a request is buffered before the MessageBodyReader runs. It was read with
 * {@code readAllBytes}, which asks the transport for 16 KB chunks and allocates them, for bodies
 * of a few dozen bytes whose size the {@code Content-Length} header gave: a tenth of the
 * allocation of the REST benchmark (VidocqTools/vidocq-benchmarks).
 */
class EntityReadSizingTest {

    @BeforeAll
    static void runtimeDelegate() {
        RuntimeDelegate.setInstance(new CassiniRuntimeDelegate());
    }

    @Path("/things")
    public static class ThingResource {
        @POST
        @Consumes("text/plain")
        public String echo(String body) {
            return body;
        }
    }

    private static CassiniStack stack() {
        return new CassiniStackBuilderImpl().application(new Application() {
            @Override
            public Set<Class<?>> getClasses() {
                return Set.of(ThingResource.class);
            }
        }).build();
    }

    @Test
    void aBodyOfKnownLengthIsReadWithABufferOfItsSize() {
        String body = "benchmark todo";
        InMemoryExchange inner = InMemoryExchange.post("/things", "text/plain", body);
        ChunkSpyExchange exchange = new ChunkSpyExchange(inner);
        stack().adapter().dispatch(exchange).toCompletableFuture().join();
        assertEquals(200, inner.status());
        assertEquals(body, inner.responseText());
        assertTrue(exchange.largestRead() <= body.length(), "largest read requested from the transport: "
                + exchange.largestRead() + " bytes for a body of " + body.length());
    }

    @Test
    void anEmptyBodyIsStillAnEmptyEntity() {
        InMemoryExchange inner = InMemoryExchange.post("/things", "text/plain", "");
        stack().adapter().dispatch(new ChunkSpyExchange(inner)).toCompletableFuture().join();
        assertEquals(200, inner.status());
        assertEquals("", inner.responseText());
    }

    /** Delegates everything to an {@link InMemoryExchange} and records the largest read of its body. */
    static final class ChunkSpyExchange implements CassiniHttpExchange {
        private final InMemoryExchange inner;
        private int largestRead;

        ChunkSpyExchange(InMemoryExchange inner) { this.inner = inner; }

        int largestRead() { return largestRead; }

        @Override public InputStream requestBody() {
            InputStream body = inner.requestBody();
            return body == null ? null : new FilterInputStream(body) {
                @Override public int read(byte[] b, int off, int len) throws IOException {
                    largestRead = Math.max(largestRead, len);
                    return super.read(b, off, len);
                }
                @Override public int read() throws IOException {
                    largestRead = Math.max(largestRead, 1);
                    return super.read();
                }
            };
        }

        @Override public String method() { return inner.method(); }
        @Override public URI requestUri() { return inner.requestUri(); }
        @Override public String requestUriRaw() { return inner.requestUriRaw(); }
        @Override public Map<String, List<String>> requestHeaders() { return inner.requestHeaders(); }
        @Override public void setStatus(int code) { inner.setStatus(code); }
        @Override public Map<String, List<String>> responseHeaders() { return inner.responseHeaders(); }
        @Override public OutputStream responseBody() { return inner.responseBody(); }
        @Override public SocketAddress remoteAddress() { return inner.remoteAddress(); }
        @Override public boolean isSecure() { return inner.isSecure(); }
        @Override public String authScheme() { return inner.authScheme(); }
        @Override public Principal userPrincipal() { return inner.userPrincipal(); }
        @Override public boolean isUserInRole(String role) { return inner.isUserInRole(role); }
        @Override public void setAttribute(String key, Object value) { inner.setAttribute(key, value); }
        @Override public Object getAttribute(String key) { return inner.getAttribute(key); }
    }
}
