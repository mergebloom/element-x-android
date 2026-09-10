/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.RecentThread
import io.element.android.libraries.matrix.api.threads.RecentThreads
import io.element.android.libraries.matrix.api.threads.ThreadKey
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Properties

/** Session directory is removed on logout. Only IDs and local activity order/time are stored, never content. */
class FileRecentThreads(
    private val account: UserId,
    private val file: File,
    private val dispatcher: CoroutineDispatcher,
    private val pending: PendingThreadSends? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : RecentThreads {
    private val mutex = Mutex()
    private var sequence = 0L
    private var highWaterTime = 0L
    private var loaded = false
    private val mutableEntries = MutableStateFlow<List<RecentThread>>(emptyList())
    override val entries = mutableEntries.asStateFlow()

    private fun read(): List<RecentThread> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val data = Properties().apply { file.inputStream().use(::load) }
        check(data.getProperty("account") == account.value)
        sequence = data.getProperty("sequence").toLong()
        highWaterTime = maxOf(data.getProperty("time").toLong(), now())
        (0 until data.getProperty("count").toInt().coerceIn(0, LIMIT)).map { index ->
            RecentThread(
                ThreadKey(account, RoomId(data.getProperty("$index.room")), EventId(data.getProperty("$index.root"))),
                data.getProperty("$index.opened").toLong(),
                data.getProperty("$index.sent").toLong(),
                data.getProperty("$index.time").toLong(),
                data.getProperty("$index.event")?.let(::EventId),
            )
        }.let(::retain)
    }.getOrDefault(emptyList())

    suspend fun load() = withContext(dispatcher) { mutex.withLock { ensureLoaded() } }

    private fun ensureLoaded() {
        if (!loaded) {
            mutableEntries.value = read()
            loaded = true
        }
    }

    override suspend fun recordOpened(key: ThreadKey) = record(key, opened = true)
    override suspend fun recordSent(key: ThreadKey) = record(key, opened = false)

    suspend fun recordConfirmedSent(key: ThreadKey, event: EventId) = record(key, opened = false, event = event)

    private suspend fun record(key: ThreadKey, opened: Boolean, event: EventId? = null) = withContext(dispatcher) {
        require(key.accountId == account)
        mutex.withLock {
            ensureLoaded()
            highWaterTime = maxOf(highWaterTime, now()) // rollback cannot reorder or prematurely expire activity
            val previous = mutableEntries.value.firstOrNull { it.key == key } ?: RecentThread(key)
            if (event != null && previous.confirmedEventId == event) return@withLock
            // Clear history may race an in-flight relation lookup. Don't resurrect an already cleared confirmation.
            if (event != null && pending != null && pending.first()?.event != event.value) return@withLock
            val next = ++sequence
            val updated = if (opened) {
                previous.copy(openedSequence = next, activeAtMillis = highWaterTime)
            } else {
                previous.copy(messagedSequence = next, activeAtMillis = highWaterTime, confirmedEventId = event)
            }
            val retained = retain(mutableEntries.value.filter { it.key != key } + updated)
            persist(retained)
            mutableEntries.value = retained
        }
    }

    override suspend fun clear() = withContext(dispatcher) {
        mutex.withLock {
            ensureLoaded()
            pending?.clear()
            persist(emptyList())
            mutableEntries.value = emptyList()
        }
    }

    private fun retain(candidates: List<RecentThread>): List<RecentThread> {
        val pins = setOfNotNull(
            candidates.maxByOrNull { it.openedSequence }?.takeIf { it.openedSequence > 0 }?.key,
            candidates.maxByOrNull { it.messagedSequence }?.takeIf { it.messagedSequence > 0 }?.key,
        )
        val others = candidates.filter { it.key !in pins && highWaterTime - it.activeAtMillis <= RETENTION }.sortedByDescending { it.sequence }
        return (candidates.filter { it.key in pins } + others).take(LIMIT).sortedByDescending { it.sequence }
    }

    private fun persist(rows: List<RecentThread>) {
        val data = Properties().apply {
            setProperty("account", account.value)
            setProperty("sequence", sequence.toString())
            setProperty("time", highWaterTime.toString())
            setProperty("count", rows.size.toString())
            rows.forEachIndexed { index, row ->
                setProperty("$index.room", row.key.roomId.value)
                setProperty("$index.root", row.key.rootEventId.value)
                setProperty("$index.opened", row.openedSequence.toString())
                setProperty("$index.sent", row.messagedSequence.toString())
                setProperty("$index.time", row.activeAtMillis.toString())
                row.confirmedEventId?.let { setProperty("$index.event", it.value) }
            }
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { stream ->
            data.store(stream, null)
            stream.fd.sync()
        }
        check(temporary.renameTo(file)) { "Unable to save recent threads" }
    }

    companion object {
        const val LIMIT = 100
        const val RETENTION = 90L * 24 * 60 * 60 * 1000
    }
}
