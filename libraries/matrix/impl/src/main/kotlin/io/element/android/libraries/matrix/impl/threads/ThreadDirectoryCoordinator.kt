/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.threads.ThreadCoverage
import io.element.android.libraries.matrix.api.threads.ThreadDirectory
import io.element.android.libraries.matrix.api.threads.ThreadDirectoryRow
import io.element.android.libraries.matrix.api.threads.ThreadDirectorySnapshot
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.api.threads.ThreadReadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

interface ThreadDirectorySource {
    /** A completed network sync, not merely SyncState.Running. */
    suspend fun syncAndRooms(): List<RoomId>
    suspend fun scan(room: RoomId, emit: suspend (ThreadDirectoryRow) -> Unit)
    suspend fun isAvailable(key: ThreadKey): Boolean
    suspend fun summary(key: ThreadKey): ThreadDirectoryRow? = null
    suspend fun isRoomAvailable(room: RoomId): Boolean = false
    fun loadMore() = Unit
}

class ThreadDirectoryCoordinator(
    private val source: ThreadDirectorySource,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    private val refreshMillis: Long = 30_000,
) : ThreadDirectory {
    private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
    private var previous = ThreadDirectorySnapshot()
    private val stream = channelFlow {
        while (isActive) {
            var snapshot = previous.copy(coverage = if (previous.rows.isEmpty()) ThreadCoverage.Loading else ThreadCoverage.Stale)
            send(snapshot)
            try {
                val rooms = source.syncAndRooms().distinct()
                val rows = snapshot.rows.filter { it.key.roomId in rooms }.associateBy { it.key }.toMutableMap()
                val failures = mutableSetOf<RoomId>()
                val mutex = Mutex()
                var checked = 0
                val generation = previous.generation + 1
                suspend fun publish(finished: Boolean = false) {
                    snapshot = ThreadDirectorySnapshot(
                        rows = rows.values.toList(),
                        coverage = when {
                            !finished -> ThreadCoverage.Loading
                            failures.isNotEmpty() || rows.values.any { it.readState == ThreadReadState.Unknown } -> ThreadCoverage.Partial
                            else -> ThreadCoverage.Complete
                        },
                        roomsChecked = checked,
                        roomsTotal = rooms.size,
                        failedRooms = failures.toSet(),
                        generation = generation,
                    )
                    previous = snapshot
                    send(snapshot)
                }
                publish()
                coroutineScope {
                    val work = Channel<RoomId>(2)
                    launch {
                        try {
                        rooms.forEach { work.send(it) }
                    } finally {
                        work.close()
                    }
                    }
                    repeat(2) {
                        launch {
                            for (room in work) {
                                val seen = mutableSetOf<ThreadKey>()
                                try {
                                    source.scan(room) { row ->
                                        mutex.withLock {
                                            seen += row.key
                                            rows[row.key] = row
                                            publish()
                                        }
                                    }
                                    mutex.withLock { rows.keys.removeAll { it.roomId == room && it !in seen } }
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: Exception) {
                                    mutex.withLock { failures += room }
                                }
                                mutex.withLock {
                                    checked++
                                    publish()
                                }
                            }
                        }
                    }
                }
                publish(finished = true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                snapshot = snapshot.copy(coverage = if (snapshot.rows.isEmpty()) ThreadCoverage.Error else ThreadCoverage.Stale)
                previous = snapshot
                send(snapshot)
            }
            // A full first-page rescan discovers new roots and receipt-only changes which ThreadListService does not publish.
            withTimeoutOrNull(refreshMillis) { refreshRequests.receive() }
        }
    }.flowOn(dispatcher).shareIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 0, replayExpirationMillis = 0), replay = 1)

    override fun snapshots() = stream
    override fun refresh() {
        refreshRequests.trySend(Unit)
    }
    override fun loadMore() {
        source.loadMore()
        refresh()
    }
    override suspend fun isAvailable(key: ThreadKey) = source.isAvailable(key)
    override suspend fun summary(key: ThreadKey) = source.summary(key)
    override suspend fun isRoomAvailable(room: RoomId) = source.isRoomAvailable(room)
}
