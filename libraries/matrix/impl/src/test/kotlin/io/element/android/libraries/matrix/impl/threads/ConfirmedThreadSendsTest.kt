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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.MessageType
import org.matrix.rustcomponents.sdk.NoHandle
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueRoomUpdateListener
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.TextMessageContent
import org.matrix.rustcomponents.sdk.TimelineEvent
import org.matrix.rustcomponents.sdk.TimelineEventContent
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ConfirmedThreadSendsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = UserId("@alice:example.org")
    private val key = ThreadKey(account, RoomId("!room:example.org"), EventId("$" + "root"))

    @Test fun `production observer ignores enqueue errors and persists only confirmed relation with retry`() = runTest {
        lateinit var listener: SendQueueRoomUpdateListener
        var failures = 4
        val event = object : TimelineEvent(NoHandle) {
            override fun threadRootEventId() = key.rootEventId.value
            override fun senderId() = account.value
            override fun content() = TimelineEventContent.MessageLike(
                MessageLikeEventContent.RoomMessage(MessageType.Text(TextMessageContent("test", null)), null),
            )
            override fun close() = Unit
        }
        val room = object : Room(NoHandle) {
            override suspend fun loadOrFetchEvent(eventId: String): TimelineEvent {
                if (failures-- > 0) error("Transient lookup failure")
                return event
            }
            override fun close() = Unit
        }
        val client = object : Client(NoHandle) {
            override suspend fun subscribeToSendQueueUpdates(callback: SendQueueRoomUpdateListener): TaskHandle {
                listener = callback
                return FakeFfiTaskHandle()
            }
            override fun getRoom(roomId: String): Room = room
        }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingThreadSends(File(temporary.root, "pending"))
        val recent = FileRecentThreads(account, File(temporary.root, "recent"), dispatcher, pending)
        observeConfirmedThreadSends(client, account, recent, pending, backgroundScope, dispatcher)
        runCurrent()
        listener.onUpdate(key.roomId.value, RoomSendQueueUpdate.NewLocalEvent("txn"))
        listener.onUpdate(key.roomId.value, RoomSendQueueUpdate.CancelledLocalEvent("txn"))
        runCurrent()
        assertThat(recent.entries.value).isEmpty()
        listener.onUpdate(key.roomId.value, RoomSendQueueUpdate.SentEvent("txn", "$" + "sent"))
        runCurrent()
        assertThat(pending.first()).isNotNull()
        assertThat(recent.entries.value).isEmpty()
        advanceTimeBy(60_000)
        runCurrent()
        assertThat(recent.entries.value.single().key).isEqualTo(key)
        assertThat(recent.entries.value.single().openedSequence).isEqualTo(0)
        assertThat(pending.first()).isNull()
    }

    @Test fun `pending confirmation survives restart and literal delimiter identifiers round trip`() = runTest {
        val file = File(temporary.root, "pending")
        val entry = PendingThreadSends.Entry("!room\nwith-tab\t:example.org", "$" + "event:example.org")
        PendingThreadSends(file).append(entry)
        val restored = PendingThreadSends(file)
        assertThat(restored.first()).isEqualTo(entry)
        val processed = mutableListOf<PendingThreadSends.Entry>()
        restored.drain { processed += it }
        assertThat(processed).containsExactly(entry)
        assertThat(restored.first()).isNull()
    }

    @Test fun `clear prevents an in flight confirmed lookup from resurrecting history`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val pending = PendingThreadSends(File(temporary.root, "pending"))
        val recent = FileRecentThreads(account, File(temporary.root, "recent"), dispatcher, pending)
        val event = EventId("$" + "sent")
        pending.append(PendingThreadSends.Entry(key.roomId.value, event.value))
        recent.clear()
        recent.recordConfirmedSent(key, event)
        assertThat(recent.entries.value).isEmpty()
    }
}
