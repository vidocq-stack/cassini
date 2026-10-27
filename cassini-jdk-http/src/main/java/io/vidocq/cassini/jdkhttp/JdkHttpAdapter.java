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
package io.vidocq.cassini.jdkhttp;

import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.vidocq.cassini.spi.http.CassiniHttpAdapter;
import io.vidocq.cassini.spi.http.CassiniStack;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;

/**
 * HTTP adapter based on {@link HttpServer} (pure JDK).
 *
 * <p>Acts as a standalone transport for Cassini in Mode A, with no external
 * dependency. Used for pure Cassini unit tests and as the reference transport
 * when the user does not want to depend on Chappe.
 *
 * <p>Each request runs on a virtual thread via
 * {@link java.util.concurrent.Executors#newVirtualThreadPerTaskExecutor()} —
 * configure the {@link HttpServer} executor accordingly (or use
 * {@link #serve(int)}, which does it automatically).
 */
public final class JdkHttpAdapter {

    private static final System.Logger LOG = System.getLogger(JdkHttpAdapter.class.getName());

    private final CassiniHttpAdapter engine;
    private final String contextPath;

    public JdkHttpAdapter(CassiniHttpAdapter engine) {
        this(engine, "");
    }

    public JdkHttpAdapter(CassiniHttpAdapter engine, String contextPath) {
        this.engine = engine;
        this.contextPath = contextPath == null ? "" : contextPath;
    }

    /** Creates a JDK {@link HttpHandler} that dispatches to Cassini. */
    public HttpHandler asHandler() {
        return jdkExchange -> {
            JdkHttpExchange exchange = new JdkHttpExchange(jdkExchange, contextPath);
            try {
                engine.dispatch(exchange).toCompletableFuture().get();
                writeResponse(jdkExchange, exchange);
            } catch (InterruptedException e) {
                try {
                    failed(jdkExchange, e);
                } finally {
                    // After the 500 went out: an interrupted thread could not write it on an interruptible channel.
                    Thread.currentThread().interrupt();
                }
            } catch (Exception e) {
                failed(jdkExchange, e);
            }
        };
    }

    /**
     * Ends a request that failed with an empty 500 (cassini#58): the exception message, and the header values or
     * request data it may carry, reach neither the client nor the log. Nothing more is sent when the response had
     * already started.
     */
    private static void failed(com.sun.net.httpserver.HttpExchange jdkEx, Exception e) throws IOException {
        Throwable cause = e;
        while ((cause instanceof java.util.concurrent.ExecutionException
                || cause instanceof java.util.concurrent.CompletionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String request = jdkEx.getRequestMethod() + " "
                + ResponseHeaders.printable(jdkEx.getRequestURI().getPath());
        if (cause instanceof ResponseHeaders.Rejected rejected) {
            LOG.log(System.Logger.Level.ERROR, "Response header " + ResponseHeaders.printable(rejected.name())
                    + " of " + request + " cannot be sent (" + rejected.reason() + "); answering 500 instead."
                    + " The value is not logged. " + ResponseHeaders.RULE);
        } else {
            // Only the class: the message may carry request or header values. The stack trace goes
            // through a message-less copy.
            var trace = new RuntimeException(cause.getClass().getName() + " (message not logged)");
            trace.setStackTrace(cause.getStackTrace());
            LOG.log(System.Logger.Level.ERROR, "Request " + request + " failed (" + cause.getClass().getName()
                    + "); answering 500 instead. The exception message is not logged.", trace);
        }
        if (jdkEx.getResponseCode() == -1) {
            jdkEx.getResponseHeaders().clear();
            jdkEx.sendResponseHeaders(500, -1);
        }
        jdkEx.close();
    }

    private static void writeResponse(com.sun.net.httpserver.HttpExchange jdkEx,
                                      JdkHttpExchange exchange) throws IOException {
        var headers = exchange.collectedHeaders();
        ResponseHeaders.check(headers);
        for (var e : headers.entrySet()) {
            jdkEx.getResponseHeaders().put(e.getKey(), new ArrayList<>(e.getValue()));
        }
        byte[] body = exchange.collectedBody();
        jdkEx.sendResponseHeaders(exchange.collectedStatus(), body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (var os = jdkEx.getResponseBody()) { os.write(body); }
        } else {
            jdkEx.close();
        }
    }

    /** Starts a JDK {@link HttpServer} on {@code port} and binds the adapter to {@code contextPath}. */
    public HttpServer serve(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(contextPath.isEmpty() ? "/" : contextPath, asHandler());
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server;
    }

    /**
     * Starts a server with a JAX-RS {@link Application} bootstrapped through
     * {@link CassiniStack}.
     */
    public static HttpServer serve(int port, jakarta.ws.rs.core.Application app)
            throws IOException {
        var stack = CassiniStack.builder().application(app).build();
        return new JdkHttpAdapter(stack.adapter()).serve(port);
    }
}
