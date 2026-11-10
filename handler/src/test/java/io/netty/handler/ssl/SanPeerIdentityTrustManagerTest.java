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

import io.netty.util.internal.EmptyArrays;
import org.junit.jupiter.api.Test;

import javax.net.ssl.TrustManager;
import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class SanPeerIdentityTrustManagerTest {

    // Verifies the method exits early when there is no usable peer host
    @Test
    public void testResolvePeerIdentityReturnsNullForEmptyPeerHost() throws Exception {
        assertEquals(null, SanPeerIdentityTrustManager.resolvePeerIdentity(
            "", certificateWithSans(dnsSan("node1.example.com"))));
        assertEquals(null, SanPeerIdentityTrustManager.resolvePeerIdentity(
            null, certificateWithSans(dnsSan("node1.example.com"))));
    }

    // Verifies the method does not try to choose a peer identity when the certificate has no relevant SAN entries
    @Test
    public void testResolvePeerIdentityReturnsNullWhenCertificateHasNoDnsOrIpSans() throws Exception {
        assertEquals(null, SanPeerIdentityTrustManager.resolvePeerIdentity(
            "10.0.0.1", certificateWithSans(Arrays.asList(1, "ignored"))));
    }

    // Verifies that when the certificate only contains IP SANs, the method keeps using the original peer host
    @Test
    public void testResolvePeerIdentityKeepsOriginalPeerHostForIpOnlySans() throws Exception {
        assertEquals("10.0.0.1", SanPeerIdentityTrustManager.resolvePeerIdentity(
            "10.0.0.1", certificateWithSans(ipSan("10.0.0.1"))));
    }

    // Verifies that if the peer host is already a hostname and the certificate has DNS SANs, no rewrite is needed
    @Test
    public void testResolvePeerIdentityKeepsOriginalPeerHostForDnsSansWhenPeerIsHostname() throws Exception {
        assertEquals("node1.example.com", SanPeerIdentityTrustManager.resolvePeerIdentity(
            "node1.example.com", certificateWithSans(dnsSan("node1.example.com"))));
    }

    // Verifies the fallback path after attempting reverse lookup for an IP peer host with DNS SANs.
    @Test
    public void testResolvePeerIdentityFallsBackToOriginalPeerHostWhenReverseLookupDoesNotYieldHostname()
        throws Exception {
        assertEquals("192.0.2.10", SanPeerIdentityTrustManager.resolvePeerIdentity(
            "192.0.2.10", certificateWithSans(dnsSan("node1.example.com"))));
    }

    @Test
    public void testWrapIfNeededWrapsOnlyExtendedTrustManagersWhenEnabled() {
        TrustManager wrapped = SanPeerIdentityTrustManager.wrapIfNeeded(new EmptyExtendedX509TrustManager(), true);
        assertTrue(wrapped instanceof SanPeerIdentityTrustManager);

        TrustManager notWrappedWhenDisabled =
            SanPeerIdentityTrustManager.wrapIfNeeded(new EmptyExtendedX509TrustManager(), false);
        assertTrue(notWrappedWhenDisabled instanceof EmptyExtendedX509TrustManager);

        TrustManager plainTrustManager = new TrustManager() { };
        assertSame(plainTrustManager, SanPeerIdentityTrustManager.wrapIfNeeded(plainTrustManager, true));
    }

    private static X509Certificate certificateWithSans(List<?>... sans) {
        final Collection<List<?>> subjectAlternativeNames = sans.length == 0 ? Collections.<List<?>>emptyList()
            : Arrays.<List<?>>asList(sans);
        return new TestX509Certificate(subjectAlternativeNames);
    }

    private static List<?> dnsSan(String value) {
        return Arrays.asList(Integer.valueOf(2), value);
    }

    private static List<?> ipSan(String value) {
        return Arrays.asList(Integer.valueOf(7), value);
    }

    private static final class TestX509Certificate extends X509Certificate {
        private final Collection<List<?>> subjectAlternativeNames;

        TestX509Certificate(Collection<List<?>> subjectAlternativeNames) {
            this.subjectAlternativeNames = subjectAlternativeNames;
        }

        @Override
        public Collection<List<?>> getSubjectAlternativeNames() {
            return subjectAlternativeNames;
        }

        @Override
        public void checkValidity() {
            // NOOP
        }

        @Override
        public void checkValidity(Date date) {
            // NOOP
        }

        @Override
        public int getVersion() {
            return 0;
        }

        @Override
        public BigInteger getSerialNumber() {
            return null;
        }

        @Override
        public Principal getIssuerDN() {
            return null;
        }

        @Override
        public Principal getSubjectDN() {
            return null;
        }

        @Override
        public Date getNotBefore() {
            return null;
        }

        @Override
        public Date getNotAfter() {
            return null;
        }

        @Override
        public byte[] getTBSCertificate() {
            return EmptyArrays.EMPTY_BYTES;
        }

        @Override
        public byte[] getSignature() {
            return EmptyArrays.EMPTY_BYTES;
        }

        @Override
        public String getSigAlgName() {
            return null;
        }

        @Override
        public String getSigAlgOID() {
            return null;
        }

        @Override
        public byte[] getSigAlgParams() {
            return EmptyArrays.EMPTY_BYTES;
        }

        @Override
        public boolean[] getIssuerUniqueID() {
            return new boolean[0];
        }

        @Override
        public boolean[] getSubjectUniqueID() {
            return new boolean[0];
        }

        @Override
        public boolean[] getKeyUsage() {
            return new boolean[0];
        }

        @Override
        public int getBasicConstraints() {
            return 0;
        }

        @Override
        public byte[] getEncoded() {
            return EmptyArrays.EMPTY_BYTES;
        }

        @Override
        public void verify(PublicKey key) {
            // NOOP
        }

        @Override
        public void verify(PublicKey key, String sigProvider) {
            // NOOP
        }

        @Override
        public String toString() {
            return "TestX509Certificate";
        }

        @Override
        public PublicKey getPublicKey() {
            return null;
        }

        @Override
        public boolean hasUnsupportedCriticalExtension() {
            return false;
        }

        @Override
        public Set<String> getCriticalExtensionOIDs() {
            return null;
        }

        @Override
        public Set<String> getNonCriticalExtensionOIDs() {
            return null;
        }

        @Override
        public byte[] getExtensionValue(String oid) {
            return EmptyArrays.EMPTY_BYTES;
        }
    }
}
