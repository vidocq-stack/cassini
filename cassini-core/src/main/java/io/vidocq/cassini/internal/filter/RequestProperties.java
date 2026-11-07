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
 * <p>The store is built by the first property set, not before: most requests never set one, and a request
 * context allocates nothing for it. The contexts of a request run one after the other on the dispatch thread
 * and JAX-RS contexts are not thread-safe, so the first {@code set} has no race to lose; the store itself is a
 * concurrent map. The {@code CassiniHttpExchange} SPI has no atomic "create if absent" for an attribute, which
 * is why no stronger promise is made.</p>
 *
 * <p>The static methods work on an exchange; an instance made with {@link #of} does the same for a context
 * that may have no exchange, and then keeps a store of its own, as a context built outside a request does.</p>
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

    /** @return the value of the property, or {@code null}; a {@code null} name asks for nothing */
    public static Object get(CassiniHttpExchange exchange, String name) {
        Map<String, Object> properties = store(exchange);
        return properties == null || name == null ? null : properties.get(name);
    }

    public static Collection<String> names(CassiniHttpExchange exchange) {
        Map<String, Object> properties = store(exchange);
        return properties == null ? Collections.emptySet() : Collections.unmodifiableSet(properties.keySet());
    }

    /** A {@code null} value removes the property (as {@code ContainerRequestContext#setProperty}). */
    public static void set(CassiniHttpExchange exchange, String name, Object value) {
        if (value == null) {
            remove(exchange, name);
            return;
        }
        Objects.requireNonNull(name, "name");
        Map<String, Object> properties = store(exchange);
        if (properties == null) {
            properties = new ConcurrentHashMap<>();
            exchange.setAttribute(ATTR_PROPERTIES, properties);
        }
        properties.put(name, value);
    }

    /** A {@code null} name removes nothing. */
    public static void remove(CassiniHttpExchange exchange, String name) {
        Map<String, Object> properties = store(exchange);
        if (properties != null && name != null) properties.remove(name);
    }

    /**
     * Removes a property of the request and returns its value, without building a context: the dispatch asks for
     * the end-of-request action once the request is over. {@code null} when the request has no such property.
     */
    public static Object take(CassiniHttpExchange exchange, String name) {
        Map<String, Object> properties = store(exchange);
        return properties == null || name == null ? null : properties.remove(name);
    }

    public Object get(String name) {
        if (exchange != null) return get(exchange, name);
        return detached == null || name == null ? null : detached.get(name);
    }

    public Collection<String> names() {
        if (exchange != null) return names(exchange);
        return detached == null ? Collections.emptySet() : Collections.unmodifiableSet(detached.keySet());
    }

    public void set(String name, Object value) {
        if (exchange != null) {
            set(exchange, name, value);
        } else if (value == null) {
            remove(name);
        } else {
            Objects.requireNonNull(name, "name");
            if (detached == null) detached = new HashMap<>();
            detached.put(name, value);
        }
    }

    public void remove(String name) {
        if (exchange != null) {
            remove(exchange, name);
        } else if (detached != null && name != null) {
            detached.remove(name);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> store(CassiniHttpExchange exchange) {
        return (Map<String, Object>) exchange.getAttribute(ATTR_PROPERTIES);
    }
}
