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

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests best-match §3.7.2: literal > custom regex > default regex.
 */
class UriRouterBestMatchTest {

    static class Handlers {
        public void literal() {}
        public void byId() {}
        public void numeric() {}
        public void splat() {}
    }

    private ResourceMethod make(String verb, String template, String methodName) throws Exception {
        Method m = Handlers.class.getDeclaredMethod(methodName);
        return new ResourceMethod(Handlers.class, m, verb, UriTemplate.compile(template), Set.of(), Set.of());
    }

    @Test
    void literalBeatsTemplate() throws Exception {
        ResourceMethod literal = make("GET", "/users/me", "literal");
        ResourceMethod byId = make("GET", "/users/{id}", "byId");
        UriRouter r = new UriRouter(List.of(byId, literal));

        Optional<MatchResult> res = r.match("GET", "/users/me");
        assertTrue(res.isPresent());
        assertEquals("literal", res.get().method().javaMethod().getName());
    }

    @Test
    void customRegexBeatsDefault() throws Exception {
        ResourceMethod numeric = make("GET", "/items/{id:[0-9]+}", "numeric");
        ResourceMethod any = make("GET", "/items/{id}", "byId");
        UriRouter r = new UriRouter(List.of(any, numeric));

        MatchResult res = r.match("GET", "/items/42").orElseThrow();
        assertEquals("numeric", res.method().javaMethod().getName());
        assertEquals(List.of("42"), res.pathParams().get("id"));
    }

    @Test
    void wildcardIsLeastSpecific() throws Exception {
        ResourceMethod splat = make("GET", "/files/{path:.*}", "splat");
        ResourceMethod literal = make("GET", "/files/index.html", "literal");
        UriRouter r = new UriRouter(List.of(splat, literal));

        assertEquals("literal", r.match("GET", "/files/index.html").orElseThrow()
                .method().javaMethod().getName());
        assertEquals("splat", r.match("GET", "/files/a/b/c").orElseThrow()
                .method().javaMethod().getName());
    }

    @Test
    void methodMismatchYieldsEmptyButAllowListed() throws Exception {
        ResourceMethod get = make("GET", "/users/{id}", "byId");
        UriRouter r = new UriRouter(List.of(get));

        assertTrue(r.match("POST", "/users/42").isEmpty());
        assertEquals(List.of("GET"), r.methodsAllowedFor("/users/42"));
    }

    @Test
    void noMatchReturnsEmpty() throws Exception {
        ResourceMethod literal = make("GET", "/hello", "literal");
        UriRouter r = new UriRouter(List.of(literal));

        assertTrue(r.match("GET", "/nope").isEmpty());
    }
}
