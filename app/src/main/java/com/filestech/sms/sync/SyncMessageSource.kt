package com.filestech.sms.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Slice 2 — the seam between the sync engine and the phone's message
 * store (SYNC_PROTOCOL.md, glue point 4). The sync package defines
 * this contract and never sees Room, the DAOs, or the domain models:
 * the implementation lives outside the sync package and maps domain
 * models to the wire DTOs below. A future port re-implements this
 * one interface and lifts the sync package as-is.
 *
 * All ids on the wire are decimal strings of the phone's Room ids
 * (the desktop's ids are strings everywhere, and this dodges any JS
 * number-precision concern).
 */
interface SyncMessageSource {
    /** One-shot snapshot of every non-vault conversation (archived included), most recent first. */
    suspend fun conversations(): List<SyncedConversation>

    /** Live conversation list (same content as [conversations], as a Flow). */
    fun observeConversations(): Flow<List<SyncedConversation>>

    /**
     * One page of one conversation, newest first: ids strictly below
     * [beforeId] (0 = newest), at most [limit] rows, `hidden = 0`.
     */
    suspend fun messagesBefore(conversationId: Long, beforeId: Long, limit: Int): List<SyncedMessage>

    /** Messages with id strictly above [afterId], oldest first — the live path. */
    suspend fun messagesAfter(conversationId: Long, afterId: Long): List<SyncedMessage>

    /** Total message count (all non-vault conversations) — the progress total. */
    suspend fun totalMessageCount(): Long

    /** Per-conversation message counts — the `message_count` field. */
    suspend fun messageCountsByConversation(): Map<Long, Int>

    /** Current max message id — the "now" cursor for `new_only` mode. */
    suspend fun maxMessageId(): Long

    /** Live states (status / reaction / read / starred) for a bounded id set. */
    fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>>

    /** Readiness: is there anything to sync, and if not, why? */
    suspend fun readiness(): Readiness
}

/**
 * One conversation on the wire (§3.3 CONVERSATION). Plain data — not
 * the domain model. [title] is the phone's computed title (custom
 * name > display name > numbers) and is authoritative for display.
 * Never carries a vault conversation, a draft, or appearance data.
 *
 * The nullable fields have no defaults, so they are always encoded
 * (as explicit JSON nulls) — the wire shape of §3.3, not the
 * omit-when-null shape `encodeDefaults = false` would give them.
 */
@Serializable
data class SyncedConversation(
    @SerialName("id") val id: String,
    @SerialName("thread_id") val threadId: Long?,
    @SerialName("addresses") val addresses: List<String>,
    @SerialName("display_name") val displayName: String?,
    @SerialName("custom_name") val customName: String?,
    @SerialName("title") val title: String,
    @SerialName("last_message_at") val lastMessageAt: Long,
    @SerialName("last_message_preview") val lastMessagePreview: String?,
    @SerialName("unread") val unread: Int,
    @SerialName("pinned") val pinned: Boolean,
    @SerialName("archived") val archived: Boolean,
    @SerialName("muted") val muted: Boolean,
    @SerialName("blocked") val blocked: Boolean,
    @SerialName("message_count") val messageCount: Int,
    @SerialName("pinned_message_id") val pinnedMessageId: String?,
    @SerialName("pinned_message_at") val pinnedMessageAt: Long?,
)

/**
 * One message on the wire (§3.3 MESSAGE). [type] is `"sms"` | `"mms"`,
 * [direction] is `"incoming"` | `"outgoing"`, [status] is `"pending"` |
 * `"sent"` | `"delivered"` | `"failed"` | `"received"` | `"scheduled"`.
 * [address] is the sender's address (for group messages, the sender,
 * not the group). Attachments are metadata only in Slice 2 — no bytes.
 */
@Serializable
data class SyncedMessage(
    @SerialName("id") val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("address") val address: String,
    @SerialName("body") val body: String,
    @SerialName("type") val type: String,
    @SerialName("direction") val direction: String,
    @SerialName("date") val date: Long,
    @SerialName("date_sent") val dateSent: Long?,
    @SerialName("read") val read: Boolean,
    @SerialName("starred") val starred: Boolean,
    @SerialName("status") val status: String,
    @SerialName("error_code") val errorCode: Int?,
    @SerialName("reply_to_message_id") val replyToMessageId: String?,
    @SerialName("reaction") val reaction: String?,
    @SerialName("attachments") val attachments: List<SyncedAttachment>,
)

/** One attachment's metadata on the wire (§3.3 ATTACHMENT) — no bytes in Slice 2. */
@Serializable
data class SyncedAttachment(
    @SerialName("id") val id: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("file_name") val fileName: String?,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("width") val width: Int?,
    @SerialName("height") val height: Int?,
    @SerialName("duration_ms") val durationMs: Long?,
)

/**
 * The live-mutable slice of one synced message: what the states
 * watch compares to decide whether a `message_updated` push is due.
 * No body, no attachments — just the fields that can change after a
 * message was first synced. [status] is the wire string (see
 * [SyncedMessage]).
 */
data class MessageState(
    val id: Long,
    val conversationId: Long,
    val status: String,
    val reaction: String?,
    val read: Boolean,
    val starred: Boolean,
)

/** What [SyncMessageSource.readiness] reports: syncable, or why not. */
sealed interface Readiness {
    /** SMS permission granted and the mirror holds at least one message. */
    data object Ready : Readiness

    /** READ_SMS is missing — the mirror cannot be populated. */
    data object NoSmsPermission : Readiness

    /** Permission granted, but the phone's message import hasn't run yet. */
    data object MirrorEmpty : Readiness
}
