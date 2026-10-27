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
package io.vidocq.cassini.client;

import io.vidocq.cassini.client.support.RecordingServer;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Link;
import jakarta.ws.rs.core.UriBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link Client#target} and {@link WebTarget}: URI building and per-target configuration. */
class WebTargetTest {

    private static final String BASE = "http://example.test";
    private static final String ID = "id";

    private static RecordingServer server;
    private static Client client;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
        client = ClientBuilder.newClient();
    }

    @AfterAll
    static void stop() {
        client.close();
        server.close();
    }

    @Test
    void targets_from_a_string_a_uri_a_builder_and_a_link_agree() {
        URI expected = URI.create(BASE + "/a");
        assertEquals(expected, client.target(BASE + "/a").getUri());
        assertEquals(expected, client.target(expected).getUri());
        assertEquals(expected, client.target(UriBuilder.fromUri(expected)).getUri());
        assertEquals(expected, client.target(Link.fromUri(expected).build()).getUri());
    }

    @Test
    void invocation_from_a_link_requests_its_uri() {
        Link link = Link.fromUri(server.baseUrl() + "/linked").build();
        assertEquals("GET:", client.invocation(link).get(String.class));
        assertEquals("/linked", server.last().uri());
    }

    @Test
    void target_derivations_leave_the_original_untouched() {
        WebTarget base = client.target(BASE);
        WebTarget child = base.path("/items").queryParam("q", "x").matrixParam("m", "1");
        assertEquals(URI.create(BASE), base.getUri());
        assertEquals(URI.create(BASE + "/items;m=1?q=x"), child.getUri());
        UriBuilder copy = child.getUriBuilder();
        copy.path("more");
        assertEquals(URI.create(BASE + "/items;m=1?q=x"), child.getUri());
    }

    @Test
    void templates_resolve_one_by_one_or_from_a_map() {
        WebTarget template = client.target(BASE).path("/{a}/{b}");
        URI expected = URI.create(BASE + "/1/2");
        assertEquals(expected, template.resolveTemplate("a", 1).resolveTemplate("b", 2).getUri());
        assertEquals(expected, template.resolveTemplate("a", 1, true).resolveTemplateFromEncoded("b", 2).getUri());
        assertEquals(expected, template.resolveTemplates(Map.of("a", 1, "b", 2)).getUri());
        assertEquals(expected, template.resolveTemplates(Map.of("a", 1), false)
                .resolveTemplatesFromEncoded(Map.of("b", 2)).getUri());
    }

    @Test
    void resolved_templates_survive_further_derivations() {
        WebTarget target = client.target(server.baseUrl()).path("/items/{id}").resolveTemplate(ID, 7)
                .queryParam("v", 1).matrixParam("m", 2);
        assertEquals("GET:", target.request().get(String.class));
        assertEquals("/items/7;m=2?v=1", server.last().uri());
    }

    @Test
    void target_properties_and_registrations_show_in_its_configuration() {
        WebTarget target = client.target(BASE);
        assertSame(target, target.property("t", "v"));
        assertEquals("v", target.getConfiguration().getProperty("t"));

        target.register(Filter.class).register(Filter2.class, 10).register(Filter3.class, ClientRequestFilter.class)
                .register(Filter4.class, Map.of(ClientRequestFilter.class, 20));
        assertTrue(target.getConfiguration().isRegistered(Filter4.class));
        assertEquals(Map.of(ClientRequestFilter.class, 10), target.getConfiguration().getContracts(Filter2.class));

        Filter instance = new Filter();
        target.register(instance).register(new Filter2(), 11).register(new Filter3(), ClientRequestFilter.class)
                .register(new Filter4(), Map.of(ClientRequestFilter.class, 21));
        assertTrue(target.getConfiguration().isRegistered(instance));
        assertEquals(Map.of(ClientRequestFilter.class, 21), target.getConfiguration().getContracts(Filter4.class));
    }

    public static class Filter implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) { /* registration fixture */ }
    }

    public static class Filter2 implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) { /* registration fixture */ }
    }

    public static class Filter3 implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) { /* registration fixture */ }
    }

    public static class Filter4 implements ClientRequestFilter {
        @Override public void filter(ClientRequestContext ctx) { /* registration fixture */ }
    }
}
