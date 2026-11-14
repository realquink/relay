package com.filestech.sms.sync

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test

/**
 * Slice 2 — the sync engine, driven without a socket: a fake
 * [SyncMessageSource] holds the conversations and messages, and the
 * harness records every outbound frame. This is the contract the
 * desktop syncs against, so the frame order, the paging cursors, the
 * live diff and the readiness errors are all pinned here.
 */
class SyncEngineTest {

    @Test fun `full sync streams conversations then messages then complete`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(
            sampleConversation(id = 1, lastMessageAt = 2000, messageCount = 3),
            sampleConversation(id = 2, lastMessageAt = 1000, messageCount = 1),
        )
        source.store(sampleMessage(id = 11, conversationId = 1))
        source.store(sampleMessage(id = 12, conversationId = 1))
        source.store(sampleMessage(id = 13, conversationId = 1))
        source.store(sampleMessage(id = 21, conversationId = 2))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly(
                "sync_started",
                "conversations",
                "messages_batch",
                "messages_batch",
                "sync_complete",
            ).inOrder()

            val started = sent.first { it.frameType() == "sync_started" }.asObject()
            assertThat(started["mode"]?.jsonPrimitive?.content).isEqualTo("full")
            assertThat(started["total_conversations"]?.jsonPrimitive?.content).isEqualTo("2")
            assertThat(started["total_messages"]?.jsonPrimitive?.content).isEqualTo("4")
            assertThat(started["cursor_message_id"]?.jsonPrimitive?.content).isEqualTo("21")

            val conversations = sent.first { it.frameType() == "conversations" }.asObject()
            val ids = conversations["conversations"]!!.jsonArray
                .map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertThat(ids).containsExactly("1", "2").inOrder()
            assertThat(conversations["has_more"]?.jsonPrimitive?.content).isEqualTo("false")

            val batches = sent.filter { it.frameType() == "messages_batch" }.map { it.asObject() }
            assertThat(batches[0]["conversation_id"]?.jsonPrimitive?.content).isEqualTo("1")
            val batchIds = batches[0]["messages"]!!.jsonArray
                .map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertThat(batchIds).containsExactly("13", "12", "11").inOrder()
            assertThat(batches[0]["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(batches[1]["conversation_id"]?.jsonPrimitive?.content).isEqualTo("2")

            val complete = sent.first { it.frameType() == "sync_complete" }.asObject()
            assertThat(complete["cursor_message_id"]?.jsonPrimitive?.content).isEqualTo("21")
        }
    }

    @Test fun `full sync pages a long thread at 200`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 250))
        for (id in 1..250) source.store(sampleMessage(id = id.toLong(), conversationId = 1))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val batches = sent.filter { it.frameType() == "messages_batch" }.map { it.asObject() }
            assertThat(batches).hasSize(2)
            assertThat(batches[0]["messages"]!!.jsonArray).hasSize(200)
            assertThat(batches[0]["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(batches[0]["next_before_id"]?.jsonPrimitive?.content).isEqualTo("51")
            assertThat(batches[1]["messages"]!!.jsonArray).hasSize(50)
            assertThat(batches[1]["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(batches[1].containsKey("next_before_id")).isFalse()
        }
    }

    @Test fun `new_only sync skips messages and reports the now cursor`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 3))
        source.store(sampleMessage(id = 11, conversationId = 1))
        source.store(sampleMessage(id = 12, conversationId = 1))
        source.store(sampleMessage(id = 13, conversationId = 1))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "new_only"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly(
                "sync_started",
                "conversations",
                "sync_complete",
            ).inOrder()
            val started = sent.first { it.frameType() == "sync_started" }.asObject()
            assertThat(started["mode"]?.jsonPrimitive?.content).isEqualTo("new_only")
            assertThat(started["cursor_message_id"]?.jsonPrimitive?.content).isEqualTo("13")
        }
    }

    @Test fun `delta sync sends only messages above the cursor`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 5))
        for (id in 1..5) source.store(sampleMessage(id = id.toLong(), conversationId = 1))
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full", sinceMessageId = "2"))
            advanceUntilIdle()
            val batches = sent.filter { it.frameType() == "messages_batch" }.map { it.asObject() }
            assertThat(batches).hasSize(1)
            val batchIds = batches[0]["messages"]!!.jsonArray
                .map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertThat(batchIds).containsExactly("3", "4", "5").inOrder()
            assertThat(batches[0]["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
            assertThat(sent.map { it.frameType() }).contains("sync_complete")
        }
    }

    @Test fun `messages_request answers one page and pages with before_id`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 3))
        source.store(sampleMessage(id = 11, conversationId = 1))
        source.store(sampleMessage(id = 12, conversationId = 1))
        source.store(sampleMessage(id = 13, conversationId = 1))
        runEngine(source) { engine, sent ->
            engine.onMessagesRequest(
                SyncProtocol.MessagesRequest(conversationId = "1", beforeId = "0", limit = 2),
            )
            advanceUntilIdle()
            val batches = sent.filter { it.frameType() == "messages_batch" }.map { it.asObject() }
            assertThat(batches).hasSize(1)
            val batchIds = batches[0]["messages"]!!.jsonArray
                .map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertThat(batchIds).containsExactly("13", "12").inOrder()
            assertThat(batches[0]["has_more"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(batches[0]["next_before_id"]?.jsonPrimitive?.content).isEqualTo("12")

            engine.onMessagesRequest(
                SyncProtocol.MessagesRequest(conversationId = "1", beforeId = "12", limit = 2),
            )
            advanceUntilIdle()
            val second = sent.filter { it.frameType() == "messages_batch" }.map { it.asObject() }
            assertThat(second).hasSize(2)
            val secondIds = second[1]["messages"]!!.jsonArray
                .map { it.jsonObject["id"]?.jsonPrimitive?.content }
            assertThat(secondIds).containsExactly("11")
            assertThat(second[1]["has_more"]?.jsonPrimitive?.content).isEqualTo("false")
        }
    }

    @Test fun `messages_request for an unknown conversation answers error`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1))
        runEngine(source) { engine, sent ->
            engine.onMessagesRequest(SyncProtocol.MessagesRequest(conversationId = "999"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("error")
        }
    }

    @Test fun `unknown sync mode answers error`() = runTest {
        val source = FakeSyncMessageSource()
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "sideways"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("error")
        }
    }

    @Test fun `no sms permission answers sync_error hub_no_sms_access`() = runTest {
        val source = FakeSyncMessageSource()
        source.readiness = Readiness.NoSmsPermission
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("sync_error")
            val error = sent.first().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.SyncError.HUB_NO_SMS_ACCESS)
        }
    }

    @Test fun `empty mirror answers sync_error hub_mirror_empty`() = runTest {
        val source = FakeSyncMessageSource()
        source.readiness = Readiness.MirrorEmpty
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("sync_error")
            val error = sent.first().asObject()
            assertThat(error["code"]?.jsonPrimitive?.content)
                .isEqualTo(SyncProtocol.SyncError.HUB_MIRROR_EMPTY)
        }
    }

    @Test fun `live push sends conversation_updated and message_new for a new message`() = runTest {
        val source = FakeSyncMessageSource()
        val conv = sampleConversation(id = 1, lastMessageAt = 1000, messageCount = 1)
        source.store(sampleMessage(id = 11, conversationId = 1))
        assertThat(source.conversationsFlow.tryEmit(listOf(conv))).isTrue()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            source.store(sampleMessage(id = 12, conversationId = 1, body = "fresh"))
            val updated = conv.copy(
                lastMessageAt = 2000,
                lastMessagePreview = "fresh",
                unread = 1,
                messageCount = 2,
            )
            assertThat(source.conversationsFlow.tryEmit(listOf(updated))).isTrue()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() })
                .containsExactly("conversation_updated", "message_new").inOrder()
            val newMessage = sent.first { it.frameType() == "message_new" }.asObject()
            assertThat(newMessage["message"]!!.jsonObject["id"]?.jsonPrimitive?.content)
                .isEqualTo("12")
            assertThat(newMessage["message"]!!.jsonObject["body"]?.jsonPrimitive?.content)
                .isEqualTo("fresh")
        }
    }

    @Test fun `live push sends conversation_deleted when a conversation disappears`() = runTest {
        val source = FakeSyncMessageSource()
        val convA = sampleConversation(id = 1, lastMessageAt = 2000)
        val convB = sampleConversation(id = 2, lastMessageAt = 1000, title = "Curt")
        assertThat(source.conversationsFlow.tryEmit(listOf(convA, convB))).isTrue()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            assertThat(source.conversationsFlow.tryEmit(listOf(convA))).isTrue()
            advanceUntilIdle()
            assertThat(sent.map { it.frameType() }).containsExactly("conversation_deleted")
            val deleted = sent.first().asObject()
            assertThat(deleted["conversation_id"]?.jsonPrimitive?.content).isEqualTo("2")
        }
    }

    @Test fun `an identical list emission sends nothing`() = runTest {
        val source = FakeSyncMessageSource()
        val conv = sampleConversation(id = 1, lastMessageAt = 1000, messageCount = 1)
        assertThat(source.conversationsFlow.tryEmit(listOf(conv))).isTrue()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            // An equal list, re-emitted (a draft keystroke re-emits the
            // Room flow upstream; the DTOs carry no draft, so the diff
            // must stay silent).
            assertThat(source.conversationsFlow.tryEmit(listOf(conv.copy()))).isTrue()
            advanceUntilIdle()
            assertThat(sent).isEmpty()
        }
    }

    @Test fun `states watch sends message_updated with only the changed field`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 1))
        source.store(sampleMessage(id = 11, conversationId = 1, status = "pending"))
        source.statesFlow.value = mapOf(
            11L to MessageState(
                id = 11,
                conversationId = 1,
                status = "pending",
                reaction = null,
                read = false,
                starred = false,
            ),
        )
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            // Push the message through the stream so the watch follows it.
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val before = sent.size

            source.statesFlow.value = mapOf(
                11L to MessageState(
                    id = 11,
                    conversationId = 1,
                    status = "delivered",
                    reaction = null,
                    read = false,
                    starred = false,
                ),
            )
            advanceUntilIdle()
            val updates = sent.drop(before).filter { it.frameType() == "message_updated" }
            assertThat(updates).hasSize(1)
            val update = updates.first().asObject()
            assertThat(update["message_id"]?.jsonPrimitive?.content).isEqualTo("11")
            assertThat(update["conversation_id"]?.jsonPrimitive?.content).isEqualTo("1")
            assertThat(update["status"]?.jsonPrimitive?.content).isEqualTo("delivered")
            assertThat(update.containsKey("reaction")).isFalse()
            assertThat(update.containsKey("reaction_removed")).isFalse()
            assertThat(update.containsKey("read")).isFalse()
            assertThat(update.containsKey("starred")).isFalse()
        }
    }

    @Test fun `states watch reports a removed reaction`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1, messageCount = 1))
        source.store(sampleMessage(id = 11, conversationId = 1))
        source.statesFlow.value = mapOf(
            11L to MessageState(
                id = 11,
                conversationId = 1,
                status = "received",
                reaction = "❤️",
                read = false,
                starred = false,
            ),
        )
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            val before = sent.size

            source.statesFlow.value = mapOf(
                11L to MessageState(
                    id = 11,
                    conversationId = 1,
                    status = "received",
                    reaction = null,
                    read = false,
                    starred = false,
                ),
            )
            advanceUntilIdle()
            val updates = sent.drop(before).filter { it.frameType() == "message_updated" }
            assertThat(updates).hasSize(1)
            val update = updates.first().asObject()
            assertThat(update.containsKey("reaction")).isFalse()
            assertThat(update["reaction_removed"]?.jsonPrimitive?.content).isEqualTo("true")
            assertThat(update.containsKey("status")).isFalse()
        }
    }

    @Test fun `a second sync_request cancels the first stream`() = runTest {
        val source = FakeSyncMessageSource()
        source.snapshot = listOf(sampleConversation(id = 1))
        val gate = CompletableDeferred<Unit>()
        source.conversationsGate = gate
        runEngine(source) { engine, sent ->
            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            assertThat(sent).isEmpty()

            engine.onSyncRequest(SyncProtocol.SyncRequest(mode = "full"))
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(sent.filter { it.frameType() == "sync_started" }).hasSize(1)
            assertThat(sent.filter { it.frameType() == "sync_complete" }).hasSize(1)
        }
    }

    @Test fun `stop cancels the collectors`() = runTest {
        val source = FakeSyncMessageSource()
        val conv = sampleConversation(id = 1, lastMessageAt = 1000)
        assertThat(source.conversationsFlow.tryEmit(listOf(conv))).isTrue()
        runEngine(source) { engine, sent ->
            engine.start()
            advanceUntilIdle()
            engine.stop()
            advanceUntilIdle()

            assertThat(source.conversationsFlow.tryEmit(listOf(conv.copy(unread = 3)))).isTrue()
            advanceUntilIdle()
            assertThat(sent).isEmpty()
        }
    }

    /**
     * Builds an engine on the test scope and guarantees it is stopped
     * afterwards — the live collectors run forever, so a test that
     * leaks one would hang `runTest` until its timeout.
     */
    private suspend fun TestScope.runEngine(
        source: FakeSyncMessageSource,
        block: suspend TestScope.(SyncEngine, MutableList<String>) -> Unit,
    ) {
        val sent = mutableListOf<String>()
        val engine = SyncEngine(source, this, send = { sent += it })
        try {
            block(engine, sent)
        } finally {
            engine.stop()
            advanceUntilIdle()
        }
    }

    private fun String.frameType(): String? =
        Json.parseToJsonElement(this).jsonObject["type"]?.jsonPrimitive?.content

    private fun String.asObject(): JsonObject = Json.parseToJsonElement(this).jsonObject
}
