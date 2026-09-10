/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import kotlinx.coroutines.delay
import java.io.File

/** Durable FIFO of confirmed event IDs awaiting relation lookup; memory is O(1), no transcript or unbounded channel. */
class PendingThreadSends(private val file: File) {
    data class Entry(val room: String, val event: String)
    private val lock = Any()

    fun append(entry: Entry) = synchronized(lock) {
        file.parentFile?.mkdirs()
        java.io.FileOutputStream(file, true).use {
            val room = java.net.URLEncoder.encode(entry.room, "UTF-8")
            val event = java.net.URLEncoder.encode(entry.event, "UTF-8")
            it.write("$room\t$event\n".toByteArray())
            it.fd.sync()
        }
    }

    fun first(): Entry? = synchronized(lock) {
        if (!file.exists()) return@synchronized null
        file.bufferedReader().use { reader ->
            reader.readLine()?.split('\t', limit = 2)?.let { Entry(java.net.URLDecoder.decode(it[0], "UTF-8"), java.net.URLDecoder.decode(it[1], "UTF-8")) }
        }
    }

    fun remove(entry: Entry) = synchronized(lock) {
        if (first() != entry) return@synchronized
        val temporary = File(file.path + ".tmp")
        file.bufferedReader().use { reader ->
            reader.readLine()
            temporary.outputStream().use { stream ->
                stream.bufferedWriter().apply {
                    reader.copyTo(this)
                    flush()
                }
                stream.fd.sync()
            }
        }
        check(temporary.renameTo(file))
    }

    fun clear() = synchronized(lock) {
        if (file.exists()) check(file.delete())
    }

    /** Failure remains durable and retries in order, so a late old success cannot overtake a newer confirmation. */
    suspend fun drain(resolve: suspend (Entry) -> Unit) {
        var failures = 0
        while (true) {
            val entry = first() ?: return
            try {
                resolve(entry)
                remove(entry)
                failures = 0
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                failures = (failures + 1).coerceAtMost(6)
                delay((500L shl failures).coerceAtMost(30_000))
            }
        }
    }
}
