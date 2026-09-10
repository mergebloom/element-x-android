/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.test.threads

import io.element.android.libraries.matrix.api.threads.RecentThread
import io.element.android.libraries.matrix.api.threads.RecentThreads
import io.element.android.libraries.matrix.api.threads.ThreadDirectory
import io.element.android.libraries.matrix.api.threads.ThreadDirectorySnapshot
import io.element.android.libraries.matrix.api.threads.ThreadKey
import kotlinx.coroutines.flow.MutableStateFlow

class FakeThreadDirectory : ThreadDirectory {
    val state = MutableStateFlow(ThreadDirectorySnapshot())
    var refreshCount = 0
    var available = true
    override fun snapshots() = state
    override fun refresh() {
        refreshCount++
    }
    override fun loadMore() {
        refreshCount++
    }
    override suspend fun isAvailable(key: ThreadKey) = available
    override suspend fun summary(key: ThreadKey) = state.value.rows.firstOrNull { it.key == key }
    override suspend fun isRoomAvailable(room: io.element.android.libraries.matrix.api.core.RoomId) = available
}

class FakeRecentThreads : RecentThreads {
    override val entries = MutableStateFlow<List<RecentThread>>(emptyList())
    private var sequence = 0L
    override suspend fun recordOpened(key: ThreadKey) {
        record(key, true)
    }
    override suspend fun recordSent(key: ThreadKey) {
        record(key, false)
    }
    private fun record(key: ThreadKey, opened: Boolean) {
        val old = entries.value.firstOrNull { it.key == key } ?: RecentThread(key)
        val next = if (opened) old.copy(openedSequence = ++sequence) else old.copy(messagedSequence = ++sequence)
        entries.value = listOf(next) + entries.value.filterNot { it.key == key }
    }
    override suspend fun clear() {
        entries.value = emptyList()
    }
}
