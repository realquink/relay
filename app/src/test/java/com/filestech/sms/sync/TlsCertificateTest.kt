package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.security.KeyPairGenerator

/**
 * Slice 1 — the BouncyCastle fallback certificate: self-signed, CN =
 * hub id, 10-year validity, and verifiable with its own public key.
 * The Keystore path cannot be exercised on the JVM (no AndroidKeyStore
 * here); it is covered by the same shape — same CN, same validity —
 * and needs a device to prove.
 */
class TlsCertificateTest {

    @Test fun `certificate subject is the hub id`() {
        val cert = buildSelfSignedCertificate(ecKeyPair(), HUB_ID, NOW_MS)
        assertThat(cert.subjectX500Principal.name).isEqualTo("CN=$HUB_ID")
        assertThat(cert.issuerX500Principal.name).isEqualTo("CN=$HUB_ID")
    }

    @Test fun `certificate is valid for ten years from now`() {
        val cert = buildSelfSignedCertificate(ecKeyPair(), HUB_ID, NOW_MS)
        assertThat(cert.notBefore.time).isEqualTo(NOW_MS)
        assertThat(cert.notAfter.time)
            .isEqualTo(NOW_MS + TlsCertificateProvider.CERT_VALIDITY_MS)
        assertThat(TlsCertificateProvider.CERT_VALIDITY_MS)
            .isEqualTo(10L * 365 * 24 * 60 * 60 * 1000)
    }

    @Test fun `certificate verifies with its own public key`() {
        val keyPair = ecKeyPair()
        val cert = buildSelfSignedCertificate(keyPair, HUB_ID, NOW_MS)
        // Throws SignatureException / InvalidKeyException on failure.
        cert.verify(keyPair.public)
        assertThat(cert.publicKey.encoded).isEqualTo(keyPair.public.encoded)
    }

    @Test fun `two certificates for the same hub differ only in serial`() {
        val keyPair = ecKeyPair()
        val first = buildSelfSignedCertificate(keyPair, HUB_ID, NOW_MS)
        val second = buildSelfSignedCertificate(keyPair, HUB_ID, NOW_MS)
        // The serial is random per certificate, so a regenerated cert is
        // never bit-identical to the old one — the desktop's TOFU pin
        // would (correctly) notice.
        assertThat(first.serialNumber).isNotEqualTo(second.serialNumber)
        assertThat(first.subjectX500Principal).isEqualTo(second.subjectX500Principal)
    }

    @Test fun `a key authorized for SHA-256 only does not cover TLS digests`() {
        // The pre-fix keystore key: the handshake's signature scheme is
        // the client's pick, and clients offer SHA-384/SHA-512 schemes
        // first, so this key failed every handshake (INCOMPATIBLE_DIGEST).
        assertThat(
            TlsCertificateProvider.authorizesRequiredDigests(setOf("SHA-256")),
        ).isFalse()
    }

    @Test fun `a key authorized for all three SHA-2 digests covers TLS digests`() {
        assertThat(
            TlsCertificateProvider.authorizesRequiredDigests(
                setOf("SHA-256", "SHA-384", "SHA-512"),
            ),
        ).isTrue()
    }

    companion object {
        private const val HUB_ID = "123e4567-e89b-12d3-a456-426614174099"

        /** A fixed instant, so the validity assertions are exact. */
        private const val NOW_MS = 1_791_000_000_000L

        private fun ecKeyPair() = KeyPairGenerator.getInstance("EC").apply {
            initialize(256)
        }.generateKeyPair()
    }
}
