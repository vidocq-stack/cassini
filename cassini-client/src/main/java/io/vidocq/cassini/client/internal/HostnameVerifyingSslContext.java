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

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLContextSpi;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import java.nio.ByteBuffer;
import java.security.KeyManagementException;
import java.security.SecureRandom;
import java.util.List;
import java.util.function.BiFunction;

/**
 * An {@link SSLContext} whose client engines hand the endpoint's hostname check to a
 * {@link HostnameVerifier} ({@code ClientBuilder.hostnameVerifier}, which the JDK
 * {@link java.net.http.HttpClient} has no API for).
 *
 * <p>The {@code HttpClient} always asks its engines for the {@code HTTPS} endpoint identification
 * and applies it itself. The engines of this context drop that request, so the JDK check does not
 * run, and call the verifier as soon as the handshake has finished, before any application byte
 * is written or read: a verifier that answers {@code false} fails the connection with an
 * {@link SSLHandshakeException}, one that answers {@code true} accepts a host the certificate does
 * not name. The certificate chain is still validated by the delegate's trust managers.</p>
 *
 * <p>Only engines are covered (the {@code HttpClient} path); the socket factories of the delegate
 * are handed out unchanged.</p>
 */
final class HostnameVerifyingSslContext {

    private HostnameVerifyingSslContext() { }

    /** {@code delegate}, with its client engines verified by {@code verifier}. */
    static SSLContext wrap(SSLContext delegate, HostnameVerifier verifier) {
        return new SSLContext(new Spi(delegate, verifier), delegate.getProvider(), delegate.getProtocol()) { };
    }

    private static final class Spi extends SSLContextSpi {

        private final SSLContext delegate;
        private final HostnameVerifier verifier;

        Spi(SSLContext delegate, HostnameVerifier verifier) {
            this.delegate = delegate;
            this.verifier = verifier;
        }

        @Override
        protected void engineInit(KeyManager[] km, TrustManager[] tm, SecureRandom random) throws KeyManagementException {
            throw new KeyManagementException("The wrapped SSLContext is already initialised");
        }

        @Override protected SSLSocketFactory engineGetSocketFactory() { return delegate.getSocketFactory(); }
        @Override protected SSLServerSocketFactory engineGetServerSocketFactory() { return delegate.getServerSocketFactory(); }
        @Override protected SSLEngine engineCreateSSLEngine() { return new VerifyingEngine(delegate.createSSLEngine(), verifier); }
        @Override protected SSLEngine engineCreateSSLEngine(String host, int port) {
            return new VerifyingEngine(delegate.createSSLEngine(host, port), verifier);
        }
        @Override protected SSLSessionContext engineGetServerSessionContext() { return delegate.getServerSessionContext(); }
        @Override protected SSLSessionContext engineGetClientSessionContext() { return delegate.getClientSessionContext(); }
        @Override protected SSLParameters engineGetDefaultSSLParameters() { return delegate.getDefaultSSLParameters(); }
        @Override protected SSLParameters engineGetSupportedSSLParameters() { return delegate.getSupportedSSLParameters(); }
    }

    /** Delegates to a real engine and checks the hostname once its client handshake has finished. */
    private static final class VerifyingEngine extends SSLEngine {

        private final SSLEngine delegate;
        private final HostnameVerifier verifier;
        private volatile boolean verified;

        VerifyingEngine(SSLEngine delegate, HostnameVerifier verifier) {
            super(delegate.getPeerHost(), delegate.getPeerPort());
            this.delegate = delegate;
            this.verifier = verifier;
        }

        private SSLEngineResult verify(SSLEngineResult result) throws SSLException {
            if (!verified && delegate.getUseClientMode()
                    && result.getHandshakeStatus() == SSLEngineResult.HandshakeStatus.FINISHED) {
                String host = delegate.getPeerHost();
                SSLSession session = delegate.getSession();
                if (host == null || !verifier.verify(host, session)) {
                    delegate.closeOutbound();
                    throw new SSLHandshakeException("The hostname verifier rejected the host " + host);
                }
                verified = true;
            }
            return result;
        }

        @Override
        public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int length, ByteBuffer dst) throws SSLException {
            return verify(delegate.wrap(srcs, offset, length, dst));
        }

        @Override
        public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts, int offset, int length) throws SSLException {
            return verify(delegate.unwrap(src, dsts, offset, length));
        }

        /** The JDK's own endpoint identification is replaced by the verifier: it must not be requested. */
        @Override
        public void setSSLParameters(SSLParameters params) {
            if (params != null) params.setEndpointIdentificationAlgorithm(null);
            delegate.setSSLParameters(params);
        }

        @Override public SSLParameters getSSLParameters() { return delegate.getSSLParameters(); }
        @Override public String getPeerHost() { return delegate.getPeerHost(); }
        @Override public int getPeerPort() { return delegate.getPeerPort(); }
        @Override public Runnable getDelegatedTask() { return delegate.getDelegatedTask(); }
        @Override public void closeInbound() throws SSLException { delegate.closeInbound(); }
        @Override public boolean isInboundDone() { return delegate.isInboundDone(); }
        @Override public void closeOutbound() { delegate.closeOutbound(); }
        @Override public boolean isOutboundDone() { return delegate.isOutboundDone(); }
        @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }
        @Override public String[] getEnabledCipherSuites() { return delegate.getEnabledCipherSuites(); }
        @Override public void setEnabledCipherSuites(String[] suites) { delegate.setEnabledCipherSuites(suites); }
        @Override public String[] getSupportedProtocols() { return delegate.getSupportedProtocols(); }
        @Override public String[] getEnabledProtocols() { return delegate.getEnabledProtocols(); }
        @Override public void setEnabledProtocols(String[] protocols) { delegate.setEnabledProtocols(protocols); }
        @Override public SSLSession getSession() { return delegate.getSession(); }
        @Override public SSLSession getHandshakeSession() { return delegate.getHandshakeSession(); }
        @Override public void beginHandshake() throws SSLException { delegate.beginHandshake(); }
        @Override public SSLEngineResult.HandshakeStatus getHandshakeStatus() { return delegate.getHandshakeStatus(); }
        @Override public void setUseClientMode(boolean mode) { delegate.setUseClientMode(mode); }
        @Override public boolean getUseClientMode() { return delegate.getUseClientMode(); }
        @Override public void setNeedClientAuth(boolean need) { delegate.setNeedClientAuth(need); }
        @Override public boolean getNeedClientAuth() { return delegate.getNeedClientAuth(); }
        @Override public void setWantClientAuth(boolean want) { delegate.setWantClientAuth(want); }
        @Override public boolean getWantClientAuth() { return delegate.getWantClientAuth(); }
        @Override public void setEnableSessionCreation(boolean flag) { delegate.setEnableSessionCreation(flag); }
        @Override public boolean getEnableSessionCreation() { return delegate.getEnableSessionCreation(); }
        @Override public String getApplicationProtocol() { return delegate.getApplicationProtocol(); }
        @Override public String getHandshakeApplicationProtocol() { return delegate.getHandshakeApplicationProtocol(); }

        @Override
        public void setHandshakeApplicationProtocolSelector(BiFunction<SSLEngine, List<String>, String> selector) {
            delegate.setHandshakeApplicationProtocolSelector(selector == null ? null
                    : (engine, protocols) -> selector.apply(this, protocols));
        }

        @Override
        public BiFunction<SSLEngine, List<String>, String> getHandshakeApplicationProtocolSelector() {
            return delegate.getHandshakeApplicationProtocolSelector();
        }
    }
}
