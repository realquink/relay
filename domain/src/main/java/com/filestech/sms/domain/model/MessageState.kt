package com.filestech.sms.domain.model

/**
 * Slice 2 — the live-mutable slice of one message: what the sync
 * states watch compares to decide whether a `message_updated` push
 * is due. No body, no attachments — just the fields that can change
 * after a message was first synced.
 */
data class MessageState(
    val id: Long,
    val conversationId: Long,
    val status: Message.Status,
    val reactionEmoji: String?,
    val read: Boolean,
    val starred: Boolean,
)
