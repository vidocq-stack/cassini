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
package io.vidocq.cassini.internal.context;

import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.ext.ContextResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * §4.3 {@code Providers.getContextResolver}: the resolver whose {@code @Produces} matches the
 * requested media type wins, the most specific one first; no {@code @Produces} means {@code *}{@code /*}.
 */
class CassiniProvidersTest {

    static final class Config {
    }

    static class AnyConfigResolver implements ContextResolver<Config> {
        @Override public Config getContext(Class<?> type) { return new Config(); }
    }

    @Produces("application/*")
    static final class ApplicationConfigResolver implements ContextResolver<Config> {
        @Override public Config getContext(Class<?> type) { return new Config(); }
    }

    @Produces({"text/plain", "application/json"})
    static final class JsonConfigResolver implements ContextResolver<Config> {
        @Override public Config getContext(Class<?> type) { return new Config(); }
    }

    @Produces("text/html")
    static final class HtmlConfigResolver implements ContextResolver<Config> {
        @Override public Config getContext(Class<?> type) { return new Config(); }
    }

    /** Inherits its type argument from {@link AnyConfigResolver}. */
    static final class InheritedConfigResolver extends AnyConfigResolver {
    }

    static final class StringResolver implements ContextResolver<String> {
        @Override public String getContext(Class<?> type) { return "s"; }
    }

    private static final MediaType JSON = MediaType.APPLICATION_JSON_TYPE;

    private static CassiniProviders providers(ContextResolver<?>... resolvers) {
        return new CassiniProviders(null, null, List.of(resolvers));
    }

    @Test
    void concreteProducesBeatsWildcardSubtypeAndImplicitWildcard() {
        var json = new JsonConfigResolver();
        var cassini = providers(new AnyConfigResolver(), new ApplicationConfigResolver(), json);

        assertSame(json, cassini.getContextResolver(Config.class, JSON));
    }

    @Test
    void wildcardSubtypeBeatsImplicitWildcard() {
        var application = new ApplicationConfigResolver();
        var cassini = providers(new AnyConfigResolver(), application, new HtmlConfigResolver());

        assertSame(application, cassini.getContextResolver(Config.class, MediaType.APPLICATION_XML_TYPE));
    }

    @Test
    void resolverWhoseProducesDoesNotMatchIsSkipped() {
        assertNull(providers(new HtmlConfigResolver()).getContextResolver(Config.class, JSON));
    }

    @Test
    void nullMediaTypeMatchesEveryProduces() {
        var html = new HtmlConfigResolver();

        assertSame(html, providers(html).getContextResolver(Config.class, null));
    }

    @Test
    void resolverForAnotherTypeIsSkipped() {
        var inherited = new InheritedConfigResolver();
        var cassini = providers(new StringResolver(), inherited);

        assertSame(inherited, cassini.getContextResolver(Config.class, JSON));
    }

    @Test
    void noResolverGivesNull() {
        assertNull(providers().getContextResolver(Config.class, JSON));
    }
}
