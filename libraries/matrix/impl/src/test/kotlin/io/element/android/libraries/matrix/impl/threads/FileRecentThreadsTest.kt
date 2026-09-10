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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileRecentThreadsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = UserId("@alice:example.org")
    private fun key(number: Int) = ThreadKey(account, RoomId("!room:example.org"), EventId("$" + "root$number"))

    @Test fun `opened and confirmed messaged pointers survive restart independently with identifiers only`() = runTest {
        val file = File(temporary.root, "recent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileRecentThreads(account, file, dispatcher) { 1000 }
        store.recordSent(key(1))
        store.recordOpened(key(2))
        val reloaded = FileRecentThreads(account, file, dispatcher) { 1000 }.also { it.load() }
        assertThat(reloaded.entries.value.maxBy { it.messagedSequence }.key).isEqualTo(key(1))
        assertThat(reloaded.entries.value.maxBy { it.openedSequence }.key).isEqualTo(key(2))
        assertThat(file.readText()).doesNotContain("body")
        assertThat(file.readText()).doesNotContain("preview")
    }

    @Test fun `one hundred root bound preserves distinct shortcuts`() = runTest {
        val store = FileRecentThreads(account, File(temporary.root, "recent"), StandardTestDispatcher(testScheduler)) { 1000 }
        store.recordSent(key(0))
        repeat(150) { store.recordOpened(key(it + 1)) }
        assertThat(store.entries.value).hasSize(100)
        assertThat(store.entries.value.map { it.key }).containsAtLeast(key(0), key(150))
    }

    @Test fun `rollback cannot reorder local actions and duplicates coalesce`() = runTest {
        var time = 1000L
        val store = FileRecentThreads(account, File(temporary.root, "recent"), StandardTestDispatcher(testScheduler)) { time }
        store.recordOpened(key(1))
        time = 100
        store.recordSent(key(2))
        store.recordOpened(key(2))
        assertThat(store.entries.value).hasSize(2)
        assertThat(store.entries.value.first().key).isEqualTo(key(2))
        assertThat(store.entries.value.first().activeAtMillis).isEqualTo(1000)
    }

    @Test fun `ninety day inactivity preserves two shortcuts but evicts other inactive roots`() = runTest {
        var time = 1000L
        val file = File(temporary.root, "recent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileRecentThreads(account, file, dispatcher) { time }
        store.recordSent(key(1))
        store.recordOpened(key(2))
        store.recordOpened(key(3))
        time += FileRecentThreads.RETENTION + 1
        val restored = FileRecentThreads(account, file, dispatcher) { time }.also { it.load() }
        assertThat(restored.entries.value.map { it.key }).containsExactly(key(1), key(3))
        store.recordOpened(key(4))
        assertThat(store.entries.value.map { it.key }).containsExactly(key(1), key(4))
    }

    @Test fun `wrong account cannot read or append history`() = runTest {
        val file = File(temporary.root, "recent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileRecentThreads(account, file, dispatcher)
        store.recordOpened(key(1))
        val another = FileRecentThreads(UserId("@bob:example.org"), file, dispatcher).also { it.load() }
        assertThat(another.entries.value).isEmpty()
        assertThat(runCatching { another.recordSent(key(1)) }.isFailure).isTrue()
    }

    @Test fun `concurrent actions have unique durable sequences and clear persists`() = runTest {
        val file = File(temporary.root, "recent")
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = FileRecentThreads(account, file, dispatcher)
        (1..20).map { async { store.recordOpened(key(it)) } }.awaitAll()
        assertThat(store.entries.value.map { it.sequence }.toSet()).hasSize(20)
        store.clear()
        assertThat(FileRecentThreads(account, file, dispatcher).also { it.load() }.entries.value).isEmpty()
    }
}
