package com.filestech.sms.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer

/**
 * Slice 1 wire protocol — the JSON messages of SYNC_PROTOCOL.md, nothing
 * more. This file is standalone: it imports no app code, so the sync
 * layer can be lifted out of this codebase as-is (see the glue-points
 * section at the end of SYNC_PROTOCOL.md).
 *
 * Every message is a JSON object with a `type` field. The client speaks
 * first (`hello`), the server answers with a `challenge`, the client
 * proves it holds the linked device's private key (`auth`), and the
 * server answers `auth_ok` (or `auth_failed` and closes the socket).
 * After auth, the client sends `ping` every 30 s and the server answers
 * `pong`; a client silent for more than 90 s is dropped.
 *
 * Slice 2 adds the sync types below (`sync_request` … `sync_error`) in
 * the same envelope; the protocol version stays 1.
 */
object SyncProtocol {

    /** Fixed LAN port from the spec — discovery and the server agree on it. */
    const val PORT = 47831

    /** mDNS service type, instance name and hub name from the spec. */
    const val SERVICE_TYPE = "_relay._tcp."
    const val SERVICE_NAME = "Relay"
    const val HUB_NAME = "Relay"

    /** Protocol version carried in `hello` and in the mDNS TXT record. */
    const val PROTOCOL_VERSION = 1

    /** A socket that has not completed auth within this long is closed. */
    const val AUTH_TIMEOUT_MS = 10_000L

    /** The client pings this often; the server drops clients silent longer than [SILENCE_LIMIT_MS]. */
    const val PING_INTERVAL_MS = 30_000L
    const val SILENCE_LIMIT_MS = 90_000L

    /** How often the server sweeps for silent clients. */
    const val SILENCE_CHECK_MS = 30_000L

    /** Challenge, key and signature sizes from the spec. */
    const val CHALLENGE_BYTES = 32
    const val PUBLIC_KEY_BYTES = 32
    const val SIGNATURE_BYTES = 64

    /** TLS 1.2 minimum, per the spec. */
    val TLS_PROTOCOLS = listOf("TLSv1.2", "TLSv1.3")

    /** Lenient JSON: unknown fields are ignored, so a newer client stays readable. */
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    /** Client → server, first message on a new socket. */
    @Serializable
    data class Hello(
        @SerialName("protocol") val protocol: Int,
        @SerialName("device_id") val deviceId: String,
        @SerialName("kind") val kind: String,
        @SerialName("name") val name: String,
    )

    /** Server → client: 32 random bytes, base64url, to be signed. */
    @Serializable
    data class Challenge(
        @SerialName("challenge") val challenge: String,
    )

    /** Client → server: Ed25519 signature over the raw challenge bytes, base64url. */
    @Serializable
    data class Auth(
        @SerialName("device_id") val deviceId: String,
        @SerialName("signature") val signature: String,
    )

    /** Server → client: auth succeeded. */
    @Serializable
    data class AuthOk(
        @SerialName("hub_id") val hubId: String,
        @SerialName("hub_name") val hubName: String,
    )

    /** Server → client: auth failed; the socket is closed after this. */
    @Serializable
    data class AuthFailed(
        @SerialName("reason") val reason: String,
    ) {
        companion object {
            const val UNKNOWN_DEVICE = "unknown_device"
            const val BAD_SIGNATURE = "bad_signature"
            const val REVOKED = "revoked"
            const val PROTOCOL = "protocol"
        }
    }

    /** Either way: heartbeat. `pong` echoes the `t` it received. */
    @Serializable
    data class Ping(
        @SerialName("t") val t: Long,
    )

    /** Either way: free-form error. Never carries secrets. */
    @Serializable
    data class Error(
        @SerialName("message") val message: String,
    )

    // -- Slice 2: sync types (SYNC_PROTOCOL.md, §3) --------------------

    /**
     * Client → server: starts (or restarts) a sync after auth.
     * [sinceMessageId] is the desktop's stored global cursor (max
     * message id it has stored), or `"0"` / omitted on a first sync;
     * ignored for [NEW_ONLY] (the cursor is set to "now").
     */
    @Serializable
    data class SyncRequest(
        @SerialName("mode") val mode: String,
        @SerialName("since_message_id") val sinceMessageId: String? = null,
    ) {
        companion object {
            /** The entire message history comes over. */
            const val FULL = "full"

            /** Only new messages from now on arrive (the list still syncs). */
            const val NEW_ONLY = "new_only"
        }
    }

    /**
     * Client → server: targeted fetch — gap-fill after a dropped
     * connection, repair of one conversation, or on-demand fetch.
     * [beforeId] fetches messages with id strictly below it, newest
     * first (`"0"` / omitted = newest); [limit] is capped at 200 by
     * the server.
     */
    @Serializable
    data class MessagesRequest(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("before_id") val beforeId: String? = null,
        @SerialName("limit") val limit: Int? = null,
    )

    /** Server → client: first reply to `sync_request` — totals + cursor. */
    @Serializable
    data class SyncStarted(
        @SerialName("mode") val mode: String,
        @SerialName("total_conversations") val totalConversations: Int,
        @SerialName("total_messages") val totalMessages: Long,
        @SerialName("cursor_message_id") val cursorMessageId: String,
    )

    /** Server → client: one batch of the conversation snapshot (100 per batch). */
    @Serializable
    data class Conversations(
        @SerialName("conversations") val conversations: List<SyncedConversation>,
        @SerialName("has_more") val hasMore: Boolean,
    )

    /**
     * Server → client: one page of one conversation, newest first.
     * [nextBeforeId] is passed back as `before_id` for the next page;
     * absent when [hasMore] is false.
     */
    @Serializable
    data class MessagesBatch(
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("messages") val messages: List<SyncedMessage>,
        @SerialName("has_more") val hasMore: Boolean,
        @SerialName("next_before_id") val nextBeforeId: String? = null,
    )

    /** Server → client: the initial stream is finished. */
    @Serializable
    data class SyncComplete(
        @SerialName("cursor_message_id") val cursorMessageId: String,
    )

    /** Server → client: one live message (a burst arrives as several frames). */
    @Serializable
    data class MessageNew(
        @SerialName("message") val message: SyncedMessage,
    )

    /**
     * Server → client: a synced message changed (status, reaction,
     * read, starred). Partial — only changed fields are present. A
     * removed reaction cannot ride as a null `reaction` (nulls are
     * omitted on encode), so it has its own flag: [reactionRemoved].
     */
    @Serializable
    data class MessageUpdated(
        @SerialName("message_id") val messageId: String,
        @SerialName("conversation_id") val conversationId: String,
        @SerialName("status") val status: String? = null,
        @SerialName("reaction") val reaction: String? = null,
        @SerialName("reaction_removed") val reactionRemoved: Boolean = false,
        @SerialName("read") val read: Boolean? = null,
        @SerialName("starred") val starred: Boolean? = null,
    )

    /** Server → client: a conversation row changed — the full row is sent. */
    @Serializable
    data class ConversationUpdated(
        @SerialName("conversation") val conversation: SyncedConversation,
    )

    /** Server → client: a conversation disappeared from the phone. */
    @Serializable
    data class ConversationDeleted(
        @SerialName("conversation_id") val conversationId: String,
    )

    /** Server → client: the phone cannot sync right now. */
    @Serializable
    data class SyncError(
        @SerialName("code") val code: String,
        @SerialName("message") val message: String,
    ) {
        companion object {
            /** READ_SMS missing on the phone. */
            const val HUB_NO_SMS_ACCESS = "hub_no_sms_access"

            /** Permission granted, but the phone's message import hasn't run yet. */
            const val HUB_MIRROR_EMPTY = "hub_mirror_empty"

            /** Anything else; [SyncError.message] stays generic, never a stack trace. */
            const val INTERNAL = "internal"
        }
    }

    /**
     * What the server makes of one incoming text frame. [Unknown] keeps
     * the connection (a newer client may send types this build does not
     * know yet); [Malformed] is a protocol violation.
     */
    sealed interface Parsed {
        data class Hello(val value: SyncProtocol.Hello) : Parsed
        data class Auth(val value: SyncProtocol.Auth) : Parsed
        data class Ping(val value: SyncProtocol.Ping) : Parsed
        data class Pong(val value: SyncProtocol.Ping) : Parsed
        data class SyncRequest(val value: SyncProtocol.SyncRequest) : Parsed
        data class MessagesRequest(val value: SyncProtocol.MessagesRequest) : Parsed
        data class Unknown(val type: String?) : Parsed
        data object Malformed : Parsed
    }

    /**
     * Parses one incoming text frame. Never throws: bad JSON, a missing
     * `type`, or a wrongly-shaped known message all come back as
     * [Parsed.Malformed]; a well-formed message of an unknown type comes
     * back as [Parsed.Unknown].
     */
    fun parse(text: String): Parsed {
        val element = runCatching { json.parseToJsonElement(text) }.getOrNull()
            ?: return Parsed.Malformed
        val obj: JsonObject = runCatching { element.jsonObject }.getOrNull()
            ?: return Parsed.Malformed
        val type = obj["type"]?.jsonPrimitive?.content
        return when (type) {
            "hello" -> decode<Hello>(obj)?.let(Parsed::Hello) ?: Parsed.Malformed
            "auth" -> decode<Auth>(obj)?.let(Parsed::Auth) ?: Parsed.Malformed
            "ping" -> decode<Ping>(obj)?.let(Parsed::Ping) ?: Parsed.Malformed
            "pong" -> decode<Ping>(obj)?.let(Parsed::Pong) ?: Parsed.Malformed
            "sync_request" -> decode<SyncRequest>(obj)?.let(Parsed::SyncRequest) ?: Parsed.Malformed
            "messages_request" -> decode<MessagesRequest>(obj)?.let(Parsed::MessagesRequest) ?: Parsed.Malformed
            null -> Parsed.Malformed
            else -> Parsed.Unknown(type)
        }
    }

    private inline fun <reified T> decode(obj: JsonObject): T? =
        runCatching { json.decodeFromJsonElement<T>(obj) }.getOrNull()

    /** Encodes a message with its `type` field, ready to send. */
    fun encodeHello(value: Hello): String = withType(value, "hello").toString()

    fun encodeChallenge(value: Challenge): String = withType(value, "challenge").toString()

    fun encodeAuthOk(value: AuthOk): String = withType(value, "auth_ok").toString()

    fun encodeAuthFailed(value: AuthFailed): String = withType(value, "auth_failed").toString()

    fun encodePing(value: Ping): String = withType(value, "ping").toString()

    fun encodePong(value: Ping): String = withType(value, "pong").toString()

    /** The `revoked` message carries no payload, so it is a constant, not an encoder. */
    const val REVOKED_JSON = """{"type":"revoked"}"""

    fun encodeError(message: String): String = withType(Error(message), "error").toString()

    fun encodeSyncStarted(value: SyncStarted): String = withType(value, "sync_started").toString()

    fun encodeConversations(value: Conversations): String = withType(value, "conversations").toString()

    fun encodeMessagesBatch(value: MessagesBatch): String = withType(value, "messages_batch").toString()

    fun encodeSyncComplete(value: SyncComplete): String = withType(value, "sync_complete").toString()

    fun encodeMessageNew(value: MessageNew): String = withType(value, "message_new").toString()

    fun encodeMessageUpdated(value: MessageUpdated): String = withType(value, "message_updated").toString()

    fun encodeConversationUpdated(value: ConversationUpdated): String =
        withType(value, "conversation_updated").toString()

    fun encodeConversationDeleted(value: ConversationDeleted): String =
        withType(value, "conversation_deleted").toString()

    fun encodeSyncError(value: SyncError): String = withType(value, "sync_error").toString()

    /**
     * The `type` field is not part of the data classes (it is routing,
     * not payload), so it is merged in at encode time: the class is
     * encoded to a JSON object and `type` is added. A tiny extra step
     * that keeps the models honest.
     */
    private inline fun <reified T> withType(value: T, type: String): JsonObject {
        val obj = json.encodeToJsonElement(serializer(), value).jsonObject
        return JsonObject(obj + ("type" to JsonPrimitive(type)))
    }
}
