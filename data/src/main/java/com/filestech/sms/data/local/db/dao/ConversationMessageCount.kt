package com.filestech.sms.data.local.db.dao

import androidx.room.ColumnInfo

/**
 * Slice 2 — one row of [MessageDao.countByConversation]: how many
 * visible messages a non-vault conversation holds. Conversations
 * with no visible message produce no row.
 */
data class ConversationMessageCount(
    @ColumnInfo(name = "conversation_id") val conversationId: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int,
)
