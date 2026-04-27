package io.vidocq.cassini.internal;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests best-match §3.7.2 : littéral > regex custom > regex par défaut.
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
