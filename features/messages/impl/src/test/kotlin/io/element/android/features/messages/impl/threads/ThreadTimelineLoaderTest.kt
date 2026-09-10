/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.asEventId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.api.timeline.ReceiptType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.A_ROOM_ID
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.room.FakeBaseRoom
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.threads.FakeRecentThreads
import io.element.android.libraries.matrix.test.threads.FakeThreadDirectory
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ThreadTimelineLoaderTest {
    private val target = ThreadId("\$target")
    private val other = ThreadId("\$other")

    @Test
    fun `load does not read or record and immediate receipt operations only reach exact target`() = runTest {
        val calls = mutableListOf<String>()
        fun timeline(name: String, mode: Timeline.Mode) = FakeTimeline(
            mode = mode,
            markAsReadResult = {
                calls += "$name mark $it"
                Result.success(Unit)
            },
            sendReadReceiptLambda = { _, type ->
                calls += "$name receipt $type"
                Result.success(Unit)
            },
        )
        val main = timeline("main", Timeline.Mode.Live)
        val thread = timeline("target", Timeline.Mode.Thread(target))
        val unrelated = timeline("other", Timeline.Mode.Thread(other))
        val requests = mutableListOf<CreateTimelineParams>()
        val room = FakeJoinedRoom(liveTimeline = main, createTimelineResult = {
            requests += it
            Result.success(if (it == CreateTimelineParams.Threaded(target)) thread else unrelated)
        })
        val recent = FakeRecentThreads()
        val loader = ThreadTimelineLoader(room, FakeMatrixClient(recentThreads = recent))
        val loaded = loader.load(target)
        assertThat(requests).containsExactly(CreateTimelineParams.Threaded(target))
        assertThat(calls).isEmpty()
        assertThat(recent.entries.value).isEmpty()
        // No scheduler yield: this catches a controller seeded with room.liveTimeline.
        assertThat(loaded.controller.activeTimelineFlow().value).isSameInstanceAs(thread)
        for (type in listOf(ReceiptType.READ, ReceiptType.READ_PRIVATE)) {
            loaded.controller.invokeOnCurrentTimeline {
                markAsRead(type)
                sendReadReceipt(AN_EVENT_ID, type)
            }
        }
        assertThat(calls).containsExactly(
            "target mark READ",
            "target receipt READ",
            "target mark READ_PRIVATE",
            "target receipt READ_PRIVATE",
        ).inOrder()
        loaded.onDisplayed()
        assertThat(recent.entries.value.map { it.key }).containsExactly(ThreadKey(A_SESSION_ID, A_ROOM_ID, target.asEventId()))
        loaded.close()
        assertThat(thread.closeCounter).isEqualTo(1)
        assertThat(main.closeCounter).isEqualTo(0)
        assertThat(unrelated.closeCounter).isEqualTo(0)
    }

    @Test
    fun `unavailable target cannot create timeline or record opened`() = runTest {
        val directory = FakeThreadDirectory().apply { available = false }
        val recent = FakeRecentThreads()
        var created = false
        val room = FakeJoinedRoom(createTimelineResult = {
            created = true
            Result.success(FakeTimeline())
        })
        val loader = ThreadTimelineLoader(room, FakeMatrixClient(threadDirectory = directory, recentThreads = recent))
        assertThat(runCatching { loader.load(target) }.isFailure).isTrue()
        assertThat(created).isFalse()
        assertThat(recent.entries.value).isEmpty()
    }

    @Test
    fun `SDK failure and wrong timeline cannot record opened or read fallback`() = runTest {
        val recent = FakeRecentThreads()
        val client = FakeMatrixClient(recentThreads = recent)
        val results = listOf(
            Result.failure<Timeline>(IllegalStateException("unavailable")),
            Result.success(FakeTimeline(mode = Timeline.Mode.Thread(other))),
        )
        for (result in results) {
            val room = FakeJoinedRoom(createTimelineResult = { result })
            assertThat(runCatching { ThreadTimelineLoader(room, client).load(target) }.isFailure).isTrue()
        }
        assertThat(recent.entries.value).isEmpty()
    }

    @Test
    fun `target lost before display is not last opened`() = runTest {
        val recent = FakeRecentThreads()
        val directory = FakeThreadDirectory()
        val loader = ThreadTimelineLoader(
            FakeJoinedRoom(createTimelineResult = { Result.success(FakeTimeline(mode = Timeline.Mode.Thread(target))) }),
            FakeMatrixClient(threadDirectory = directory, recentThreads = recent),
        )
        val loaded = loader.load(target)
        directory.available = false
        loaded.onDisplayed()
        assertThat(recent.entries.value).isEmpty()
        loaded.close()
    }

    @Test
    fun `same room and root in another account cannot be opened`() = runTest {
        val recent = FakeRecentThreads()
        val room = FakeJoinedRoom(baseRoom = FakeBaseRoom(sessionId = SessionId("@other:example.org")))
        assertThat(runCatching { ThreadTimelineLoader(room, FakeMatrixClient(recentThreads = recent)).load(target) }.isFailure).isTrue()
        assertThat(recent.entries.value).isEmpty()
    }
}
