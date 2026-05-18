/*
 * Copyright 2026 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.ssl;

import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;

final class SanPeerIdentityTrustManager extends X509ExtendedTrustManager {
    private static final InternalLogger logger =
            InternalLoggerFactory.getInstance(SanPeerIdentityTrustManager.class);
    private static final int DNS_SAN_TYPE = 2;
    private static final int IP_SAN_TYPE = 7;

    private final X509ExtendedTrustManager delegate;

    SanPeerIdentityTrustManager(X509ExtendedTrustManager delegate) {
        this.delegate = delegate;
    }

    static TrustManager wrapIfNeeded(TrustManager trustManager, boolean sanPeerIdentityLookup) {
        if (sanPeerIdentityLookup && trustManager instanceof X509ExtendedTrustManager) {
            return new SanPeerIdentityTrustManager((X509ExtendedTrustManager) trustManager);
        }
        return trustManager;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        if (engine == null || chain == null || chain.length == 0 || chain[0] == null) {
            logger.debug("SAN peer identity lookup skipped: engine={}, chainPresent={}, leafPresent={}",
                    engine != null, chain != null, chain != null && chain.length > 0 && chain[0] != null);
            delegate.checkServerTrusted(chain, authType, engine);
            return;
        }

        String peerIdentity = resolvePeerIdentity(engine.getPeerHost(), chain[0]);
        if (peerIdentity == null) {
            logger.debug("SAN peer identity override not applied for peerHost={}", engine.getPeerHost());
            delegate.checkServerTrusted(chain, authType, engine);
            return;
        }

        logger.debug("Overriding peerHost from {} to {} for SAN peer identity verification",
                engine.getPeerHost(), peerIdentity);
        delegate.checkServerTrusted(chain, authType, new DelegatingPeerHostEngine(engine, peerIdentity));
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        delegate.checkServerTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    static String resolvePeerIdentity(String peerHost, X509Certificate leafCertificate) throws CertificateException {
        logger.debug("Resolving SAN peer identity for peerHost={}", peerHost);
        if (peerHost == null || peerHost.isEmpty()) {
            logger.debug("SAN peer identity lookup aborted because peerHost is empty");
            return null;
        }

        SanTypes sanTypes = readSanTypes(leafCertificate);
        logger.debug("SAN types for peerHost={}: hasDnsSans={}, hasIpSans={}",
                peerHost, sanTypes.hasDnsSans, sanTypes.hasIpSans);
        if (!sanTypes.hasDnsSans && !sanTypes.hasIpSans) {
            logger.debug("SAN peer identity lookup aborted for peerHost={} because certificate has no DNS/IP SANs",
                    peerHost);
            return null;
        }

        if (sanTypes.hasIpSans && !sanTypes.hasDnsSans) {
            logger.debug("Using original peerHost={} because certificate only has IP SANs", peerHost);
            return peerHost;
        }

        if (sanTypes.hasDnsSans && !isIpAddress(peerHost)) {
            logger.debug("Using original peerHost={} because it is already a hostname and certificate has DNS SANs",
                    peerHost);
            return peerHost;
        }

        String canonicalHost = reverseLookup(peerHost);
        logger.debug("Reverse lookup for peerHost={} returned canonicalHost={}", peerHost, canonicalHost);
        if (canonicalHost != null && !isIpAddress(canonicalHost)) {
            logger.debug("Using reverse-looked-up canonicalHost={} for peerHost={}", canonicalHost, peerHost);
            return canonicalHost;
        }

        return peerHost;
    }

    private static SanTypes readSanTypes(X509Certificate certificate) throws CertificateException {
        boolean hasDnsSans = false;
        boolean hasIpSans = false;
        try {
            Collection<List<?>> subjectAlternativeNames = certificate.getSubjectAlternativeNames();
            if (subjectAlternativeNames == null) {
                return new SanTypes(false, false);
            }

            for (List<?> entry : subjectAlternativeNames) {
                if (entry == null || entry.size() < 2 || !(entry.get(0) instanceof Integer)) {
                    continue;
                }

                int type = ((Integer) entry.get(0)).intValue();
                if (type == DNS_SAN_TYPE) {
                    hasDnsSans = true;
                } else if (type == IP_SAN_TYPE) {
                    hasIpSans = true;
                }
            }
            return new SanTypes(hasDnsSans, hasIpSans);
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            throw new CertificateException("Failed to inspect certificate SAN entries", e);
        }
    }

    private static boolean isIpAddress(String value) {
        return value.indexOf(':') >= 0 || value.matches("\\d+\\.\\d+\\.\\d+\\.\\d+");
    }

    private static String reverseLookup(String peerHost) {
        try {
            return InetAddress.getByName(peerHost).getCanonicalHostName();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    private static final class SanTypes {
        final boolean hasDnsSans;
        final boolean hasIpSans;

        SanTypes(boolean hasDnsSans, boolean hasIpSans) {
            this.hasDnsSans = hasDnsSans;
            this.hasIpSans = hasIpSans;
        }
    }

    private static final class DelegatingPeerHostEngine extends JdkSslEngine {
        private final String peerHost;

        DelegatingPeerHostEngine(SSLEngine engine, String peerHost) {
            super(engine);
            this.peerHost = peerHost;
        }

        @Override
        public String getPeerHost() {
            return peerHost;
        }

        @Override
        public SSLSession getHandshakeSession() {
            final SSLSession session = super.getHandshakeSession();
            if (session == null) {
                return null;
            }
            return new DelegatingPeerHostSession(session, peerHost);
        }

        @Override
        public SSLSession getSession() {
            final SSLSession session = super.getSession();
            if (session == null) {
                return null;
            }
            return new DelegatingPeerHostSession(session, peerHost);
        }
    }

    private static final class DelegatingPeerHostSession implements SSLSession {
        private final SSLSession delegate;
        private final String peerHost;

        DelegatingPeerHostSession(SSLSession delegate, String peerHost) {
            this.delegate = delegate;
            this.peerHost = peerHost;
        }

        @Override
        public byte[] getId() {
            return delegate.getId();
        }

        @Override
        public javax.net.ssl.SSLSessionContext getSessionContext() {
            return delegate.getSessionContext();
        }

        @Override
        public long getCreationTime() {
            return delegate.getCreationTime();
        }

        @Override
        public long getLastAccessedTime() {
            return delegate.getLastAccessedTime();
        }

        @Override
        public void invalidate() {
            delegate.invalidate();
        }

        @Override
        public boolean isValid() {
            return delegate.isValid();
        }

        @Override
        public void putValue(String name, Object value) {
            delegate.putValue(name, value);
        }

        @Override
        public Object getValue(String name) {
            return delegate.getValue(name);
        }

        @Override
        public void removeValue(String name) {
            delegate.removeValue(name);
        }

        @Override
        public String[] getValueNames() {
            return delegate.getValueNames();
        }

        @Override
        public java.security.cert.Certificate[] getPeerCertificates()
                throws javax.net.ssl.SSLPeerUnverifiedException {
            return delegate.getPeerCertificates();
        }

        @Override
        public java.security.cert.Certificate[] getLocalCertificates() {
            return delegate.getLocalCertificates();
        }

        @Override
        public javax.security.cert.X509Certificate[] getPeerCertificateChain()
                throws javax.net.ssl.SSLPeerUnverifiedException {
            return delegate.getPeerCertificateChain();
        }

        @Override
        public java.security.Principal getPeerPrincipal()
                throws javax.net.ssl.SSLPeerUnverifiedException {
            return delegate.getPeerPrincipal();
        }

        @Override
        public java.security.Principal getLocalPrincipal() {
            return delegate.getLocalPrincipal();
        }

        @Override
        public String getCipherSuite() {
            return delegate.getCipherSuite();
        }

        @Override
        public String getProtocol() {
            return delegate.getProtocol();
        }

        @Override
        public String getPeerHost() {
            return peerHost;
        }

        @Override
        public int getPeerPort() {
            return delegate.getPeerPort();
        }

        @Override
        public int getPacketBufferSize() {
            return delegate.getPacketBufferSize();
        }

        @Override
        public int getApplicationBufferSize() {
            return delegate.getApplicationBufferSize();
        }
    }
}

