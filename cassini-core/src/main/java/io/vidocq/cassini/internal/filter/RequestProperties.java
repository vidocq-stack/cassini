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
package io.vidocq.cassini.internal.filter;

import io.vidocq.cassini.spi.http.CassiniHttpExchange;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The properties of a request: one store for the whole request, held by the exchange. Every context that
 * exposes {@code getProperty} / {@code setProperty} — the request and response contexts of the filters, the
 * reader and writer interceptor contexts — reads and writes this store, so a property set by a filter reaches
 * the interceptors and the other way round (CASSINI-007; JAX-RS 4.0 {@code InterceptorContext#getProperty}: the
 * properties of the current request/response exchange).
 *
 * <p>The dispatch {@linkplain #install installs} the store before any filter runs, on the dispatch thread: its
 * creation needs no atomic operation of the exchange. A context built without an exchange keeps a store of its
 * own, as a context built outside a request does.</p>
 */
public final class RequestProperties {

    /** Exchange attribute holding the store. */
    private static final String ATTR_PROPERTIES = "io.vidocq.cassini.requestProperties";

    private final CassiniHttpExchange exchange;
    /** The store of a context built without an exchange; {@code null} otherwise. */
    private Map<String, Object> detached;

    private RequestProperties(CassiniHttpExchange exchange) {
        this.exchange = exchange;
    }

    /** The properties of the request of {@code exchange}; a store of its own when {@code exchange} is {@code null}. */
    public static RequestProperties of(CassiniHttpExchange exchange) {
        return new RequestProperties(exchange);
    }

    /**
     * Installs the store of the request. Called once, before any filter, on the dispatch thread: the store is then
     * there for every context, and no two threads race to create it.
     */
    public static void install(CassiniHttpExchange exchange) {
        if (exchange.getAttribute(ATTR_PROPERTIES) == null) {
            exchange.setAttribute(ATTR_PROPERTIES, new ConcurrentHashMap<String, Object>());
        }
    }

    /**
     * Removes a property of the request and returns its value, without building a context: the dispatch asks for
     * the end-of-request action once the request is over. {@code null} when the request has no such property.
     */
    public static Object take(CassiniHttpExchange exchange, String name) {
        Object shared = exchange.getAttribute(ATTR_PROPERTIES);
        return shared instanceof Map<?, ?> properties ? properties.remove(name) : null;
    }

    /** @return the value of the property, or {@code null}; a {@code null} name asks for nothing */
    public Object get(String name) {
        Map<String, Object> properties = store(false);
        return properties == null || name == null ? null : properties.get(name);
    }

    public Collection<String> names() {
        Map<String, Object> properties = store(false);
        return properties == null ? Collections.emptySet() : Collections.unmodifiableSet(properties.keySet());
    }

    /** A {@code null} value removes the property (as {@code ContainerRequestContext#setProperty}). */
    public void set(String name, Object value) {
        if (value == null) {
            remove(name);
            return;
        }
        Objects.requireNonNull(name, "name");
        store(true).put(name, value);
    }

    /** A {@code null} name removes nothing. */
    public void remove(String name) {
        Map<String, Object> properties = store(false);
        if (properties != null && name != null) properties.remove(name);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> store(boolean create) {
        if (exchange == null) {
            if (detached == null && create) detached = new HashMap<>();
            return detached;
        }
        Object shared = exchange.getAttribute(ATTR_PROPERTIES);
        if (shared == null && create) {
            // A request not dispatched through Cassini's adapter (a context built by hand): the store is
            // created by the first property, on the thread that builds the contexts.
            shared = new ConcurrentHashMap<String, Object>();
            exchange.setAttribute(ATTR_PROPERTIES, shared);
        }
        return (Map<String, Object>) shared;
    }
}
