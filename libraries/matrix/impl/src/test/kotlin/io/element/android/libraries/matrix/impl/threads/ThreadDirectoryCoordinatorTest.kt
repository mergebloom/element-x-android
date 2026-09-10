/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.ThreadCoverage
import io.element.android.libraries.matrix.api.threads.ThreadDirectoryRow
import io.element.android.libraries.matrix.api.threads.ThreadDirectorySnapshot
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.api.threads.ThreadReadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ThreadDirectoryCoordinatorTest {
    private class Source(val roomCount: Int = 3) : ThreadDirectorySource {
        var active = 0
        var maxActive = 0
        var fail: RoomId? = null
        var syncFailure = false
        var unknown = false
        var scans = 0
        var block: CompletableDeferred<Unit>? = null
        var rooms = (1..roomCount).map { RoomId("!room$it:example.org") }
        override suspend fun syncAndRooms(): List<RoomId> {
            check(!syncFailure)
            return rooms
        }
        override suspend fun scan(room: RoomId, emit: suspend (ThreadDirectoryRow) -> Unit) {
            active++
            maxActive = maxOf(maxActive, active)
            try {
                block?.await()
                check(room != fail)
                // Pages after the first remain part of the same production worker; no per-room or total-room cap.
                repeat(3) { page ->
                    delay(1)
                    emit(ThreadDirectoryRow(
                        ThreadKey(UserId("@alice:example.org"), room, EventId("$" + "root$page")),
                        "Room",
                        null,
                        if (unknown) ThreadReadState.Unknown else ThreadReadState.Unread,
                        1,
                    ))
                }
                scans++
            } finally {
                active--
            }
        }
        override suspend fun isAvailable(key: ThreadKey) = key.roomId in rooms
    }

    @Test fun `all rooms and pages progress with at most two workers`() = runTest {
        val source = Source(350)
        val coordinator = ThreadDirectoryCoordinator(source, backgroundScope, StandardTestDispatcher(testScheduler))
        val result = coordinator.snapshots().first { it.coverage == ThreadCoverage.Complete }
        assertThat(result.roomsChecked).isEqualTo(350)
        assertThat(result.rows).hasSize(1050)
        assertThat(source.maxActive).isEqualTo(2)
    }

    @Test fun `partial room failure retains previous known results and other rooms`() = runTest {
        val source = Source()
        val coordinator = ThreadDirectoryCoordinator(source, backgroundScope, StandardTestDispatcher(testScheduler))
        var latest = ThreadDirectorySnapshot()
        val collection = backgroundScope.launch { coordinator.snapshots().collect { latest = it } }
        advanceTimeBy(100)
        runCurrent()
        assertThat(latest.rows).hasSize(9)
        source.fail = source.rooms.first()
        coordinator.refresh()
        advanceTimeBy(100)
        runCurrent()
        assertThat(latest.coverage).isEqualTo(ThreadCoverage.Partial)
        assertThat(latest.rows).hasSize(9)
        assertThat(latest.failedRooms).containsExactly(source.fail)
        collection.cancel()
    }

    @Test fun `receipt only refresh invalidates without latest event change`() = runTest {
        val source = Source()
        val coordinator = ThreadDirectoryCoordinator(source, backgroundScope, StandardTestDispatcher(testScheduler))
        var latest = ThreadDirectorySnapshot()
        val collection = backgroundScope.launch { coordinator.snapshots().collect { latest = it } }
        advanceTimeBy(100)
        runCurrent()
        val generation = latest.generation
        source.unknown = true
        coordinator.refresh()
        advanceTimeBy(100)
        runCurrent()
        assertThat(latest.generation).isGreaterThan(generation)
        assertThat(latest.rows.all { it.readState == ThreadReadState.Unknown }).isTrue()
        assertThat(latest.canShowEmptyUnread).isFalse()
        collection.cancel()
    }

    @Test fun `cancellation releases blocked workers and does not publish another account`() = runTest {
        val source = Source().apply { block = CompletableDeferred() }
        val coordinator = ThreadDirectoryCoordinator(source, backgroundScope, StandardTestDispatcher(testScheduler))
        val collection = backgroundScope.launch { coordinator.snapshots().collect {} }
        runCurrent()
        assertThat(source.active).isEqualTo(2)
        collection.cancel()
        runCurrent()
        assertThat(source.active).isEqualTo(0)
    }

    @Test fun `sync failure is stale not false complete and removal withdraws rows`() = runTest {
        val source = Source()
        val coordinator = ThreadDirectoryCoordinator(source, backgroundScope, StandardTestDispatcher(testScheduler))
        var latest = ThreadDirectorySnapshot()
        val collection = backgroundScope.launch { coordinator.snapshots().collect { latest = it } }
        advanceTimeBy(100)
        runCurrent()
        source.syncFailure = true
        coordinator.refresh()
        runCurrent()
        assertThat(latest.coverage).isEqualTo(ThreadCoverage.Stale)
        source.syncFailure = false
        source.rooms = emptyList()
        coordinator.refresh()
        runCurrent()
        assertThat(latest.rows).isEmpty()
        assertThat(latest.canShowEmptyUnread).isTrue()
        collection.cancel()
    }
}
