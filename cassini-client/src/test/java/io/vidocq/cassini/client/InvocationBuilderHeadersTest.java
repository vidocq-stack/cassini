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
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.CacheControl;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Request headers set through {@link Invocation.Builder}, as the server receives them. */
class InvocationBuilderHeadersTest {

    private static final String X_CUSTOM = "X-Custom";
    private static final String TEXT_PLAIN = "text/plain";

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

    private static String sent(Invocation.Builder builder, String header) {
        assertEquals(200, builder.get().getStatus());
        return server.last().header(header);
    }

    private static Invocation.Builder request() {
        return client.target(server.baseUrl()).path("/h").request();
    }

    @Test
    void request_without_types_accepts_anything() {
        assertEquals("*/*", sent(request(), HttpHeaders.ACCEPT));
    }

    @Test
    void request_with_types_sends_them_in_one_accept_header() {
        var target = client.target(server.baseUrl()).path("/h");
        assertEquals("text/plain, application/json", sent(target.request(TEXT_PLAIN, "application/json"), HttpHeaders.ACCEPT));
        assertEquals("application/xml", sent(target.request(MediaType.APPLICATION_XML_TYPE), HttpHeaders.ACCEPT));
    }

    @Test
    void request_with_an_empty_type_list_sends_no_accept_header() {
        var target = client.target(server.baseUrl()).path("/h");
        assertNull(sent(target.request(new String[0]), HttpHeaders.ACCEPT));
    }

    @Test
    void accept_replaces_the_requested_types() {
        assertEquals(TEXT_PLAIN, sent(request().accept(TEXT_PLAIN), HttpHeaders.ACCEPT));
        assertEquals("application/json", sent(request().accept(MediaType.APPLICATION_JSON_TYPE), HttpHeaders.ACCEPT));
    }

    @Test
    void accept_language_and_encoding_are_sent() {
        assertEquals("fr-FR, en-US", sent(request().acceptLanguage(Locale.FRANCE, Locale.US), HttpHeaders.ACCEPT_LANGUAGE));
        assertEquals("de", sent(request().acceptLanguage("de"), HttpHeaders.ACCEPT_LANGUAGE));
        assertEquals("gzip, br", sent(request().acceptEncoding("gzip", "br"), HttpHeaders.ACCEPT_ENCODING));
    }

    @Test
    void cookies_are_sent() {
        assertTrue(sent(request().cookie(new Cookie.Builder("a").value("1").build()), HttpHeaders.COOKIE).contains("a=1"));
        assertEquals("b=2", sent(request().cookie("b", "2"), HttpHeaders.COOKIE));
    }

    @Test
    void cache_control_is_sent() {
        CacheControl cc = new CacheControl();
        cc.setNoCache(true);
        assertTrue(sent(request().cacheControl(cc), HttpHeaders.CACHE_CONTROL).contains("no-cache"));
    }

    @Test
    void header_values_accumulate_and_a_null_value_removes_the_header() {
        assertEquals("1, 2", sent(request().header(X_CUSTOM, "1").header(X_CUSTOM, 2), X_CUSTOM));
        assertNull(sent(request().header(X_CUSTOM, "1").header(X_CUSTOM, null), X_CUSTOM));
    }

    @Test
    void headers_replaces_every_header_and_null_clears_them() {
        MultivaluedMap<String, Object> replacement = new MultivaluedHashMap<>();
        replacement.putSingle(X_CUSTOM, "only");
        Invocation.Builder replaced = request().header("X-Other", "x").headers(replacement);
        assertEquals("only", sent(replaced, X_CUSTOM));
        assertNull(server.last().header("X-Other"));
        assertNull(server.last().header(HttpHeaders.ACCEPT));

        assertNull(sent(request().header(X_CUSTOM, "x").headers(null), X_CUSTOM));
    }

    @Test
    void null_header_values_are_skipped() {
        MultivaluedMap<String, Object> headers = new MultivaluedHashMap<>();
        headers.put(X_CUSTOM, Arrays.asList("v", null));
        headers.put("X-Empty", Collections.singletonList(null));
        assertEquals("v", sent(request().headers(headers), X_CUSTOM));
        assertNull(server.last().header("X-Empty"));
    }

    @Test
    void restricted_headers_are_left_to_the_transport() {
        Invocation.Builder builder = request()
                .header("Host", "elsewhere.invalid")
                .header("Connection", "close")
                .header("Content-Length", "999")
                .header("Expect", "100-continue")
                .header("Upgrade", "h2c");
        String host = sent(builder, "Host");
        assertTrue(server.baseUrl().endsWith(host), host);
    }
}
