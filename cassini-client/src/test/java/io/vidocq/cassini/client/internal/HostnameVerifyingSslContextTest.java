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
package io.vidocq.cassini.client.internal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLParameters;
import java.security.KeyManagementException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HostnameVerifyingSslContext}: apart from the hostname check (covered end to end in
 * {@code TlsConfigurationTest}), the wrapper must behave as the context and the engine it wraps,
 * since the {@code HttpClient} drives them through every one of these methods (ALPN, protocols,
 * cipher suites...). Each state set on the wrapper must reach, and be read back from, the delegate.
 */
class HostnameVerifyingSslContextTest {

    private static final String HOST = "example.test";
    private static final int PORT = 443;

    private SSLContext delegate;
    private SSLContext wrapped;
    private SSLEngine engine;

    @BeforeEach
    void wrap() throws Exception {
        delegate = SSLContext.getDefault();
        wrapped = HostnameVerifyingSslContext.wrap(delegate, (host, session) -> true);
        engine = wrapped.createSSLEngine(HOST, PORT);
        engine.setUseClientMode(true);
    }

    @Test
    void the_context_hands_out_the_delegate_s_factories_and_parameters() {
        assertEquals(delegate.getProtocol(), wrapped.getProtocol());
        assertSame(delegate.getProvider(), wrapped.getProvider());
        assertNotNull(wrapped.getSocketFactory());
        assertNotNull(wrapped.getServerSocketFactory());
        assertSame(delegate.getClientSessionContext(), wrapped.getClientSessionContext());
        assertSame(delegate.getServerSessionContext(), wrapped.getServerSessionContext());
        assertArrayEquals(delegate.getDefaultSSLParameters().getProtocols(),
                wrapped.getDefaultSSLParameters().getProtocols());
        assertArrayEquals(delegate.getSupportedSSLParameters().getProtocols(),
                wrapped.getSupportedSSLParameters().getProtocols());
    }

    @Test
    void the_context_cannot_be_initialised_again() {
        assertThrows(KeyManagementException.class, () -> wrapped.init(null, null, null));
    }

    @Test
    void an_engine_without_a_peer_has_no_host() {
        SSLEngine anonymous = wrapped.createSSLEngine();
        assertNull(anonymous.getPeerHost());
        assertEquals(-1, anonymous.getPeerPort());
    }

    @Test
    void the_engine_keeps_its_peer() {
        assertEquals(HOST, engine.getPeerHost());
        assertEquals(PORT, engine.getPeerPort());
    }

    @Test
    void cipher_suites_and_protocols_go_through_to_the_delegate() {
        String[] supportedSuites = engine.getSupportedCipherSuites();
        assertTrue(supportedSuites.length > 0);
        String[] oneSuite = {engine.getEnabledCipherSuites()[0]};
        engine.setEnabledCipherSuites(oneSuite);
        assertArrayEquals(oneSuite, engine.getEnabledCipherSuites());

        assertTrue(Arrays.asList(engine.getSupportedProtocols()).contains("TLSv1.2"));
        engine.setEnabledProtocols(new String[] {"TLSv1.2"});
        assertArrayEquals(new String[] {"TLSv1.2"}, engine.getEnabledProtocols());
    }

    @Test
    void the_mode_and_the_client_authentication_flags_go_through() {
        assertTrue(engine.getUseClientMode());
        engine.setUseClientMode(false);
        assertFalse(engine.getUseClientMode());

        engine.setNeedClientAuth(true);
        assertTrue(engine.getNeedClientAuth());
        engine.setWantClientAuth(true);
        assertTrue(engine.getWantClientAuth());
        engine.setEnableSessionCreation(false);
        assertFalse(engine.getEnableSessionCreation());
    }

    @Test
    void the_handshake_state_is_the_delegate_s() throws Exception {
        assertEquals(SSLEngineResult.HandshakeStatus.NOT_HANDSHAKING, engine.getHandshakeStatus());
        assertNotNull(engine.getSession());
        assertNull(engine.getHandshakeSession());
        assertNull(engine.getDelegatedTask());
        engine.beginHandshake();
        assertEquals(SSLEngineResult.HandshakeStatus.NEED_WRAP, engine.getHandshakeStatus());
        assertFalse(engine.isInboundDone());
        assertFalse(engine.isOutboundDone());
        engine.closeOutbound();
        assertThrows(javax.net.ssl.SSLException.class, engine::closeInbound);
        assertTrue(engine.isInboundDone());
    }

    @Test
    void an_engine_that_never_started_closes_its_inbound_side_quietly() throws Exception {
        engine.closeInbound();
        assertTrue(engine.isInboundDone());
    }

    @Test
    void the_application_protocol_selector_is_the_delegate_s_and_receives_the_wrapper() {
        assertNull(engine.getApplicationProtocol());
        assertNull(engine.getHandshakeApplicationProtocol());
        assertNull(engine.getHandshakeApplicationProtocolSelector());
        engine.setHandshakeApplicationProtocolSelector((e, protocols) -> protocols.get(0));
        assertNotNull(engine.getHandshakeApplicationProtocolSelector());
        engine.setHandshakeApplicationProtocolSelector(null);
        assertNull(engine.getHandshakeApplicationProtocolSelector());
    }

    @Test
    void the_jdk_endpoint_identification_is_dropped_from_the_parameters() {
        SSLParameters parameters = engine.getSSLParameters();
        parameters.setEndpointIdentificationAlgorithm("HTTPS");
        parameters.setApplicationProtocols(new String[] {"h2", "http/1.1"});
        engine.setSSLParameters(parameters);

        SSLParameters applied = engine.getSSLParameters();
        assertNull(applied.getEndpointIdentificationAlgorithm());
        assertArrayEquals(new String[] {"h2", "http/1.1"}, applied.getApplicationProtocols());
    }
}
