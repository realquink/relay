package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.jupiter.api.Test
import java.security.SecureRandom
import java.util.Base64

/**
 * Slice 1 — the auth + heartbeat state machine, driven without a
 * socket: the fake lookup holds one linked device with a real Ed25519
 * keypair, and the harness records every outbound frame and close.
 * This is the contract the desktop speaks against, so every rejection
 * path is pinned here: the reason string AND the close.
 */
class SyncSessionHandlerTest {

    private val privateKey = Ed25519PrivateKeyParameters(SecureRandom())
    private val publicKeyB64 = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(privateKey.generatePublicKey().encoded)
    private val device = LinkedDevice(
        deviceId = DEVICE_ID,
        kind = DeviceKind.DESKTOP,
        displayName = "Kristin's MacBook",
        publicKeyBase64Url = publicKeyB64,
        linkedAt = 0L,
    )

    /** One handler plus the frames it sent and the closes it asked for. */
    private class Harness(
        devices: Map<String, LinkedDevice>,
        engine: SyncEngine? = null,
        val sent: MutableList<String> = mutableListOf(),
    ) {
        val lookup = FakeLookup(devices)
        val closes = mutableListOf<String>()
        val handler = SyncSessionHandler(
            lookup = lookup,
            hubId = HUB_ID,
            send = { sent += it },
            close = { closes += it },
            engine = engine,
        )

        /** The reason of the last `auth_failed` frame, or null. */
        fun lastAuthFailedReason(): String? {
            val obj = Json.parseToJsonElement(sent.last()).jsonObject
            if (obj["type"]?.jsonPrimitive?.content != "auth_failed") return null
            return obj["reason"]?.jsonPrimitive?.content
        }
    }

    private class FakeLookup(private val devices: Map<String, LinkedDevice>) :
        LinkedDeviceLookup {
        val touched = mutableListOf<String>()
        override suspend fun findDevice(deviceId: String) = devices[deviceId]
        override suspend fun touchLastSeen(deviceId: String, nowMs: Long) {
            touched += deviceId
        }
    }

    @Test fun `happy path authenticates and answers auth_ok`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        assertThat(harness.sent).hasSize(1)
        assertThat(harness.closes).isEmpty()

        harness.handler.onText(authJson(harness.challengeBytes()))
        assertThat(harness.handler.isAuthenticated).isTrue()
        assertThat(harness.handler.deviceId).isEqualTo(DEVICE_ID)
        assertThat(harness.lookup.touched).contains(DEVICE_ID)
        val ok = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(ok["type"]?.jsonPrimitive?.content).isEqualTo("auth_ok")
        assertThat(ok["hub_id"]?.jsonPrimitive?.content).isEqualTo(HUB_ID)
        assertThat(ok["hub_name"]?.jsonPrimitive?.content).isEqualTo("Relay")
        assertThat(harness.closes).isEmpty()
    }

    @Test fun `auth before hello fails with protocol and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(authJson(ByteArray(32)))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
        assertThat(harness.handler.isAuthenticated).isFalse()
    }

    @Test fun `auth for a different device than hello fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        val other = "123e4567-e89b-12d3-a456-426614174001"
        harness.handler.onText(authJson(harness.challengeBytes(), deviceId = other))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `unknown device fails with unknown_device and closes`() = runTest {
        val harness = Harness(emptyMap())
        harness.handler.onText(helloJson())
        harness.handler.onText(authJson(harness.challengeBytes()))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.UNKNOWN_DEVICE)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `bad signature fails with bad_signature and closes`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        val wrongKey = Ed25519PrivateKeyParameters(SecureRandom())
        harness.handler.onText(authJson(harness.challengeBytes(), key = wrongKey))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.BAD_SIGNATURE)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `hello with a wrong protocol version fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(
            SyncProtocol.encodeHello(
                SyncProtocol.Hello(protocol = 99, deviceId = DEVICE_ID, kind = "desktop", name = "X"),
            ),
        )
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `a second hello fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        harness.handler.onText(helloJson())
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `ping before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(SyncProtocol.encodePing(SyncProtocol.Ping(t = 1L)))
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `ping after auth answers pong echoing t`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText(SyncProtocol.encodePing(SyncProtocol.Ping(t = 4242L)))
        assertThat(SyncProtocol.parse(harness.sent.last()))
            .isEqualTo(SyncProtocol.Parsed.Pong(SyncProtocol.Ping(t = 4242L)))
        assertThat(harness.lookup.touched).contains(DEVICE_ID)
        assertThat(harness.closes).isEmpty()
    }

    @Test fun `sync_request before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"sync_request","mode":"full"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `messages_request before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"messages_request","conversation_id":"1"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `sync_request after auth without an engine answers error and stays open`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText("""{"type":"sync_request","mode":"full"}""")
        val error = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(harness.closes).isEmpty()
        assertThat(harness.handler.isAuthenticated).isTrue()
    }

    @Test fun `sync_request after auth dispatches to the engine`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1))
        val sent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { sent += it })
        try {
            val harness = Harness(mapOf(DEVICE_ID to device), engine, sent)
            harness.handler.onText(helloJson())
            harness.handler.onText(authJson(harness.challengeBytes()))
            assertThat(harness.handler.isAuthenticated).isTrue()
            advanceUntilIdle()
            harness.handler.onText("""{"type":"sync_request","mode":"full"}""")
            advanceUntilIdle()
            val types = sent.map {
                Json.parseToJsonElement(it).jsonObject["type"]?.jsonPrimitive?.content
            }
            assertThat(types).contains("sync_started")
            assertThat(types).contains("sync_complete")
            assertThat(harness.closes).isEmpty()
        } finally {
            engine.stop()
        }
    }

    @Test fun `malformed frame before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("not json at all")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `malformed frame after auth sends error and closes`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText("{broken")
        val error = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `unknown type before auth fails with protocol`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText("""{"type":"future_thing"}""")
        assertThat(harness.lastAuthFailedReason())
            .isEqualTo(SyncProtocol.AuthFailed.PROTOCOL)
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `unknown type after auth sends error and stays open`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.onText("""{"type":"future_thing"}""")
        val error = Json.parseToJsonElement(harness.sent.last()).jsonObject
        assertThat(error["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(harness.closes).isEmpty()
        assertThat(harness.handler.isAuthenticated).isTrue()
    }

    @Test fun `auth timeout closes an unauthenticated socket only`() = runTest {
        val unauthed = Harness(mapOf(DEVICE_ID to device))
        unauthed.handler.onAuthTimeout()
        assertThat(unauthed.closes).hasSize(1)

        val authed = authenticatedHarness()
        authed.handler.onAuthTimeout()
        assertThat(authed.closes).isEmpty()
    }

    @Test fun `revoke sends revoked first, then closes`() = runTest {
        val harness = authenticatedHarness()
        harness.handler.revoke()
        assertThat(harness.sent.last()).isEqualTo("""{"type":"revoked"}""")
        assertThat(harness.closes).hasSize(1)
    }

    @Test fun `lastReceivedAtMs moves on every frame`() = runTest {
        val harness = Harness(mapOf(DEVICE_ID to device))
        val before = harness.handler.lastReceivedAtMs
        harness.handler.onText(helloJson())
        assertThat(harness.handler.lastReceivedAtMs).isAtLeast(before)
    }

    private fun helloJson(): String = SyncProtocol.encodeHello(
        SyncProtocol.Hello(
            protocol = SyncProtocol.PROTOCOL_VERSION,
            deviceId = DEVICE_ID,
            kind = "desktop",
            name = "Kristin's MacBook",
        ),
    )

    /** Signs [challenge] with [key] (default: the linked device's key) and builds the auth frame. */
    private fun authJson(
        challenge: ByteArray,
        deviceId: String = DEVICE_ID,
        key: Ed25519PrivateKeyParameters = privateKey,
    ): String {
        val signer = Ed25519Signer()
        signer.init(true, key)
        signer.update(challenge, 0, challenge.size)
        val signature = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(signer.generateSignature())
        return """{"type":"auth","device_id":"$deviceId","signature":"$signature"}"""
    }

    /** Decodes the challenge the handler just sent (the last frame). */
    private fun Harness.challengeBytes(): ByteArray {
        val obj = Json.parseToJsonElement(sent.last()).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("challenge")
        val b64 = obj["challenge"]!!.jsonPrimitive.content
        return Base64.getUrlDecoder().decode(QrPairing.padBase64(b64))
    }

    /** A harness that has completed the hello → challenge → auth flow. */
    private suspend fun authenticatedHarness(): Harness {
        val harness = Harness(mapOf(DEVICE_ID to device))
        harness.handler.onText(helloJson())
        harness.handler.onText(authJson(harness.challengeBytes()))
        assertThat(harness.handler.isAuthenticated).isTrue()
        return harness
    }

    companion object {
        private const val DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000"
        private const val HUB_ID = "123e4567-e89b-12d3-a456-426614174099"
    }
}
