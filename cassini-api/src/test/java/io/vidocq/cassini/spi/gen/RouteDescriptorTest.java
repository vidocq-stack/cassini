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
package io.vidocq.cassini.spi.gen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A route descriptor is a value: two descriptors of the same route are equal even when the
 * generated code built their arrays separately, and its text shows the array contents.
 */
class RouteDescriptorTest {

    private static RouteDescriptor route(String... produces) {
        return new RouteDescriptor(RouteDescriptorTest.class, "get", new String[]{"java.lang.String"},
                "GET", "/items/{id}", produces, new String[]{"application/json"}, 6);
    }

    @Test
    void two_descriptors_of_the_same_route_are_equal() {
        RouteDescriptor a = route("text/plain");
        RouteDescriptor b = route("text/plain");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void descriptors_that_differ_by_an_array_element_are_not_equal() {
        assertNotEquals(route("text/plain"), route("text/html"));
        assertNotEquals(route("text/plain"), new RouteDescriptor(RouteDescriptorTest.class, "get",
                new String[]{"java.lang.String"}, "GET", "/items/{id}", new String[]{"text/plain"},
                new String[]{"application/json"}, 7));
    }

    @Test
    void every_component_takes_part_in_equality() {
        RouteDescriptor base = route("text/plain");
        String[] params = {"java.lang.String"};
        String[] produces = {"text/plain"};
        String[] consumes = {"application/json"};
        assertEquals(base, base);
        assertNotEquals(null, base);
        assertNotEquals("a string", base);
        assertNotEquals(base, new RouteDescriptor(String.class, "get", params, "GET", "/items/{id}", produces, consumes, 6));
        assertNotEquals(base, new RouteDescriptor(RouteDescriptorTest.class, "put", params, "GET", "/items/{id}", produces, consumes, 6));
        assertNotEquals(base, new RouteDescriptor(RouteDescriptorTest.class, "get", new String[0], "GET", "/items/{id}", produces, consumes, 6));
        assertNotEquals(base, new RouteDescriptor(RouteDescriptorTest.class, "get", params, "PUT", "/items/{id}", produces, consumes, 6));
        assertNotEquals(base, new RouteDescriptor(RouteDescriptorTest.class, "get", params, "GET", "/items", produces, consumes, 6));
        assertNotEquals(base, new RouteDescriptor(RouteDescriptorTest.class, "get", params, "GET", "/items/{id}", produces, new String[0], 6));
    }

    @Test
    void the_text_shows_the_array_contents() {
        String text = route("text/plain").toString();
        assertTrue(text.contains("[text/plain]"), text);
        assertTrue(text.contains("[java.lang.String]"), text);
        assertTrue(text.contains("[application/json]"), text);
    }
}
