/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.Properties

/**
 * Account-local, bounded confirmation journal. Retains the latest [LIMIT] unique confirmations, including
 * hashed completed IDs for replay deduplication. Older replays outside this horizon are not remembered.
 * Overflow deliberately drops the oldest confirmation with a warning, not a newer send.
 * The same durable counter orders opened actions and callbacks, never asynchronous lookup completion.
 */
class PendingThreadSends(private val file: File, private val account: String? = null) {
    data class Entry(val room: String, val event: String, val sequence: Long = 0)
    private data class Item(val entry: Entry, val completed: Boolean = false, val fingerprint: String = fingerprint(entry)) {
        fun complete() = copy(entry = entry.copy(room = "", event = ""), completed = true)
    }
    private val lock = Any()
    private var loaded = false
    private var closed = false
    private var sequence = 0L
    private var epoch = 0L
    private var items = emptyList<Item>()

    val generation: Long get() = synchronized(lock) {
        load()
        epoch
    }

    fun close() = synchronized(lock) { closed = true }

    private fun load(floor: Long = 0) {
        check(!closed) { "Recent session is closed" }
        if (loaded) return
        if (file.exists()) {
            val isVersioned = file.bufferedReader().use { it.readLine()?.startsWith("#") == true }
            if (isVersioned) {
                val data = Properties().apply { file.inputStream().use(::load) }
                check(data.getProperty("version") == "2") { "Unknown confirmation journal version" }
                check(data.getProperty("account", "") == account.orEmpty()) { "Confirmation journal account mismatch" }
                sequence = data.getProperty("sequence").toLong()
                epoch = data.getProperty("generation").toLong()
                val count = data.getProperty("count").toInt()
                check(count in 0..LIMIT)
                items = (0 until count).map {
                    val entry = Entry(data.getProperty("$it.room", ""), data.getProperty("$it.event", ""), data.getProperty("$it.sequence").toLong())
                    Item(entry, data.getProperty("$it.completed").toBoolean(), data.getProperty("$it.fingerprint") ?: fingerprint(entry))
                }
            } else {
                // Upgrade the old unbounded FIFO by streaming it; never load its entire contents into memory.
                sequence = floor
                val retained = ArrayDeque<Item>()
                file.useLines { lines ->
                    lines.forEach { line ->
                        val ids = line.split('\t', limit = 2)
                        check(ids.size == 2) { "Invalid legacy confirmation journal" }
                        retained.addLast(Item(Entry(
                            java.net.URLDecoder.decode(ids[0], "UTF-8"),
                            java.net.URLDecoder.decode(ids[1], "UTF-8"),
                            ++sequence
                        )))
                        if (retained.size > LIMIT) retained.removeFirst()
                    }
                }
                items = retained.toList()
                persist(items, sequence, epoch)
            }
        }
        loaded = true
    }

    /** Reserve local activity order before work starts; gaps after failed writes are harmless. */
    fun nextSequence(floor: Long): Long = synchronized(lock) {
        load(floor)
        val next = Math.addExact(maxOf(sequence, floor), 1)
        persist(items, next, epoch)
        sequence = next
        next
    }

    fun advanceSequence(floor: Long) = synchronized(lock) {
        load(floor)
        if (sequence < floor) {
            persist(items, floor, epoch)
            sequence = floor
        }
    }

    fun append(entry: Entry): Entry = synchronized(lock) {
        load()
        val fingerprint = fingerprint(entry)
        items.firstOrNull { it.fingerprint == fingerprint }?.let { return@synchronized entry.copy(sequence = it.entry.sequence) }
        val next = Math.addExact(sequence, 1)
        val ordered = entry.copy(sequence = next)
        val retained = (items + Item(ordered)).takeLast(LIMIT)
        persist(retained, next, epoch)
        if (items.size == LIMIT && !items.first().completed) Timber.w("Recent confirmation capacity reached; oldest unresolved ID evicted")
        items = retained
        sequence = next
        ordered
    }

    fun snapshot(): List<Entry> = synchronized(lock) {
        load()
        items.filterNot { it.completed }.map { it.entry }
    }
    fun first(): Entry? = snapshot().firstOrNull()

    /** Keep completed ID hashes within the bound so a replay after restart cannot acquire a newer sequence. */
    fun remove(entry: Entry) = synchronized(lock) {
        load()
        val updated = items.map { if (it.entry == entry) it.complete() else it }
        if (updated != items) {
            persist(updated, sequence, epoch)
            items = updated
        }
    }

    fun ifPending(entry: Entry, action: () -> Unit) = synchronized(lock) {
        load()
        if (items.any { it.entry == entry && !it.completed }) action()
    }

    fun clear() = synchronized(lock) {
        load()
        val nextEpoch = Math.addExact(epoch, 1)
        // Keep only counter and hashed tombstones, not room/event IDs, to reject replay after clear.
        val cleared = items.map { it.complete() }
        persist(cleared, sequence, nextEpoch)
        items = cleared
        epoch = nextEpoch
    }

    /** One bounded pass: a failed or hanging target cannot starve later confirmations. */
    suspend fun drain(resolve: suspend (Entry) -> Unit) {
        for (entry in snapshot()) {
            try {
                val resolved = withTimeoutOrNull(LOOKUP_TIMEOUT_MILLIS) {
                    resolve(entry)
                    true
                } ?: false
                if (resolved) remove(entry)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                Timber.w(exception, "Confirmed thread lookup deferred")
            }
        }
    }

    private fun persist(rows: List<Item>, next: Long, generation: Long) {
        val data = Properties().apply {
            setProperty("version", "2")
            setProperty("account", account.orEmpty())
            setProperty("sequence", next.toString())
            setProperty("generation", generation.toString())
            setProperty("count", rows.size.toString())
            rows.forEachIndexed { index, item ->
                if (!item.completed) {
                    setProperty("$index.room", item.entry.room)
                    setProperty("$index.event", item.entry.event)
                }
                setProperty("$index.fingerprint", item.fingerprint)
                setProperty("$index.sequence", item.entry.sequence.toString())
                setProperty("$index.completed", item.completed.toString())
            }
        }
        file.parentFile?.mkdirs()
        val temporary = File(file.path + ".tmp")
        temporary.outputStream().use { stream ->
            data.store(stream, null)
            stream.fd.sync()
        }
        check(temporary.renameTo(file)) { "Unable to save confirmed thread activity" }
    }

    companion object {
        private fun fingerprint(entry: Entry): String = MessageDigest.getInstance("SHA-256")
            .digest("${entry.room}\u0000${entry.event}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        const val LIMIT = 100
        const val LOOKUP_TIMEOUT_MILLIS = 10_000L
        const val RETRY_MILLIS = 5_000L
    }
}
