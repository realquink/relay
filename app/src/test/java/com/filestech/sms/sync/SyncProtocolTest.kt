package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Slice 1 — the wire protocol: every message type encodes with its
 * `type` field and parses back to the same value; unknown types and
 * malformed frames are classified, never thrown.
 */
class SyncProtocolTest {

    @Test fun `hello round-trips`() {
        val hello = SyncProtocol.Hello(
            protocol = 1,
            deviceId = DEVICE_ID,
            kind = "desktop",
            name = "Kristin's MacBook",
        )
        val parsed = SyncProtocol.parse(SyncProtocol.encodeHello(hello))
        assertThat(parsed).isEqualTo(SyncProtocol.Parsed.Hello(hello))
    }

    @Test fun `challenge encodes with type and challenge fields`() {
        val encoded = SyncProtocol.encodeChallenge(SyncProtocol.Challenge("abc123"))
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("challenge")
        assertThat(obj["challenge"]?.jsonPrimitive?.content).isEqualTo("abc123")
    }

    @Test fun `auth_ok encodes hub id and name`() {
        val encoded = SyncProtocol.encodeAuthOk(
            SyncProtocol.AuthOk(hubId = HUB_ID, hubName = "Relay"),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("auth_ok")
        assertThat(obj["hub_id"]?.jsonPrimitive?.content).isEqualTo(HUB_ID)
        assertThat(obj["hub_name"]?.jsonPrimitive?.content).isEqualTo("Relay")
    }

    @Test fun `auth_failed encodes the reason`() {
        val encoded = SyncProtocol.encodeAuthFailed(
            SyncProtocol.AuthFailed(SyncProtocol.AuthFailed.BAD_SIGNATURE),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("auth_failed")
        assertThat(obj["reason"]?.jsonPrimitive?.content).isEqualTo("bad_signature")
    }

    @Test fun `ping and pong round-trip with the same timestamp`() {
        val ping = SyncProtocol.Ping(t = 1_700_000_000_000L)
        assertThat(SyncProtocol.parse(SyncProtocol.encodePing(ping)))
            .isEqualTo(SyncProtocol.Parsed.Ping(ping))
        assertThat(SyncProtocol.parse(SyncProtocol.encodePong(ping)))
            .isEqualTo(SyncProtocol.Parsed.Pong(ping))
    }

    @Test fun `revoked is exactly the bare type object`() {
        assertThat(SyncProtocol.REVOKED_JSON).isEqualTo("""{"type":"revoked"}""")
    }

    @Test fun `error encodes type and message`() {
        val encoded = SyncProtocol.encodeError("protocol violation")
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("error")
        assertThat(obj["message"]?.jsonPrimitive?.content).isEqualTo("protocol violation")
    }

    @Test fun `unknown type is classified, not malformed`() {
        val parsed = SyncProtocol.parse("""{"type":"future_thing","x":1}""")
        assertThat(parsed).isEqualTo(SyncProtocol.Parsed.Unknown("future_thing"))
    }

    @Test fun `missing type is malformed`() {
        assertThat(SyncProtocol.parse("""{"protocol":1}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `not json at all is malformed`() {
        assertThat(SyncProtocol.parse("this is not json"))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `a json array is malformed`() {
        assertThat(SyncProtocol.parse("""[1, 2, 3]"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `hello with a missing field is malformed`() {
        assertThat(
            SyncProtocol.parse(
                """{"type":"hello","protocol":1,"device_id":"$DEVICE_ID"}""",
            ),
        ).isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `extra unknown fields are ignored`() {
        val parsed = SyncProtocol.parse(
            """{"type":"ping","t":42,"future_field":"kept out"}""",
        )
        assertThat(parsed).isEqualTo(SyncProtocol.Parsed.Ping(SyncProtocol.Ping(t = 42)))
    }

    @Test fun `linked device list round-trips through json`() {
        val devices = listOf(
            LinkedDevice(
                deviceId = DEVICE_ID,
                kind = DeviceKind.DESKTOP,
                displayName = "Kristin's MacBook",
                publicKeyBase64Url = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                linkedAt = 1_700_000_000_000L,
                lastSeen = null,
            ),
            LinkedDevice(
                deviceId = "123e4567-e89b-12d3-a456-426614174001",
                kind = DeviceKind.TABLET,
                displayName = "iPad",
                publicKeyBase64Url = "BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB",
                linkedAt = 1_700_000_100_000L,
                lastSeen = 1_700_000_200_000L,
            ),
        )
        val encoded = SyncProtocol.json.encodeToString(devices)
        val decoded: List<LinkedDevice> = SyncProtocol.json.decodeFromString(encoded)
        assertThat(decoded).isEqualTo(devices)
        // The wire names are the snake_case serial names, not the Kotlin ones.
        assertThat(encoded).contains("\"device_id\"")
        assertThat(encoded).contains("\"public_key\"")
        assertThat(encoded).contains("\"desktop\"")
    }

    // -- Slice 2: sync types ------------------------------------------

    @Test fun `sync_request round-trips with a cursor`() {
        // `sync_request` is client → server, so the server never
        // encodes it — the frame is built the way the client sends it.
        val frame = """{"type":"sync_request","mode":"full","since_message_id":"12345"}"""
        assertThat(SyncProtocol.parse(frame)).isEqualTo(
            SyncProtocol.Parsed.SyncRequest(
                SyncProtocol.SyncRequest(mode = "full", sinceMessageId = "12345"),
            ),
        )
    }

    @Test fun `sync_request without a cursor parses with null`() {
        val parsed = SyncProtocol.parse("""{"type":"sync_request","mode":"new_only"}""")
        assertThat(parsed).isEqualTo(
            SyncProtocol.Parsed.SyncRequest(
                SyncProtocol.SyncRequest(mode = "new_only", sinceMessageId = null),
            ),
        )
    }

    @Test fun `sync_request with a missing mode is malformed`() {
        assertThat(SyncProtocol.parse("""{"type":"sync_request"}"""))
            .isEqualTo(SyncProtocol.Parsed.Malformed)
    }

    @Test fun `messages_request round-trips`() {
        val frame = """{"type":"messages_request","conversation_id":"123","before_id":"789","limit":200}"""
        assertThat(SyncProtocol.parse(frame)).isEqualTo(
            SyncProtocol.Parsed.MessagesRequest(
                SyncProtocol.MessagesRequest(conversationId = "123", beforeId = "789", limit = 200),
            ),
        )
    }

    @Test fun `messages_request without optionals parses with nulls`() {
        val parsed = SyncProtocol.parse("""{"type":"messages_request","conversation_id":"123"}""")
        assertThat(parsed).isEqualTo(
            SyncProtocol.Parsed.MessagesRequest(
                SyncProtocol.MessagesRequest(conversationId = "123", beforeId = null, limit = null),
            ),
        )
    }

    @Test fun `sync_started encodes totals and cursor`() {
        val encoded = SyncProtocol.encodeSyncStarted(
            SyncProtocol.SyncStarted(
                mode = "full",
                totalConversations = 87,
                totalMessages = 12340,
                cursorMessageId = "98765",
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("sync_started")
        assertThat(obj["mode"]?.jsonPrimitive?.content).isEqualTo("full")
        assertThat(obj["total_conversations"]?.jsonPrimitive?.content).isEqualTo("87")
        assertThat(obj["total_messages"]?.jsonPrimitive?.content).isEqualTo("12340")
        assertThat(obj["cursor_message_id"]?.jsonPrimitive?.content).isEqualTo("98765")
    }

    @Test fun `conversations encodes the batch and has_more`() {
        val encoded = SyncProtocol.encodeConversations(
            SyncProtocol.Conversations(
                conversations = listOf(sampleConversation(id = 123)),
                hasMore = true,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("conversations")
        assertThat(obj["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
        val first = obj["conversations"]!!.jsonArray[0].jsonObject
        assertThat(first["id"]?.jsonPrimitive?.content).isEqualTo("123")
        assertThat(first["title"]?.jsonPrimitive?.content).isEqualTo("Marie")
        assertThat(first["message_count"]?.jsonPrimitive?.content).isEqualTo("0")
    }

    @Test fun `messages_batch omits next_before_id when has_more is false`() {
        val encoded = SyncProtocol.encodeMessagesBatch(
            SyncProtocol.MessagesBatch(
                conversationId = "123",
                messages = listOf(sampleMessage(id = 789, conversationId = 123)),
                hasMore = false,
                nextBeforeId = null,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("messages_batch")
        assertThat(obj["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(obj.containsKey("next_before_id")).isFalse()
        val first = obj["messages"]!!.jsonArray[0].jsonObject
        assertThat(first["id"]?.jsonPrimitive?.content).isEqualTo("789")
        assertThat(first["type"]?.jsonPrimitive?.content).isEqualTo("sms")
        assertThat(first["direction"]?.jsonPrimitive?.content).isEqualTo("incoming")
        assertThat(first["status"]?.jsonPrimitive?.content).isEqualTo("received")
    }

    @Test fun `messages_batch carries next_before_id when has_more is true`() {
        val encoded = SyncProtocol.encodeMessagesBatch(
            SyncProtocol.MessagesBatch(
                conversationId = "123",
                messages = listOf(sampleMessage(id = 789, conversationId = 123)),
                hasMore = true,
                nextBeforeId = "456",
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["next_before_id"]?.jsonPrimitive?.content).isEqualTo("456")
    }

    @Test fun `sync_complete encodes the cursor`() {
        val encoded = SyncProtocol.encodeSyncComplete(SyncProtocol.SyncComplete("98765"))
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("sync_complete")
        assertThat(obj["cursor_message_id"]?.jsonPrimitive?.content).isEqualTo("98765")
    }

    @Test fun `message_new encodes the message with explicit nulls and attachment metadata`() {
        val message = sampleMessage(id = 789, conversationId = 123).copy(
            attachments = listOf(
                SyncedAttachment(
                    id = "12",
                    mimeType = "image/jpeg",
                    fileName = "photo.jpg",
                    sizeBytes = 412000,
                    width = 4032,
                    height = 3024,
                    durationMs = null,
                ),
            ),
        )
        val encoded = SyncProtocol.encodeMessageNew(SyncProtocol.MessageNew(message))
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("message_new")
        val payload = obj["message"]!!.jsonObject
        // §3.3 shows explicit nulls for absent optional fields.
        assertThat(payload["date_sent"] is JsonNull).isTrue()
        assertThat(payload["error_code"] is JsonNull).isTrue()
        assertThat(payload["reaction"] is JsonNull).isTrue()
        val attachment = payload["attachments"]!!.jsonArray[0].jsonObject
        assertThat(attachment["mime_type"]?.jsonPrimitive?.content).isEqualTo("image/jpeg")
        assertThat(attachment["file_name"]?.jsonPrimitive?.content).isEqualTo("photo.jpg")
        assertThat(attachment["size_bytes"]?.jsonPrimitive?.content).isEqualTo("412000")
        assertThat(attachment["duration_ms"] is JsonNull).isTrue()
    }

    @Test fun `message_updated encodes only the changed fields`() {
        val encoded = SyncProtocol.encodeMessageUpdated(
            SyncProtocol.MessageUpdated(messageId = "789", conversationId = "123", status = "delivered"),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("message_updated")
        assertThat(obj["status"]?.jsonPrimitive?.content).isEqualTo("delivered")
        assertThat(obj.containsKey("reaction")).isFalse()
        assertThat(obj.containsKey("reaction_removed")).isFalse()
        assertThat(obj.containsKey("read")).isFalse()
        assertThat(obj.containsKey("starred")).isFalse()
    }

    @Test fun `message_updated encodes a removed reaction as a flag`() {
        val encoded = SyncProtocol.encodeMessageUpdated(
            SyncProtocol.MessageUpdated(
                messageId = "789",
                conversationId = "123",
                reactionRemoved = true,
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj.containsKey("reaction")).isFalse()
        assertThat(obj["reaction_removed"]?.jsonPrimitive?.content).isEqualTo("true")
    }

    @Test fun `conversation_updated encodes the full row`() {
        val encoded = SyncProtocol.encodeConversationUpdated(
            SyncProtocol.ConversationUpdated(sampleConversation(id = 123)),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("conversation_updated")
        assertThat(obj["conversation"]!!.jsonObject["id"]?.jsonPrimitive?.content).isEqualTo("123")
    }

    @Test fun `conversation_deleted encodes the id`() {
        val encoded = SyncProtocol.encodeConversationDeleted(
            SyncProtocol.ConversationDeleted(conversationId = "123"),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("conversation_deleted")
        assertThat(obj["conversation_id"]?.jsonPrimitive?.content).isEqualTo("123")
    }

    @Test fun `sync_error encodes code and message`() {
        val encoded = SyncProtocol.encodeSyncError(
            SyncProtocol.SyncError(
                code = SyncProtocol.SyncError.HUB_NO_SMS_ACCESS,
                message = "Relay on the phone needs SMS permission to sync messages.",
            ),
        )
        val obj = Json.parseToJsonElement(encoded).jsonObject
        assertThat(obj["type"]?.jsonPrimitive?.content).isEqualTo("sync_error")
        assertThat(obj["code"]?.jsonPrimitive?.content).isEqualTo("hub_no_sms_access")
        assertThat(obj["message"]?.jsonPrimitive?.content)
            .isEqualTo("Relay on the phone needs SMS permission to sync messages.")
    }

    companion object {
        private const val DEVICE_ID = "123e4567-e89b-12d3-a456-426614174000"
        private const val HUB_ID = "123e4567-e89b-12d3-a456-426614174099"
    }
}
