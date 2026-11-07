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
import jakarta.ws.rs.core.Feature;
import jakarta.ws.rs.core.FeatureContext;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CASSINI-024 items 3 and 4: a {@link WebTarget} owns a copy of the client's configuration
 * (its {@code register}/{@code property} never reach the parent client or the sibling targets),
 * and a registered {@link Feature} is configured and reported by {@code Configuration.isEnabled}.
 */
class TargetConfigurationTest {

    private static RecordingServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** Adds the header {@code X-Mark: <mark>}. */
    public static final class Mark implements ClientRequestFilter {
        private final String mark;
        public Mark(String mark) { this.mark = mark; }
        @Override public void filter(ClientRequestContext ctx) { ctx.getHeaders().putSingle("X-Mark", mark); }
    }

    /** A feature that registers a {@link Mark} filter and counts its configurations. */
    public static final class MarkFeature implements Feature {
        final AtomicInteger configured = new AtomicInteger();
        private final boolean enable;
        public MarkFeature(boolean enable) { this.enable = enable; }
        @Override public boolean configure(FeatureContext context) {
            configured.incrementAndGet();
            context.register(new Mark("from-feature"));
            return enable;
        }
    }

    private static String markSentBy(WebTarget target) {
        assertEquals(200, target.request().get().getStatus());
        return server.last().header("X-Mark");
    }

    // ---- item 3 ---------------------------------------------------------------------------

    @Test
    void registering_on_a_target_does_not_touch_the_client() {
        try (Client client = ClientBuilder.newClient()) {
            Mark filter = new Mark("target");
            WebTarget target = client.target(server.baseUrl()).register(filter);
            assertTrue(target.getConfiguration().isRegistered(filter));
            assertFalse(client.getConfiguration().isRegistered(filter));
            assertEquals("target", markSentBy(target));
            assertNull(markSentBy(client.target(server.baseUrl())));
        }
    }

    @Test
    void registering_on_a_target_does_not_touch_its_siblings() {
        try (Client client = ClientBuilder.newClient()) {
            WebTarget marked = client.target(server.baseUrl()).register(new Mark("marked"));
            WebTarget sibling = client.target(server.baseUrl());
            assertEquals("marked", markSentBy(marked));
            assertNull(markSentBy(sibling));
        }
    }

    @Test
    void a_property_set_on_a_target_does_not_touch_the_client() {
        try (Client client = ClientBuilder.newClient()) {
            WebTarget target = client.target(server.baseUrl()).property("p", "v");
            assertEquals("v", target.getConfiguration().getProperty("p"));
            assertNull(client.getConfiguration().getProperty("p"));
        }
    }

    @Test
    void a_target_starts_from_the_client_configuration() {
        try (Client client = ClientBuilder.newClient().register(new Mark("client")).property("p", "v")) {
            WebTarget target = client.target(server.baseUrl());
            assertEquals("v", target.getConfiguration().getProperty("p"));
            assertEquals("client", markSentBy(target));
        }
    }

    @Test
    void a_derived_target_inherits_the_configuration_without_sharing_it_back() {
        try (Client client = ClientBuilder.newClient()) {
            WebTarget parent = client.target(server.baseUrl()).register(new Mark("parent"));
            WebTarget child = parent.path("sub").register(new Mark("child"));
            assertEquals("child", markSentBy(child));
            assertEquals("parent", markSentBy(parent));
        }
    }

    // ---- item 4 ---------------------------------------------------------------------------

    @Test
    void a_registered_feature_is_configured_and_enabled() {
        try (Client client = ClientBuilder.newClient()) {
            MarkFeature feature = new MarkFeature(true);
            client.register(feature);
            assertEquals(1, feature.configured.get());
            assertTrue(client.getConfiguration().isEnabled(feature));
            assertTrue(client.getConfiguration().isEnabled(MarkFeature.class));
            assertEquals("from-feature", markSentBy(client.target(server.baseUrl())));
        }
    }

    @Test
    void a_feature_returning_false_is_not_enabled() {
        try (Client client = ClientBuilder.newClient()) {
            MarkFeature feature = new MarkFeature(false);
            client.register(feature);
            assertEquals(1, feature.configured.get());
            assertFalse(client.getConfiguration().isEnabled(feature));
            assertFalse(client.getConfiguration().isEnabled(MarkFeature.class));
        }
    }

    @Test
    void a_feature_registered_on_the_builder_is_configured_once() {
        MarkFeature feature = new MarkFeature(true);
        try (Client client = ClientBuilder.newBuilder().register(feature).build()) {
            assertEquals(1, feature.configured.get());
            assertTrue(client.getConfiguration().isEnabled(feature));
            assertEquals("from-feature", markSentBy(client.target(server.baseUrl())));
        }
    }

    @Test
    void a_feature_registered_on_a_target_stays_on_that_target() {
        try (Client client = ClientBuilder.newClient()) {
            MarkFeature feature = new MarkFeature(true);
            WebTarget target = client.target(server.baseUrl()).register(feature);
            assertTrue(target.getConfiguration().isEnabled(feature));
            assertFalse(client.getConfiguration().isEnabled(feature));
            assertEquals("from-feature", markSentBy(target));
            assertNull(markSentBy(client.target(server.baseUrl())));
        }
    }
}
