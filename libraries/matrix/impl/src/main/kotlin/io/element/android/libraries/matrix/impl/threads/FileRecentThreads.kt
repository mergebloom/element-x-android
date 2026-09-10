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
    private var generation = 0L
    private var loaded = false
    private var closed = false
    private val mutableEntries = MutableStateFlow<List<RecentThread>>(emptyList())
    override val entries = mutableEntries.asStateFlow()

    private fun read(): List<RecentThread> = runCatching {
        if (!file.exists()) return@runCatching emptyList()
        val data = Properties().apply { file.inputStream().use(::load) }
        check(data.getProperty("account") == account.value)
        sequence = data.getProperty("sequence").toLong()
        generation = data.getProperty("generation", "0").toLong()
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

    /** Await existing writes and reject late UI/native callbacks before session files can be removed. */
    suspend fun close() = withContext(dispatcher) {
        mutex.withLock {
            closed = true
            pending?.close()
            mutableEntries.value = emptyList()
        }
    }

    private fun ensureLoaded() {
        check(!closed) { "Recent session is closed" }
        if (!loaded) {
            val rows = read()
            pending?.advanceSequence(sequence)
            val currentGeneration = pending?.generation ?: generation
            mutableEntries.value = if (generation == currentGeneration) rows else emptyList()
            generation = currentGeneration
            loaded = true
        }
    }

    override suspend fun recordOpened(key: ThreadKey) = record(key, opened = true)
    override suspend fun recordSent(key: ThreadKey) = record(key, opened = false)

    suspend fun recordConfirmedSent(key: ThreadKey, entry: PendingThreadSends.Entry) = withContext(dispatcher) {
        require(key.accountId == account)
        require(key.roomId.value == entry.room)
        mutex.withLock {
            ensureLoaded()
            checkNotNull(pending).ifPending(entry) {
                update(key, opened = false, event = EventId(entry.event), next = entry.sequence)
            }
        }
    }

    private suspend fun record(key: ThreadKey, opened: Boolean) = withContext(dispatcher) {
        require(key.accountId == account)
        mutex.withLock {
            ensureLoaded()
            val next = pending?.nextSequence(sequence) ?: Math.addExact(sequence, 1)
            update(key, opened, next = next)
        }
    }

    private fun update(key: ThreadKey, opened: Boolean, event: EventId? = null, next: Long) {
        highWaterTime = maxOf(highWaterTime, now()) // rollback cannot reorder or prematurely expire activity
        val previous = mutableEntries.value.firstOrNull { it.key == key } ?: RecentThread(key)
        // Both crash replay and an older delayed resolution are idempotent, including for the same root.
        if (!opened && (next <= previous.messagedSequence || (event != null && previous.confirmedEventId == event))) return
        sequence = maxOf(sequence, next)
        val updated = if (opened) {
            previous.copy(openedSequence = next, activeAtMillis = highWaterTime)
        } else {
            previous.copy(messagedSequence = next, activeAtMillis = highWaterTime, confirmedEventId = event)
        }
        val retained = retain(mutableEntries.value.filter { it.key != key } + updated)
        persist(retained)
        mutableEntries.value = retained
    }

    override suspend fun clear() = withContext(dispatcher) {
        mutex.withLock {
            ensureLoaded()
            pending?.clear()
            generation = pending?.generation ?: generation
            // The journal generation is already a durable clear, even if the second file write fails.
            if (pending != null) mutableEntries.value = emptyList()
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
            setProperty("generation", generation.toString())
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
