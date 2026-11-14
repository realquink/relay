package com.filestech.sms.sync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyProperties
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import timber.log.Timber
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import javax.security.auth.x500.X500Principal

/**
 * The TLS identity the LAN server presents: a self-signed X.509
 * certificate (CN = hub id, 10-year validity) and its private key,
 * per SYNC_PROTOCOL.md. The client pins the certificate's SHA-256
 * fingerprint on first connect (TOFU) — the fingerprint only stays
 * stable if the keypair survives restarts, so both paths below persist
 * it.
 *
 * ## Keystore first, BouncyCastle fallback
 *
 * The preferred path generates an EC P-256 keypair in the
 * AndroidKeyStore, whose entry carries the self-signed certificate —
 * the private key never leaves the secure hardware / keystore daemon.
 * The key authorizes DIGEST_NONE plus all three SHA-2 digests:
 * the TLS stack hashes the handshake itself and signs the
 * pre-computed digest (NONEwithECDSA), so a key without DIGEST_NONE
 * fails every handshake with INCOMPATIBLE_DIGEST — a key with all
 * three SHA-2 digests but no NONE still fails (seen on device
 * 2026-11-14). An older entry missing any required digest is
 * detected and regenerated on start.
 * If anything about that path fails (a device without a working
 * keystore, a broken entry), the fallback generates the same shape of
 * keypair with plain JCA, builds the certificate with BouncyCastle's
 * X.509 builder, and stores the PKCS#8 key + DER certificate in
 * app-private files (readable only by this app). There is no third
 * path: no unencrypted fallback exists, and the server does not start
 * without an identity.
 *
 * The TLS certificate is transport-only — device authentication is the
 * separate Ed25519 challenge/response ([Ed25519]).
 */
@Singleton
class TlsCertificateProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /**
     * The server's TLS identity, creating it on first run. Suspends on
     * IO: key generation can take a noticeable moment on first run.
     * Throws only if *both* paths fail — the caller treats that as
     * "the server cannot start".
     */
    suspend fun ensureIdentity(hubId: String): TlsIdentity = withContext(Dispatchers.IO) {
        keystoreIdentity(hubId) ?: fileIdentity(hubId)
    }

    /**
     * The Keystore path. Returns null on any failure so the caller
     * falls through to the file path — a half-broken keystore must not
     * take the server down when a working fallback exists.
     */
    private fun keystoreIdentity(hubId: String): TlsIdentity? = runCatching {
        var keyStore = loadKeystore()
        if (!isUsableKeystoreEntry(keyStore, hubId)) {
            runCatching { keyStore.deleteEntry(KEY_ALIAS) }
            generateKeystoreKey(hubId)
            keyStore = loadKeystore()
            check(isUsableKeystoreEntry(keyStore, hubId)) {
                "Keystore entry unusable after generation"
            }
        }
        // AndroidKeyStore ignores passwords; Ktor still asks for them.
        Timber.i("TlsCertificateProvider: using AndroidKeyStore identity")
        TlsIdentity(keyStore, KEY_ALIAS, EMPTY_PASSWORD, EMPTY_PASSWORD)
    }.onFailure { t ->
        Timber.w(t, "TlsCertificateProvider: Keystore path failed, using file fallback")
    }.getOrNull()

    private fun loadKeystore(): KeyStore =
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun isUsableKeystoreEntry(keyStore: KeyStore, hubId: String): Boolean {
        if (!runCatching { keyStore.containsAlias(KEY_ALIAS) }.getOrDefault(false)) return false
        val cert = runCatching { keyStore.getCertificate(KEY_ALIAS) }.getOrNull()
            as? X509Certificate
        if (cert == null || !cert.subjectX500Principal.name.contains("CN=$hubId")) return false
        if (!hasRequiredDigests(keyStore)) {
            Timber.w(
                "TlsCertificateProvider: keystore key lacks a required digest, regenerating",
            )
            return false
        }
        return true
    }

    /**
     * Whether the stored key authorizes every digest a TLS handshake
     * might need. Keys generated before the digest fixes (SHA-256
     * only, or the three SHA-2 digests without NONE) fail here and
     * are regenerated by the caller.
     */
    private fun hasRequiredDigests(keyStore: KeyStore): Boolean {
        val privateKey = runCatching { keyStore.getKey(KEY_ALIAS, null) }.getOrNull()
            ?: return false
        val keyInfo = runCatching {
            KeyFactory.getInstance(privateKey.algorithm, "AndroidKeyStore")
                .getKeySpec(privateKey, KeyInfo::class.java)
        }.getOrNull() ?: return false
        val digests = keyInfo.digests?.toSet() ?: return false
        return authorizesRequiredDigests(digests)
    }

    private fun generateKeystoreKey(hubId: String) {
        val now = System.currentTimeMillis()
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
            .setDigests(
                KeyProperties.DIGEST_NONE,
                KeyProperties.DIGEST_SHA256,
                KeyProperties.DIGEST_SHA384,
                KeyProperties.DIGEST_SHA512,
            )
            .setCertificateSubject(X500Principal("CN=$hubId"))
            .setCertificateSerialNumber(BigInteger(64, SecureRandom()))
            .setCertificateNotBefore(Date(now))
            .setCertificateNotAfter(Date(now + CERT_VALIDITY_MS))
            .setKeyValidityStart(Date(now))
            .setKeyValidityEnd(Date(now + CERT_VALIDITY_MS))
            .build()
        KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
            initialize(spec)
            generateKeyPair()
        }
    }

    /**
     * The file fallback: an EC keypair + BouncyCastle self-signed
     * certificate, persisted as app-private files and loaded into an
     * in-memory PKCS#12 store (whose password is random per process —
     * the store itself never touches disk).
     */
    private fun fileIdentity(hubId: String): TlsIdentity {
        Timber.i("TlsCertificateProvider: using file identity")
        val keyFile = File(context.filesDir, KEY_FILE_NAME)
        val certFile = File(context.filesDir, CERT_FILE_NAME)
        val (privateKey, certificate) = loadFileIdentity(hubId, keyFile, certFile)
            ?: generateFileIdentity(hubId, keyFile, certFile)
        val password = randomPassword()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry(KEY_ALIAS, privateKey, password, arrayOf(certificate))
        }
        return TlsIdentity(keyStore, KEY_ALIAS, password, password)
    }

    private fun loadFileIdentity(
        hubId: String,
        keyFile: File,
        certFile: File,
    ): Pair<PrivateKey, X509Certificate>? {
        return runCatching {
            val keyBytes = keyFile.readBytes()
            val certBytes = certFile.readBytes()
            val privateKey = KeyFactory.getInstance("EC")
                .generatePrivate(PKCS8EncodedKeySpec(keyBytes))
            val certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(certBytes.inputStream()) as X509Certificate
            check(certificate.subjectX500Principal.name.contains("CN=$hubId")) {
                "Stored certificate is for another hub id"
            }
            privateKey to certificate
        }.onFailure { t ->
            Timber.w(t, "TlsCertificateProvider: stored file identity unreadable, regenerating")
        }.getOrNull()
    }

    private fun generateFileIdentity(
        hubId: String,
        keyFile: File,
        certFile: File,
    ): Pair<PrivateKey, X509Certificate> {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val certificate = buildSelfSignedCertificate(keyPair, hubId)
        keyFile.writeBytes(keyPair.private.encoded)
        certFile.writeBytes(certificate.encoded)
        return keyPair.private to certificate
    }

    private fun randomPassword(): CharArray {
        val bytes = ByteArray(PASSWORD_BYTES).also { SecureRandom().nextBytes(it) }
        return Base64.getEncoder().encodeToString(bytes).toCharArray()
    }

    companion object {
        private const val KEY_ALIAS = "relay-sync-tls"
        private const val KEY_FILE_NAME = "sync-tls-key.pk8"
        private const val CERT_FILE_NAME = "sync-tls-cert.der"

        /** 10-year certificate validity, per the spec. */
        internal const val CERT_VALIDITY_MS = 10L * 365 * 24 * 60 * 60 * 1000

        /**
         * Digests the TLS key must authorize. DIGEST_NONE is the
         * critical one: the TLS stack hashes the handshake itself
         * and signs the pre-computed digest (NONEwithECDSA), so a
         * key without NONE fails every handshake with
         * INCOMPATIBLE_DIGEST — even one authorizing all three SHA-2
         * digests (seen on device 2026-11-14). The SHA-2 digests
         * cover any path that signs with a named digest.
         */
        internal val REQUIRED_DIGESTS: Set<String> = setOf(
            KeyProperties.DIGEST_NONE,
            KeyProperties.DIGEST_SHA256,
            KeyProperties.DIGEST_SHA384,
            KeyProperties.DIGEST_SHA512,
        )

        /** Whether [digests] cover every digest in [REQUIRED_DIGESTS]. */
        internal fun authorizesRequiredDigests(digests: Set<String>): Boolean =
            REQUIRED_DIGESTS.all { it in digests }

        private const val PASSWORD_BYTES = 16

        private val EMPTY_PASSWORD = charArrayOf()
    }
}

/**
 * What Ktor's `sslConnector` needs: a [KeyStore] holding the private
 * key + certificate under [keyAlias], with the passwords to unlock
 * them. For the Keystore path both passwords are empty (AndroidKeyStore
 * ignores them); for the file path they are the random per-process
 * password of the in-memory PKCS#12 store.
 */
data class TlsIdentity(
    val keyStore: KeyStore,
    val keyAlias: String,
    val keyPassword: CharArray,
    val storePassword: CharArray,
)

/**
 * Builds a self-signed X.509 certificate for [keyPair] with CN =
 * [hubId] and 10-year validity, using BouncyCastle's X.509 builder.
 * Top-level and `internal` so the JVM unit tests can exercise it
 * without a Context or a keystore.
 */
internal fun buildSelfSignedCertificate(
    keyPair: KeyPair,
    hubId: String,
    nowMs: Long = System.currentTimeMillis(),
): X509Certificate {
    val subject = X500Name("CN=$hubId")
    val builder = X509v3CertificateBuilder(
        subject,
        BigInteger(64, SecureRandom()),
        Date(nowMs),
        Date(nowMs + TlsCertificateProvider.CERT_VALIDITY_MS),
        subject,
        SubjectPublicKeyInfo.getInstance(keyPair.public.encoded),
    )
    val signer = JcaContentSignerBuilder("SHA256withECDSA").build(keyPair.private)
    return JcaX509CertificateConverter().getCertificate(builder.build(signer))
}
