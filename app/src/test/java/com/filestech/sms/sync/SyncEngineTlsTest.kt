package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.netty.handler.ssl.util.SelfSignedCertificate
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLEngineResult
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * Regression test for the 2026-11-13 crash: the sync server ran on
 * Ktor's CIO engine, which has no HTTPS support — it throws
 * UnsupportedOperationException on any `sslConnector`, in the engine's
 * own start job, so the throw escapes every `runCatching` around
 * `start()` and kills the app. This test boots the exact wiring
 * [SyncServer] uses (Netty engine + TLS-only connector + WebSockets)
 * and probes the port: with an engine that cannot serve TLS the
 * connect never succeeds and the test fails, instead of the app
 * crashing on the phone.
 *
 * Pure JVM (no Android APIs): the TLS identity is Netty's own
 * [SelfSignedCertificate], and the port is a free ephemeral one, so
 * the test binds nothing stable and leaves nothing behind.
 */
class SyncEngineTlsTest {

    @Test fun `the sync engine serves TLS on its port`() {
        val port = freePort()
        val identity = testIdentity()
        val server = embeddedServer(
            Netty,
            applicationEnvironment { },
            configure = {
                SyncServer.configureEngine(
                    this,
                    TlsIdentity(
                        identity.keyStore,
                        identity.alias,
                        identity.password,
                        identity.password,
                    ),
                    "127.0.0.1",
                    port,
                )
            },
        ) {
            install(WebSockets)
            routing {
                webSocket("/sync") { }
            }
        }
        try {
            server.start(wait = false)
            assertThat(waitForListening(port)).isTrue()
        } finally {
            runCatching { server.stop(500L, 2_000L) }
        }
    }

    /**
     * Regression test for the 2026-11-14 link failure: with HTTP/2 on
     * (Ktor's default), the server negotiates ALPN — and Netty's ALPN
     * engine kills the handshake at the finish line when the client
     * offers no ALPN extension, which the desktop client doesn't
     * (AssertionError in JdkAlpnSslEngine). The sync protocol is one
     * WebSocket over HTTP/1.1, so H2 stays off.
     */
    @Test fun `the sync engine disables HTTP2`() {
        val identity = testIdentity()
        val config = NettyApplicationEngine.Configuration()
        SyncServer.configureEngine(
            config,
            TlsIdentity(
                identity.keyStore,
                identity.alias,
                identity.password,
                identity.password,
            ),
        )
        assertThat(config.enableHttp2).isFalse()
    }

    /**
     * Regression test for the 2026-11-14 link failure: the phone's server
     * accepted TCP connections but closed every TLS handshake without
     * sending a byte. The TCP probe above cannot catch that — it only
     * proves the port is listening. This test builds the exact identity
     * [TlsCertificateProvider]'s file path uses (software EC key +
     * [buildSelfSignedCertificate]) and the exact server SSLContext
     * Netty builds from it (in-memory default-type KeyStore +
     * KeyManagerFactory + SSLContext.init), then drives a real handshake
     * between two in-memory engines. No sockets: the point is the SSL
     * layer, not the network.
     */
    @Test fun `the file identity completes a real TLS handshake`() {
        val keyPair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        val certificate = buildSelfSignedCertificate(keyPair, "test-hub-id")
        val password = Base64.getEncoder().encodeToString(
            ByteArray(16).also { SecureRandom().nextBytes(it) },
        ).toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, password)
            setKeyEntry("relay-sync-tls", keyPair.private, password, arrayOf(certificate))
        }
        // Netty's SslContext.buildKeyStore + KeyManagerFactory path.
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("key", keyPair.private, password, arrayOf(certificate))
        }
        assertThat(keyStore.getKey("relay-sync-tls", password)).isNotNull()
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(store, password)
        }
        val serverCtx = SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, null, SecureRandom())
        }
        val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val clientCtx = SSLContext.getInstance("TLS").apply {
            init(null, trustAll, SecureRandom())
        }
        val serverEngine = serverCtx.createSSLEngine().apply {
            useClientMode = false
            enabledProtocols = SyncProtocol.TLS_PROTOCOLS.toTypedArray()
        }
        val clientEngine = clientCtx.createSSLEngine("127.0.0.1", 47831).apply {
            useClientMode = true
        }
        pumpHandshake(clientEngine, serverEngine)
        assertThat(clientEngine.session.protocol).isNotEmpty()
        assertThat(clientEngine.session.cipherSuite).isNotEmpty()
        assertThat(serverEngine.session.protocol).isEqualTo(clientEngine.session.protocol)
    }

    /** Drives two SSLEngines to a completed handshake (or throws). */
    private fun pumpHandshake(client: SSLEngine, server: SSLEngine) {
        val c2s = ByteBuffer.allocate(BUFFER_SIZE)
        val s2c = ByteBuffer.allocate(BUFFER_SIZE)
        val app = ByteBuffer.allocate(BUFFER_SIZE)
        var clientDone = false
        var serverDone = false
        var guard = 0
        while ((!clientDone || !serverDone) && guard++ < MAX_PUMP_STEPS) {
            while (!clientDone) {
                val result = client.wrap(ByteBuffer.allocate(0), c2s)
                runDelegated(client)
                check(result.status != SSLEngineResult.Status.CLOSED) { "client engine closed" }
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                    clientDone = true
                }
                if (result.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) break
            }
            c2s.flip()
            if (c2s.hasRemaining()) {
                val result = server.unwrap(c2s, app)
                runDelegated(server)
                check(result.status != SSLEngineResult.Status.CLOSED) { "server engine closed" }
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                    serverDone = true
                }
            }
            c2s.compact()
            while (!serverDone) {
                val result = server.wrap(ByteBuffer.allocate(0), s2c)
                runDelegated(server)
                check(result.status != SSLEngineResult.Status.CLOSED) { "server engine closed" }
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                    serverDone = true
                }
                if (result.handshakeStatus != SSLEngineResult.HandshakeStatus.NEED_WRAP) break
            }
            s2c.flip()
            if (s2c.hasRemaining()) {
                val result = client.unwrap(s2c, app)
                runDelegated(client)
                check(result.status != SSLEngineResult.Status.CLOSED) { "client engine closed" }
                if (result.handshakeStatus == SSLEngineResult.HandshakeStatus.FINISHED) {
                    clientDone = true
                }
            }
            s2c.compact()
        }
        check(clientDone && serverDone) { "handshake did not finish" }
    }

    private fun runDelegated(engine: SSLEngine) {
        var task = engine.delegatedTask
        while (task != null) {
            task.run()
            task = engine.delegatedTask
        }
    }

    /**
     * TCP-connect probe: a plain socket handshake proves the engine is
     * listening (no TLS handshake needed — the point is the port, and
     * a failed engine binds nothing). Polls because `start(wait =
     * false)` returns before the bind completes.
     */
    private fun waitForListening(port: Int): Boolean {
        val deadline = System.currentTimeMillis() + PROBE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                Socket().use { socket ->
                    socket.connect(InetSocketAddress("127.0.0.1", port), PROBE_CONNECT_MS)
                }
                return true
            } catch (_: IOException) {
                Thread.sleep(PROBE_INTERVAL_MS)
            }
        }
        return false
    }

    private fun freePort(): Int = ServerSocket(0).use { it.localPort }

    private fun testIdentity(): TestIdentity {
        val cert = SelfSignedCertificate.builder().build()
        val password = "test".toCharArray()
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry(ALIAS, cert.key(), password, arrayOf(cert.cert()))
        }
        return TestIdentity(keyStore, ALIAS, password)
    }

    private data class TestIdentity(
        val keyStore: KeyStore,
        val alias: String,
        val password: CharArray,
    )

    companion object {
        private const val ALIAS = "test"
        private const val PROBE_TIMEOUT_MS = 5_000L
        private const val PROBE_INTERVAL_MS = 50L
        private const val PROBE_CONNECT_MS = 200
        private const val BUFFER_SIZE = 32768
        private const val MAX_PUMP_STEPS = 100
    }
}
