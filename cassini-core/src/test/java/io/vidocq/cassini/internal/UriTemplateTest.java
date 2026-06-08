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

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UriTemplateTest {

    @Test
    void literalMatches() {
        UriTemplate t = UriTemplate.compile("/hello");
        assertEquals(6, t.literalChars());
        assertEquals(0, t.totalCaptures());
        assertTrue(t.match("/hello").isPresent());
        assertTrue(t.match("/hello/world").isEmpty());
    }

    @Test
    void defaultParamCaptures() {
        UriTemplate t = UriTemplate.compile("/users/{id}");
        Optional<Map<String, List<String>>> m = t.match("/users/42");
        assertTrue(m.isPresent());
        assertEquals(List.of("42"), m.get().get("id"));
        assertTrue(t.match("/users/42/posts").isEmpty());
        assertEquals(1, t.totalCaptures());
        assertEquals(1, t.defaultCaptures());
    }

    @Test
    void regexParamConstrainsMatch() {
        UriTemplate t = UriTemplate.compile("/orders/{id:[0-9]+}");
        assertTrue(t.match("/orders/42").isPresent());
        assertTrue(t.match("/orders/abc").isEmpty());
        assertEquals(1, t.totalCaptures());
        assertEquals(0, t.defaultCaptures());
    }

    @Test
    void greedyRegexSpansSegments() {
        UriTemplate t = UriTemplate.compile("/files/{path:.*}");
        Optional<Map<String, List<String>>> m = t.match("/files/a/b/c.txt");
        assertTrue(m.isPresent());
        assertEquals(List.of("a/b/c.txt"), m.get().get("path"));
    }

    @Test
    void multiParamTemplate() {
        UriTemplate t = UriTemplate.compile("/users/{uid}/posts/{pid}");
        Optional<Map<String, List<String>>> m = t.match("/users/42/posts/7");
        assertTrue(m.isPresent());
        assertEquals(List.of("42"), m.get().get("uid"));
        assertEquals(List.of("7"), m.get().get("pid"));
    }

    @Test
    void rejectsMalformedTemplate() {
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.compile("/bad/{unclosed"));
        assertThrows(IllegalArgumentException.class, () -> UriTemplate.compile("/bad/{}"));
    }

    @Test
    void rootTemplate() {
        UriTemplate t = UriTemplate.compile("/");
        assertTrue(t.match("/").isPresent());
        assertTrue(t.match("/x").isEmpty());
    }

    @Test
    void literalCharsCountsDisregardCaptures() {
        UriTemplate t1 = UriTemplate.compile("/users/42");
        UriTemplate t2 = UriTemplate.compile("/users/{id}");
        assertTrue(t1.literalChars() > t2.literalChars());
    }
}
