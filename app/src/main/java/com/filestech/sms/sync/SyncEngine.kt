package com.filestech.sms.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Slice 2 — one session's sync engine: request handling, the initial
 * stream, and the live collectors. One engine per WebSocket session,
 * created by [SyncServer] and cancelled when the session ends.
 *
 * The engine never blocks the session's frame loop: [onSyncRequest]
 * and [onMessagesRequest] only start jobs; the stream runs in the
 * engine's own scope. Live push runs from [start] (auth) onwards,
 * concurrently with the initial stream — upserts by id make
 * duplicates harmless, so no mode switch is needed.
 *
 * The engine holds no state beyond the session: cursors live on the
 * desktop. The per-conversation "max sent id" tracking exists only
 * to keep the live path from re-pushing whole threads.
 *
 * @param source the message store, behind the [SyncMessageSource] seam.
 * @param parentScope the session's scope; the engine's jobs are
 * children of it, so a dead socket takes the engine with it.
 * @param send carries one outbound text frame to the client.
 */
class SyncEngine(
    private val source: SyncMessageSource,
    parentScope: CoroutineScope,
    private val send: suspend (String) -> Unit,
) {
    private val engineJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext + engineJob)

    private var streamJob: Job? = null
    private var started = false

    /** Last conversation snapshot the live collector saw (by id). */
    private var lastConversations: Map<String, SyncedConversation> = emptyMap()

    /** Per-conversation max message id pushed this session (live path). */
    private val maxSentByConversation = ConcurrentHashMap<Long, Long>()

    /** Baseline max id at collector start — live push only covers newer messages. */
    private var liveBaselineId: Long = 0L

    /** Ids of the most recent messages pushed this session (states watch). */
    private val watchedIds = MutableStateFlow<List<Long>>(emptyList())

    /** Last states the watch saw, by message id. */
    private var lastStates: Map<Long, MessageState> = emptyMap()

    /**
     * Starts the live collectors. Called once, when the session
     * authenticates. Idempotent — a second call is a no-op.
     */
    fun start() {
        if (started) return
        started = true
        scope.launch { collectConversations() }
        scope.launch { collectMessageStates() }
    }

    /**
     * Handles a `sync_request`: cancels any stream in progress and
     * starts a fresh one. Returns immediately — the stream runs in
     * the engine's scope, never on the frame loop. A failure mid-stream
     * answers `sync_error` (generic message, never a stack trace);
     * the socket stays up.
     */
    fun onSyncRequest(request: SyncProtocol.SyncRequest) {
        streamJob?.cancel()
        streamJob = scope.launch {
            try {
                runStream(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: sync stream failed")
                runCatching { send(syncErrorInternal()) }
            }
        }
    }

    /** Handles a `messages_request`: one page fetch, answered with one `messages_batch`. */
    fun onMessagesRequest(request: SyncProtocol.MessagesRequest) {
        scope.launch {
            try {
                runMessagesRequest(request)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                Timber.w(t, "SyncEngine: messages request failed")
                runCatching { send(syncErrorInternal()) }
            }
        }
    }

    /** Cancels the stream and both collectors. Called when the session ends. */
    fun stop() {
        streamJob?.cancel()
        streamJob = null
        engineJob.cancel()
    }

    private suspend fun runStream(request: SyncProtocol.SyncRequest) {
        if (request.mode != SyncProtocol.SyncRequest.FULL &&
            request.mode != SyncProtocol.SyncRequest.NEW_ONLY
        ) {
            send(SyncProtocol.encodeError("unknown sync mode: ${request.mode}"))
            return
        }
        when (source.readiness()) {
            Readiness.NoSmsPermission -> {
                send(noSmsAccessError())
                return
            }
            Readiness.MirrorEmpty -> {
                send(mirrorEmptyError())
                return
            }
            Readiness.Ready -> Unit
        }
        val sinceId = request.sinceMessageId?.toLongOrNull() ?: 0L
        val conversations = source.conversations()
        val totalMessages = source.totalMessageCount()
        val cursor = source.maxMessageId()
        send(
            SyncProtocol.encodeSyncStarted(
                SyncProtocol.SyncStarted(
                    mode = request.mode,
                    totalConversations = conversations.size,
                    totalMessages = totalMessages,
                    cursorMessageId = cursor.toString(),
                ),
            ),
        )
        streamConversations(conversations)
        if (request.mode == SyncProtocol.SyncRequest.FULL) {
            if (sinceId > 0L) streamDelta(conversations, sinceId) else streamAllMessages(conversations)
        }
        val endCursor = source.maxMessageId()
        send(
            SyncProtocol.encodeSyncComplete(
                SyncProtocol.SyncComplete(cursorMessageId = endCursor.toString()),
            ),
        )
    }

    private suspend fun streamConversations(conversations: List<SyncedConversation>) {
        if (conversations.isEmpty()) {
            // Even with nothing to sync, the desktop needs the frame:
            // `has_more: false` is what tells it the list is complete.
            send(
                SyncProtocol.encodeConversations(
                    SyncProtocol.Conversations(conversations = emptyList(), hasMore = false),
                ),
            )
            return
        }
        val batches = conversations.chunked(CONVERSATION_BATCH_SIZE)
        batches.forEachIndexed { index, batch ->
            send(
                SyncProtocol.encodeConversations(
                    SyncProtocol.Conversations(
                        conversations = batch,
                        hasMore = index < batches.lastIndex,
                    ),
                ),
            )
        }
    }

    /** Full mode: every conversation's messages, newest first, most-recent conversation first. */
    private suspend fun streamAllMessages(conversations: List<SyncedConversation>) {
        for (conversation in conversations) {
            streamOneConversation(conversation)
        }
    }

    private suspend fun streamOneConversation(conversation: SyncedConversation) {
        val conversationId = conversation.id.toLongOrNull() ?: return
        var beforeId: Long? = 0L
        while (beforeId != null) {
            beforeId = streamOnePage(conversation, conversationId, beforeId)
        }
    }

    /**
     * Streams one page of [conversation] below [beforeId]. Returns the
     * next `before_id`, or null when the thread is exhausted.
     */
    private suspend fun streamOnePage(
        conversation: SyncedConversation,
        conversationId: Long,
        beforeId: Long,
    ): Long? {
        val page = source.messagesBefore(conversationId, beforeId, MESSAGE_BATCH_SIZE)
        if (page.isEmpty()) return null
        val hasMore = page.size == MESSAGE_BATCH_SIZE
        val lastId = page.last().id
        send(
            SyncProtocol.encodeMessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = conversation.id,
                    messages = page,
                    hasMore = hasMore,
                    nextBeforeId = lastId.takeIf { hasMore },
                ),
            ),
        )
        notePushed(conversationId, page)
        return lastId.toLongOrNull().takeIf { hasMore }
    }

    /**
     * Reconnect delta: only the messages above [sinceId], per
     * conversation, oldest first. The batches carry `has_more: false`
     * — there is no `before_id` continuation for a delta range; the
     * desktop gap-fills older history via `messages_request` instead.
     */
    private suspend fun streamDelta(conversations: List<SyncedConversation>, sinceId: Long) {
        for (conversation in conversations) {
            streamConversationDelta(conversation, sinceId)
        }
    }

    private suspend fun streamConversationDelta(conversation: SyncedConversation, sinceId: Long) {
        val conversationId = conversation.id.toLongOrNull() ?: return
        val delta = source.messagesAfter(conversationId, sinceId)
        if (delta.isEmpty()) return
        delta.chunked(MESSAGE_BATCH_SIZE).forEach { chunk ->
            send(
                SyncProtocol.encodeMessagesBatch(
                    SyncProtocol.MessagesBatch(
                        conversationId = conversation.id,
                        messages = chunk,
                        hasMore = false,
                    ),
                ),
            )
        }
        notePushed(conversationId, delta)
    }

    private suspend fun runMessagesRequest(request: SyncProtocol.MessagesRequest) {
        val conversationId = request.conversationId.toLongOrNull()
        if (conversationId == null) {
            send(SyncProtocol.encodeError("unknown conversation: ${request.conversationId}"))
            return
        }
        val known = source.conversations().any { it.id == request.conversationId }
        if (!known) {
            send(SyncProtocol.encodeError("unknown conversation: ${request.conversationId}"))
            return
        }
        val beforeId = request.beforeId?.toLongOrNull() ?: 0L
        val limit = (request.limit ?: MESSAGE_BATCH_SIZE).coerceIn(1, MESSAGE_BATCH_SIZE)
        val page = source.messagesBefore(conversationId, beforeId, limit)
        val hasMore = page.size == limit
        val lastId = page.lastOrNull()?.id
        send(
            SyncProtocol.encodeMessagesBatch(
                SyncProtocol.MessagesBatch(
                    conversationId = request.conversationId,
                    messages = page,
                    hasMore = hasMore,
                    nextBeforeId = lastId.takeIf { hasMore },
                ),
            ),
        )
        notePushed(conversationId, page)
    }

    /**
     * Records pushed messages: advances the live path's
     * per-conversation max id (so the conversation collector never
     * re-pushes them) and slides the states watch over the most
     * recent ids.
     */
    private fun notePushed(conversationId: Long, messages: List<SyncedMessage>) {
        if (messages.isEmpty()) return
        val ids = messages.mapNotNull { it.id.toLongOrNull() }
        val maxId = ids.maxOrNull() ?: return
        maxSentByConversation.compute(conversationId) { _, previous ->
            maxOf(previous ?: liveBaselineId, maxId)
        }
        watchedIds.update { current -> (current + ids).distinct().takeLast(WATCHED_STATES_LIMIT) }
    }

    /**
     * Conversation collector: diffs each list emission against the
     * last snapshot and pushes what changed. The first emission is
     * the baseline — silent, it only seeds the per-conversation max
     * ids (the initial stream sends the snapshot itself).
     *
     * The diff compares whole DTOs: draft keystrokes and appearance
     * changes rewrite the conversation row and re-emit the Flow, but
     * neither field is in the DTO, so equal DTOs mean nothing
     * sync-relevant changed and nothing is pushed.
     */
    private suspend fun collectConversations() {
        liveBaselineId = runCatching { source.maxMessageId() }.getOrDefault(0L)
        var firstEmission = true
        source.observeConversations().collect { list ->
            val snapshot = list.associateBy { it.id }
            if (firstEmission) {
                firstEmission = false
                baselineMaxSent(list)
            } else {
                pushConversationDiff(lastConversations, snapshot, list)
            }
            lastConversations = snapshot
        }
    }

    /** Seeds the live path's per-conversation max ids at the session baseline. */
    private fun baselineMaxSent(list: List<SyncedConversation>) {
        for (conversation in list) {
            conversation.id.toLongOrNull()?.let { id ->
                maxSentByConversation.putIfAbsent(id, liveBaselineId)
            }
        }
    }

    private suspend fun pushConversationDiff(
        previous: Map<String, SyncedConversation>,
        snapshot: Map<String, SyncedConversation>,
        list: List<SyncedConversation>,
    ) {
        for (id in previous.keys - snapshot.keys) {
            send(
                SyncProtocol.encodeConversationDeleted(
                    SyncProtocol.ConversationDeleted(conversationId = id),
                ),
            )
            id.toLongOrNull()?.let { maxSentByConversation.remove(it) }
        }
        for (conversation in list) {
            val old = previous[conversation.id]
            val conversationId = conversation.id.toLongOrNull()
            when {
                old == null -> {
                    if (conversationId != null) {
                        maxSentByConversation.putIfAbsent(conversationId, liveBaselineId)
                    }
                    send(
                        SyncProtocol.encodeConversationUpdated(
                            SyncProtocol.ConversationUpdated(conversation),
                        ),
                    )
                }
                conversation != old -> {
                    send(
                        SyncProtocol.encodeConversationUpdated(
                            SyncProtocol.ConversationUpdated(conversation),
                        ),
                    )
                    val hasNewMessages = conversation.lastMessageAt > old.lastMessageAt ||
                        conversation.messageCount > old.messageCount
                    if (conversationId != null && hasNewMessages) {
                        pushNewMessages(conversationId)
                    }
                }
            }
        }
    }

    /** Pushes the messages that arrived in [conversationId] since the last push, oldest first. */
    private suspend fun pushNewMessages(conversationId: Long) {
        val afterId = maxSentByConversation[conversationId] ?: liveBaselineId
        val fresh = source.messagesAfter(conversationId, afterId)
        if (fresh.isEmpty()) return
        for (message in fresh) {
            send(SyncProtocol.encodeMessageNew(SyncProtocol.MessageNew(message)))
        }
        notePushed(conversationId, fresh)
    }

    /**
     * States watch: a bounded Flow over the ids of the most recent
     * messages pushed this session, re-subscribed via `flatMapLatest`
     * as the id set slides. Any emission differing from the last-sent
     * states (status, reaction, read, starred) pushes a
     * `message_updated` with only the changed fields. A first sighting
     * of an id is the baseline — recorded, not pushed.
     */
    private suspend fun collectMessageStates() {
        watchedIds.flatMapLatest { ids ->
            if (ids.isEmpty()) flowOf(emptyList()) else source.observeMessageStates(ids)
        }.collect { states ->
            for (state in states) {
                val old = lastStates[state.id]
                if (old != null && old != state) {
                    send(buildMessageUpdated(old, state))
                }
            }
            lastStates = states.associateBy { it.id }
        }
    }

    /** Builds the partial `message_updated` frame: only changed fields are present. */
    private fun buildMessageUpdated(old: MessageState, new: MessageState): String {
        val updated = SyncProtocol.MessageUpdated(
            messageId = new.id.toString(),
            conversationId = new.conversationId.toString(),
            status = new.status.takeIf { it != old.status },
            reaction = new.reaction.takeIf { it != old.reaction },
            reactionRemoved = old.reaction != null && new.reaction == null,
            read = new.read.takeIf { it != old.read },
            starred = new.starred.takeIf { it != old.starred },
        )
        return SyncProtocol.encodeMessageUpdated(updated)
    }

    private fun noSmsAccessError(): String =
        SyncProtocol.encodeSyncError(
            SyncProtocol.SyncError(
                code = SyncProtocol.SyncError.HUB_NO_SMS_ACCESS,
                message = "Relay on the phone needs SMS permission to sync messages.",
            ),
        )

    private fun mirrorEmptyError(): String =
        SyncProtocol.encodeSyncError(
            SyncProtocol.SyncError(
                code = SyncProtocol.SyncError.HUB_MIRROR_EMPTY,
                message = "Relay on the phone hasn't imported any messages yet.",
            ),
        )

    private fun syncErrorInternal(): String =
        SyncProtocol.encodeSyncError(
            SyncProtocol.SyncError(
                code = SyncProtocol.SyncError.INTERNAL,
                message = "Sync failed on the phone. Try again.",
            ),
        )

    companion object {
        /** Conversations per `conversations` batch. */
        const val CONVERSATION_BATCH_SIZE = 100

        /** Messages per `messages_batch` page. */
        const val MESSAGE_BATCH_SIZE = 200

        /** How many recent message ids the states watch follows. */
        const val WATCHED_STATES_LIMIT = 200
    }
}
