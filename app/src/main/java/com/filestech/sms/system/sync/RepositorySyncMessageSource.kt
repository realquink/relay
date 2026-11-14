package com.filestech.sms.system.sync

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.filestech.sms.domain.model.Attachment
import com.filestech.sms.domain.model.Conversation
import com.filestech.sms.domain.model.Message
import com.filestech.sms.domain.repository.ConversationRepository
import com.filestech.sms.sync.MessageState
import com.filestech.sms.sync.Readiness
import com.filestech.sms.sync.SyncMessageSource
import com.filestech.sms.sync.SyncedAttachment
import com.filestech.sms.sync.SyncedConversation
import com.filestech.sms.sync.SyncedMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton
import com.filestech.sms.domain.model.MessageState as DomainMessageState

/**
 * Slice 2 glue (SYNC_PROTOCOL.md, glue point 4): the
 * [SyncMessageSource] implementation — the one place that knows both
 * the sync wire DTOs and the app's repositories, so the sync package
 * itself keeps importing no app code.
 *
 * It lives in the app module, not the data module, on purpose: the
 * data module cannot see the app's sync package (module layering
 * runs app → data, never the reverse), and the sync package cannot
 * move into the data module without dragging the transport along.
 * App-module glue, next to the other system seams.
 */
@Singleton
class RepositorySyncMessageSource @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ConversationRepository,
) : SyncMessageSource {

    override suspend fun conversations(): List<SyncedConversation> {
        val counts = repository.messageCountsByConversation()
        return allConversations().first()
            .sortedByDescending { it.lastMessageAt }
            .map { it.toSynced(counts[it.id] ?: 0) }
    }

    override fun observeConversations(): Flow<List<SyncedConversation>> =
        allConversations().map { list ->
            val counts = repository.messageCountsByConversation()
            list.sortedByDescending { it.lastMessageAt }
                .map { it.toSynced(counts[it.id] ?: 0) }
        }

    /**
     * `observeAll`'s parameter means "archived ONLY", not "include
     * archived" — both halves are needed for the whole list. Vault
     * conversations are excluded by the DAO itself (`in_vault = 0`),
     * so sync can never leak them, by construction.
     */
    private fun allConversations(): Flow<List<Conversation>> =
        combine(
            repository.observeAll(false),
            repository.observeAll(true),
        ) { active, archived -> active + archived }

    override suspend fun messagesBefore(
        conversationId: Long,
        beforeId: Long,
        limit: Int,
    ): List<SyncedMessage> =
        repository.messagesBefore(conversationId, beforeId, limit).map { it.toSynced() }

    override suspend fun messagesAfter(conversationId: Long, afterId: Long): List<SyncedMessage> =
        repository.messagesAfter(conversationId, afterId).map { it.toSynced() }

    override suspend fun totalMessageCount(): Long = repository.totalMessageCount()

    override suspend fun messageCountsByConversation(): Map<Long, Int> =
        repository.messageCountsByConversation()

    override suspend fun maxMessageId(): Long = repository.maxMessageId()

    override fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>> {
        if (ids.isEmpty()) return flowOf(emptyList())
        return repository.observeMessageStates(ids).map { states ->
            states.map { it.toSynced() }
        }
    }

    override suspend fun readiness(): Readiness {
        val smsGranted =
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) ==
                PackageManager.PERMISSION_GRANTED
        if (!smsGranted) return Readiness.NoSmsPermission
        if (repository.totalMessageCount() == 0L) return Readiness.MirrorEmpty
        return Readiness.Ready
    }
}

/** Domain → wire mapping. Ids are decimal strings of the Room ids. */
private fun Conversation.toSynced(messageCount: Int): SyncedConversation = SyncedConversation(
    id = id.toString(),
    threadId = threadId,
    addresses = addresses.map { it.raw },
    displayName = displayName,
    customName = customName,
    title = title,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    unread = unreadCount,
    pinned = pinned,
    archived = archived,
    muted = muted,
    blocked = blocked,
    messageCount = messageCount,
    pinnedMessageId = pinnedMessageId?.toString(),
    pinnedMessageAt = pinnedMessageAt,
)

private fun Message.toSynced(): SyncedMessage = SyncedMessage(
    id = id.toString(),
    conversationId = conversationId.toString(),
    address = address,
    body = body,
    type = type.name.lowercase(),
    direction = direction.name.lowercase(),
    date = date,
    dateSent = dateSent,
    read = read,
    starred = starred,
    status = status.name.lowercase(),
    errorCode = errorCode,
    replyToMessageId = replyToMessageId?.toString(),
    reaction = reactionEmoji,
    attachments = attachments.map { it.toSynced() },
)

private fun Attachment.toSynced(): SyncedAttachment = SyncedAttachment(
    id = id.toString(),
    mimeType = mimeType,
    fileName = fileName,
    sizeBytes = sizeBytes,
    width = width,
    height = height,
    durationMs = durationMs,
)

private fun DomainMessageState.toSynced(): MessageState = MessageState(
    id = id,
    conversationId = conversationId,
    status = status.name.lowercase(),
    reaction = reactionEmoji,
    read = read,
    starred = starred,
)
