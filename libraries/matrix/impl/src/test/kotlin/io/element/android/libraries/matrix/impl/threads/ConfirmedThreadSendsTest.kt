/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.impl.fixtures.fakes.FakeFfiTaskHandle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.AudioMessageContent
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.FileMessageContent
import org.matrix.rustcomponents.sdk.MediaSource
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.QueueWedgeError
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueRoomUpdateListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.TimelineEvent
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.UnstableVoiceContent
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmedThreadSendsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = UserId("@alice:example.org")
    private val roomId = "!room:example.org"
    private fun key(root: String) = ThreadKey(account, RoomId(roomId), EventId(root))

    private fun event(
        root: String? = "$" + "root",
        sender: String = account.value,
        type: MessageType = MessageType.Text(TextMessageContent("not persisted", null)),
    ) = object : TimelineEvent(NoHandle) {
        override fun threadRootEventId() = root
        override fun senderId() = sender
        override fun content() = TimelineEventContent.MessageLike(MessageLikeEventContent.RoomMessage(type, null))
        override fun close() = Unit
    }

    private inner class Observer(scope: TestScope, val name: String = "account") {
        val pendingFile = File(temporary.root, "$name.pending")
        val recentFile = File(temporary.root, "$name.recent")
        val pending = PendingThreadSends(pendingFile, account.value)
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val recent = FileRecentThreads(account, recentFile, dispatcher, pending)
        lateinit var listener: SendQueueRoomUpdateListener
        var lookup: suspend (String) -> TimelineEvent = { event() }
        var removedRoom: String? = null
        var lookups = 0
        private val room = object : Room(NoHandle) {
            override suspend fun loadOrFetchEvent(eventId: String): TimelineEvent {
                lookups++
                return lookup(eventId)
            }
            override fun close() = Unit
        }
        private val client = object : Client(NoHandle) {
            override suspend fun subscribeToSendQueueUpdates(listener: SendQueueRoomUpdateListener): TaskHandle {
                this@Observer.listener = listener
                return FakeFfiTaskHandle()
            }
            override fun getRoom(roomId: String): Room? = if (roomId == removedRoom) null else room
        }
        val job = observeConfirmedThreadSends(client, account, recent, pending, scope.backgroundScope, dispatcher)
        fun sent(id: String, room: String = roomId) = listener.onUpdate(room, RoomSendQueueUpdate.SentEvent("txn$id", id))
    }

    @Test fun `logout barrier prevents suspended lookup and late callback from recreating files`() = runTest {
        val observer = Observer(this)
        observer.lookup = { awaitCancellation() }
        runCurrent()
        observer.sent("$" + "before-logout")
        runCurrent()
        // Production destroy waits for the observer, then seals both writers before removing session files.
        observer.job.cancel()
        observer.job.join()
        observer.recent.close()
        assertThat(observer.pendingFile.delete()).isTrue()
        observer.recentFile.delete()
        observer.sent("$" + "late-native-callback")
        assertThat(runCatching { observer.recent.recordOpened(key("$" + "late-view")) }.isFailure).isTrue()
        assertThat(runCatching { observer.recent.clear() }.isFailure).isTrue()
        runCurrent()
        assertThat(observer.pendingFile.exists()).isFalse()
        assertThat(observer.recentFile.exists()).isFalse()
        assertThat(observer.recent.entries.value).isEmpty()
    }

    @Test fun `production observer records text voice and file only after server confirmation`() = runTest {
        val observer = Observer(this)
        runCurrent()
        val source = object : MediaSource(NoHandle) {
            override fun close() = Unit
        }
        val types = listOf(
            MessageType.Text(TextMessageContent("private text", null)),
            MessageType.Audio(AudioMessageContent("voice.ogg", null, null, source, null, null, UnstableVoiceContent())),
            MessageType.File(FileMessageContent("private.pdf", "private caption", null, source, null)),
        )
        for ((index, type) in types.withIndex()) {
            val before = observer.recent.entries.value
            observer.lookup = { event(root = "$" + "root$index", type = type) }
            observer.listener.onUpdate(roomId, RoomSendQueueUpdate.NewLocalEvent("txn"))
            observer.listener.onUpdate(roomId, RoomSendQueueUpdate.SendError("txn", QueueWedgeError.GenericApiError("offline"), true))
            observer.listener.onUpdate(roomId, RoomSendQueueUpdate.RetryEvent("txn"))
            observer.listener.onUpdate(roomId, RoomSendQueueUpdate.CancelledLocalEvent("txn"))
            runCurrent()
            assertThat(observer.recent.entries.value).isEqualTo(before)
            assertThat(observer.pending.first()).isNull()
            observer.sent("$" + "sent$index")
            runCurrent()
            assertThat(observer.recent.entries.value.first().key).isEqualTo(key("$" + "root$index"))
            assertThat(observer.recent.entries.value.first().openedSequence).isEqualTo(0)
        }
        assertThat(observer.lookups).isEqualTo(3)
        assertThat(observer.recentFile.readText()).doesNotContain("private")
        assertThat(observer.pendingFile.readText()).doesNotContain("private")
    }

    @Test fun `production callback burst is bounded and retains newest confirmations in order`() = runTest {
        val observer = Observer(this)
        observer.lookup = { event(root = it) }
        runCurrent()
        repeat(350) { observer.sent("$" + "event$it") }
        assertThat(observer.pending.snapshot()).hasSize(100)
        runCurrent()
        assertThat(observer.lookups).isEqualTo(100)
        assertThat(observer.recent.entries.value).hasSize(100)
        assertThat(observer.recent.entries.value.first().key).isEqualTo(key("$" + "event349"))
        assertThat(observer.recent.entries.value.last().key).isEqualTo(key("$" + "event250"))
    }

    @Test fun `permanent forbidden not found and removed rooms do not block later success`() = runTest {
        val observer = Observer(this)
        observer.removedRoom = "!removed:example.org"
        observer.lookup = {
            when (it) {
                "$" + "forbidden" -> throw ClientException.MatrixApi(ErrorKind.Forbidden, "M_FORBIDDEN", "denied", "")
                "$" + "missing" -> throw ClientException.MatrixApi(ErrorKind.NotFound, "M_NOT_FOUND", "gone", "")
                else -> event()
            }
        }
        runCurrent()
        observer.sent("$" + "removed", observer.removedRoom!!)
        observer.sent("$" + "forbidden")
        observer.sent("$" + "missing")
        observer.sent("$" + "valid")
        runCurrent()
        assertThat(observer.recent.entries.value.single().confirmedEventId).isEqualTo(EventId("$" + "valid"))
        assertThat(observer.pending.first()).isNull()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(observer.lookups).isEqualTo(3)
    }

    @Test fun `hanging first lookup times out and later confirmation progresses`() = runTest {
        val observer = Observer(this)
        observer.lookup = { if (it == "$" + "stuck") awaitCancellation() else event() }
        runCurrent()
        observer.sent("$" + "stuck")
        observer.sent("$" + "valid")
        runCurrent()
        assertThat(observer.recent.entries.value).isEmpty()
        advanceTimeBy(PendingThreadSends.LOOKUP_TIMEOUT_MILLIS)
        runCurrent()
        assertThat(observer.recent.entries.value.single().confirmedEventId).isEqualTo(EventId("$" + "valid"))
        assertThat(observer.pending.snapshot().map { it.event }).containsExactly("$" + "stuck")
    }

    @Test fun `transient retry after restart keeps callback order not lookup completion order`() = runTest {
        val first = Observer(this)
        first.lookup = { if (it == "$" + "older") error("offline") else event(root = "$" + "new-root") }
        runCurrent()
        first.sent("$" + "older")
        first.sent("$" + "newer")
        runCurrent()
        val newest = first.recent.entries.value.single()
        first.recent.recordOpened(key("$" + "opened"))
        first.job.cancel()
        runCurrent()
        val restarted = Observer(this)
        restarted.lookup = { event(root = "$" + "old-root") }
        runCurrent()
        val rows = restarted.recent.entries.value
        assertThat(rows.maxBy { it.messagedSequence }).isEqualTo(newest)
        assertThat(rows.first().key).isEqualTo(key("$" + "opened"))
        assertThat(rows.map { it.key }).contains(key("$" + "old-root"))
        assertThat(restarted.pending.first()).isNull()
        restarted.sent("$" + "older")
        restarted.sent("$" + "newer")
        runCurrent()
        assertThat(restarted.recent.entries.value).isEqualTo(rows)
    }

    @Test fun `delayed older event cannot overwrite newer event on the same root`() = runTest {
        val observer = Observer(this)
        var fail = true
        observer.lookup = { if (it == "$" + "older" && fail) error("offline") else event() }
        runCurrent()
        observer.sent("$" + "older")
        observer.sent("$" + "newer")
        runCurrent()
        val newest = observer.recent.entries.value.single()
        fail = false
        advanceTimeBy(PendingThreadSends.RETRY_MILLIS)
        runCurrent()
        assertThat(observer.recent.entries.value.single()).isEqualTo(newest)
        assertThat(observer.pending.first()).isNull()
    }

    @Test fun `clear during actual suspended lookup survives completion replay and restart`() = runTest {
        val observer = Observer(this)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        observer.lookup = {
            entered.complete(Unit)
            finish.await()
            event()
        }
        runCurrent()
        observer.sent("$" + "before-clear")
        runCurrent()
        assertThat(entered.isCompleted).isTrue()
        observer.recent.clear()
        assertThat(observer.pendingFile.readText()).doesNotContain("before-clear")
        assertThat(observer.pendingFile.readText()).doesNotContain("!room")
        finish.complete(Unit)
        runCurrent()
        assertThat(observer.recent.entries.value).isEmpty()
        observer.job.cancel()
        runCurrent()
        val restarted = Observer(this)
        runCurrent()
        restarted.sent("$" + "before-clear")
        runCurrent()
        assertThat(restarted.recent.entries.value).isEmpty()
        restarted.sent("$" + "after-clear")
        runCurrent()
        assertThat(restarted.recent.entries.value.single().confirmedEventId).isEqualTo(EventId("$" + "after-clear"))
    }

    @Test fun `other sender and non thread confirmations do not move recent`() = runTest {
        val observer = Observer(this)
        runCurrent()
        observer.sent("$" + "valid")
        runCurrent()
        val before = observer.recent.entries.value
        observer.lookup = { event(sender = "@bob:example.org") }
        observer.sent("$" + "other-account")
        runCurrent()
        observer.lookup = { event(root = null) }
        observer.sent("$" + "not-a-thread")
        runCurrent()
        assertThat(observer.recent.entries.value).isEqualTo(before)
        assertThat(observer.pending.first()).isNull()
    }
}
