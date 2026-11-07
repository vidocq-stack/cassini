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

import io.vidocq.cassini.internal.InMemoryExchange;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The store behind the properties of a request (CASSINI-007, cassini#116). */
class RequestPropertiesStoreTest {

    private static InMemoryExchange exchange() {
        return InMemoryExchange.request("GET", "/", null, null);
    }

    @Test
    void everyViewOfTheSameExchangeSharesTheStore() {
        InMemoryExchange exchange = exchange();
        RequestProperties.install(exchange);
        RequestProperties.of(exchange).set("a", "1");
        assertEquals("1", RequestProperties.of(exchange).get("a"));
        assertEquals(Set.of("a"), Set.copyOf(RequestProperties.of(exchange).names()));
    }

    @Test
    void installingTwiceKeepsTheStoreAndItsProperties() {
        InMemoryExchange exchange = exchange();
        RequestProperties.install(exchange);
        RequestProperties.of(exchange).set("a", "1");
        RequestProperties.install(exchange);
        assertEquals("1", RequestProperties.of(exchange).get("a"));
    }

    @Test
    void aStoreBuiltWithoutAnExchangeIsItsOwn() {
        RequestProperties alone = RequestProperties.of(null);
        RequestProperties other = RequestProperties.of(null);
        alone.set("a", "1");
        assertEquals("1", alone.get("a"));
        assertNull(other.get("a"));
        alone.remove("a");
        assertNull(alone.get("a"));
        assertTrue(alone.names().isEmpty());
    }

    @Test
    void aNullNameAsksForNothingAndRemovesNothing() {
        InMemoryExchange exchange = exchange();
        RequestProperties.install(exchange);
        RequestProperties properties = RequestProperties.of(exchange);
        properties.set("a", "1");
        assertNull(properties.get(null));
        assertDoesNotThrow(() -> properties.remove(null));
        assertEquals("1", properties.get("a"));
    }

    @Test
    void aNullNameCannotBeSet() {
        RequestProperties properties = RequestProperties.of(exchange());
        assertThrows(NullPointerException.class, () -> properties.set(null, "x"));
    }

    @Test
    void aNullValueRemovesTheProperty() {
        RequestProperties properties = RequestProperties.of(exchange());
        properties.set("a", "1");
        properties.set("a", null);
        assertNull(properties.get("a"));
    }

    @Test
    void takingAPropertyRemovesIt() {
        InMemoryExchange exchange = exchange();
        RequestProperties.of(exchange).set("a", "1");
        assertEquals("1", RequestProperties.take(exchange, "a"));
        assertNull(RequestProperties.take(exchange, "a"));
        assertNull(RequestProperties.take(exchange(), "a"), "an exchange without a store has nothing to take");
    }
}
