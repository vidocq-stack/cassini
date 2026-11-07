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
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.Cookie;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * CASSINI-024 item 7, request side: the {@link ClientRequestContext} reads its date, language and
 * cookies from the headers, compares header names without regard to case, and gives filters the
 * entity output stream (Jakarta REST 4.0 {@code ClientRequestContext}).
 */
class RequestContextTest {

    private static final String HTTP_DATE = "Tue, 15 Nov 1994 08:12:31 GMT";
    private static final long HTTP_DATE_MILLIS = 784887151000L;

    private static RecordingServer server;

    @BeforeAll
    static void start() throws Exception {
        server = RecordingServer.echo();
    }

    @AfterAll
    static void stop() {
        server.close();
    }

    /** Runs {@code build} on a request whose only filter is {@code filter}, and sends it. */
    private static void send(ClientRequestFilter filter, Consumer<Invocation.Builder> build, Entity<?> entity) {
        try (Client client = ClientBuilder.newClient().register(filter)) {
            Invocation.Builder builder = client.target(server.baseUrl()).request();
            build.accept(builder);
            assertEquals(200, (entity == null ? builder.get() : builder.post(entity)).getStatus());
        }
    }

    // ---- header names are case-insensitive ---------------------------------------------------

    @Test
    void header_names_compare_without_regard_to_case() {
        AtomicReference<String> viaString = new AtomicReference<>();
        AtomicReference<Object> viaMap = new AtomicReference<>();
        AtomicReference<String> viaStringHeaders = new AtomicReference<>();
        send(ctx -> {
            viaString.set(ctx.getHeaderString("X-LOWER"));
            viaMap.set(ctx.getHeaders().getFirst("x-lower"));
            viaStringHeaders.set(ctx.getStringHeaders().getFirst("X-Lower"));
        }, b -> b.header("x-lower", "v"), null);
        assertEquals("v", viaString.get());
        assertEquals("v", viaMap.get());
        assertEquals("v", viaStringHeaders.get());
    }

    @Test
    void a_filter_adding_a_header_in_another_case_extends_the_same_header() {
        send(ctx -> ctx.getHeaders().add("X-LIST", "2"), b -> b.header("x-list", "1"), null);
        assertEquals("1, 2", server.last().header("X-List"));
    }

    @Test
    void a_filter_replacing_a_header_in_another_case_replaces_it() {
        send(ctx -> ctx.getHeaders().putSingle("ACCEPT", "text/plain"), b -> b.header("accept", "application/json"), null);
        assertEquals("text/plain", server.last().header("Accept"));
    }

    // ---- date, language, cookies --------------------------------------------------------------

    @Test
    void getDate_reads_the_date_header() {
        AtomicReference<Date> date = new AtomicReference<>();
        send(ctx -> date.set(ctx.getDate()), b -> b.header("Date", HTTP_DATE), null);
        assertEquals(new Date(HTTP_DATE_MILLIS), date.get());
    }

    @Test
    void getDate_accepts_a_date_object() {
        AtomicReference<Date> date = new AtomicReference<>();
        Date sent = new Date(HTTP_DATE_MILLIS);
        send(ctx -> date.set(ctx.getDate()), b -> b.header("Date", sent), null);
        assertEquals(sent, date.get());
    }

    @Test
    void getDate_is_null_without_the_header() {
        AtomicReference<Boolean> isNull = new AtomicReference<>();
        send(ctx -> isNull.set(ctx.getDate() == null), b -> { }, null);
        assertEquals(true, isNull.get());
    }

    @Test
    void getLanguage_reads_the_content_language_header() {
        AtomicReference<Locale> language = new AtomicReference<>();
        send(ctx -> language.set(ctx.getLanguage()), b -> b.header("Content-Language", "fr-CA"), null);
        assertEquals(Locale.CANADA_FRENCH, language.get());
    }

    @Test
    void getCookies_reads_the_cookie_header() {
        AtomicReference<Map<String, Cookie>> cookies = new AtomicReference<>();
        send(ctx -> cookies.set(ctx.getCookies()),
                b -> b.cookie("a", "1").cookie(new Cookie.Builder("b").value("2").build()).header("Cookie", "c=3; d=4"), null);
        Map<String, Cookie> seen = cookies.get();
        assertNotNull(seen);
        assertEquals(4, seen.size());
        assertEquals("1", seen.get("a").getValue());
        assertEquals("2", seen.get("b").getValue());
        assertEquals("3", seen.get("c").getValue());
        assertEquals("4", seen.get("d").getValue());
    }

    @Test
    void getCookies_returns_a_cookie_object_added_by_a_filter() {
        AtomicReference<Map<String, Cookie>> cookies = new AtomicReference<>();
        send(ctx -> {
            ctx.getHeaders().add("Cookie", new Cookie.Builder("z").value("9").build());
            cookies.set(ctx.getCookies());
        }, b -> { }, null);
        assertEquals("9", cookies.get().get("z").getValue());
    }

    @Test
    void getCookies_is_empty_without_the_header() {
        AtomicReference<Map<String, Cookie>> cookies = new AtomicReference<>();
        send(ctx -> cookies.set(ctx.getCookies()), b -> { }, null);
        assertEquals(Map.of(), cookies.get());
    }

    // ---- entity stream -------------------------------------------------------------------------

    /** Upper-cases every byte written to the stream it wraps. */
    private static final class UpperCase extends FilterOutputStream {
        UpperCase(OutputStream out) { super(out); }
        @Override public void write(int b) throws IOException { out.write(Character.toUpperCase(b)); }
        @Override public void write(byte[] b, int off, int len) throws IOException {
            for (int i = 0; i < len; i++) write(b[off + i]);
        }
    }

    @Test
    void a_filter_can_wrap_the_entity_stream() {
        send(ctx -> ctx.setEntityStream(new UpperCase(ctx.getEntityStream())), b -> { }, Entity.text("hello"));
        assertEquals("HELLO", server.last().body());
    }

    @Test
    void a_wrapped_entity_stream_also_sees_a_streamed_entity() {
        send(ctx -> ctx.setEntityStream(new UpperCase(ctx.getEntityStream())), b -> { },
                Entity.entity(new ByteArrayInputStream("hello".getBytes(StandardCharsets.UTF_8)), "text/plain"));
        assertEquals("HELLO", server.last().body());
    }

    @Test
    void reading_the_entity_stream_without_replacing_it_changes_nothing() {
        AtomicReference<OutputStream> seen = new AtomicReference<>();
        send(ctx -> seen.set(ctx.getEntityStream()), b -> { }, Entity.text("hello"));
        assertNotNull(seen.get());
        assertEquals("hello", server.last().body());
    }
}
