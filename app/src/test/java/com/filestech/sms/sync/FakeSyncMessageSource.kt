package com.filestech.sms.sync

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * Slice 2 — a controllable [SyncMessageSource] for engine + handler
 * tests. The conversation flow is a SharedFlow (not a StateFlow) so
 * a test can re-emit an EQUAL list and prove the engine's diff stays
 * silent; a StateFlow would swallow the re-emission.
 */
internal class FakeSyncMessageSource : SyncMessageSource {
    var readiness: Readiness = Readiness.Ready
    var snapshot: List<SyncedConversation> = emptyList()
    val conversationsFlow = MutableSharedFlow<List<SyncedConversation>>(replay = 1)
    val messagesByConversation = mutableMapOf<Long, MutableList<SyncedMessage>>()
    val statesFlow = MutableStateFlow<Map<Long, MessageState>>(emptyMap())
    var maxId: Long = 0L

    /** When set, [conversations] suspends on this gate until the test completes it. */
    var conversationsGate: CompletableDeferred<Unit>? = null

    init {
        conversationsFlow.tryEmit(emptyList())
    }

    override suspend fun conversations(): List<SyncedConversation> {
        conversationsGate?.await()
        return snapshot
    }

    override fun observeConversations(): Flow<List<SyncedConversation>> = conversationsFlow

    override suspend fun messagesBefore(
        conversationId: Long,
        beforeId: Long,
        limit: Int,
    ): List<SyncedMessage> {
        val all = messagesByConversation[conversationId].orEmpty()
        return all.filter { beforeId == 0L || it.id.toLong() < beforeId }
            .sortedByDescending { it.id.toLong() }
            .take(limit)
    }

    override suspend fun messagesAfter(conversationId: Long, afterId: Long): List<SyncedMessage> =
        messagesByConversation[conversationId].orEmpty()
            .filter { it.id.toLong() > afterId }
            .sortedBy { it.id.toLong() }

    override suspend fun totalMessageCount(): Long =
        messagesByConversation.values.sumOf { it.size }.toLong()

    override suspend fun messageCountsByConversation(): Map<Long, Int> =
        messagesByConversation.mapValues { it.value.size }

    override suspend fun maxMessageId(): Long = maxId

    override fun observeMessageStates(ids: List<Long>): Flow<List<MessageState>> =
        statesFlow.map { states -> ids.mapNotNull { states[it] } }

    override suspend fun readiness(): Readiness = readiness

    /** Adds [message] to its conversation's store and advances the max id. */
    fun store(message: SyncedMessage) {
        val list = messagesByConversation.getOrPut(message.conversationId.toLong()) { mutableListOf() }
        list += message
        maxId = maxOf(maxId, message.id.toLong())
    }
}

/** A minimal conversation for tests; override what the test cares about. */
internal fun sampleConversation(
    id: Long,
    lastMessageAt: Long = 1_700_000_000_000L,
    lastMessagePreview: String? = "hello",
    unread: Int = 0,
    messageCount: Int = 0,
    title: String = "Marie",
): SyncedConversation = SyncedConversation(
    id = id.toString(),
    threadId = null,
    addresses = listOf("+15551234567"),
    displayName = title,
    customName = null,
    title = title,
    lastMessageAt = lastMessageAt,
    lastMessagePreview = lastMessagePreview,
    unread = unread,
    pinned = false,
    archived = false,
    muted = false,
    blocked = false,
    messageCount = messageCount,
    pinnedMessageId = null,
    pinnedMessageAt = null,
)

/** A minimal message for tests. */
internal fun sampleMessage(
    id: Long,
    conversationId: Long,
    body: String = "msg $id",
    date: Long = 1_700_000_000_000L + id,
    status: String = "received",
): SyncedMessage = SyncedMessage(
    id = id.toString(),
    conversationId = conversationId.toString(),
    address = "+15551234567",
    body = body,
    type = "sms",
    direction = "incoming",
    date = date,
    dateSent = null,
    read = false,
    starred = false,
    status = status,
    errorCode = null,
    replyToMessageId = null,
    reaction = null,
    attachments = emptyList(),
)
