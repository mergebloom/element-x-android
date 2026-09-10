/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.api.threads

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

data class ThreadKey(val accountId: UserId, val roomId: RoomId, val rootEventId: EventId)

enum class ThreadReadState { Read, Unread, Unknown }
enum class ThreadCoverage { Loading, Partial, Complete, Stale, Error }

data class ThreadDirectoryRow(
    val key: ThreadKey,
    val roomName: String,
    val preview: String?,
    val readState: ThreadReadState,
    val unreadCount: Int = 0,
)

data class ThreadDirectorySnapshot(
    val rows: List<ThreadDirectoryRow> = emptyList(),
    val coverage: ThreadCoverage = ThreadCoverage.Loading,
    val roomsChecked: Int = 0,
    val roomsTotal: Int = 0,
    val failedRooms: Set<RoomId> = emptySet(),
    val generation: Long = 0,
) {
    val canShowEmptyUnread: Boolean get() = coverage == ThreadCoverage.Complete && rows.all { it.readState == ThreadReadState.Read }
}

interface ThreadDirectory {
    /** Collect only while visible. Cancellation releases native workers and listeners. No receipt writes. */
    fun snapshots(): Flow<ThreadDirectorySnapshot>
    fun refresh()
    fun loadMore()
    suspend fun isAvailable(key: ThreadKey): Boolean
    suspend fun summary(key: ThreadKey): ThreadDirectoryRow?
    suspend fun isRoomAvailable(room: RoomId): Boolean
}

data class RecentThread(
    val key: ThreadKey,
    val openedSequence: Long = 0,
    val messagedSequence: Long = 0,
    val activeAtMillis: Long = 0,
    val confirmedEventId: EventId? = null,
) {
    val sequence: Long get() = maxOf(openedSequence, messagedSequence)
}

interface RecentThreads {
    val entries: StateFlow<List<RecentThread>>
    suspend fun recordOpened(key: ThreadKey)

    /** Only invoke from a confirmed SDK SentEvent, never an enqueue result. */
    suspend fun recordSent(key: ThreadKey)
    suspend fun clear()
}
