/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Properties

class PendingThreadSendsTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun entry(number: Int) = PendingThreadSends.Entry("!room:example.org", "$" + "event$number")

    @Test fun `overflow is durably bounded to latest hundred and duplicate callbacks keep sequence`() = runTest {
        val file = File(temporary.root, "pending")
        val journal = PendingThreadSends(file, "alice")
        repeat(350) { journal.append(entry(it)) }
        val restored = PendingThreadSends(file, "alice")
        assertThat(restored.snapshot()).hasSize(100)
        assertThat(restored.snapshot().map { it.event }).containsExactlyElementsIn((250..349).map { entry(it).event }).inOrder()
        val last = restored.snapshot().last()
        assertThat(restored.append(entry(349))).isEqualTo(last)
        assertThat(Properties().apply { file.inputStream().use(::load) }.getProperty("count")).isEqualTo("100")
        val processed = mutableListOf<PendingThreadSends.Entry>()
        restored.drain { processed += it }
        assertThat(processed).hasSize(100)
        assertThat(restored.first()).isNull()
        val completed = PendingThreadSends(file, "alice")
        assertThat(completed.append(entry(349))).isEqualTo(last)
        assertThat(completed.first()).isNull()
        assertThat(completed.append(entry(350)).sequence).isGreaterThan(last.sequence)
    }

    @Test fun `legacy unbounded journal streams latest entries and advances above recent order`() {
        val file = File(temporary.root, "legacy")
        file.bufferedWriter().use { writer ->
            repeat(350) {
                writer.appendLine("${java.net.URLEncoder.encode(entry(it).room, "UTF-8")}\t${java.net.URLEncoder.encode(entry(it).event, "UTF-8")}")
            }
        }
        val journal = PendingThreadSends(file, "alice")
        journal.advanceSequence(1000)
        assertThat(journal.snapshot()).hasSize(100)
        assertThat(journal.snapshot().first().sequence).isGreaterThan(1000)
        assertThat(journal.snapshot().last().event).isEqualTo(entry(349).event)
        assertThat(PendingThreadSends(file, "alice").snapshot()).isEqualTo(journal.snapshot())
    }

    @Test fun `literal delimiters survive restart and wrong account fails closed`() {
        val file = File(temporary.root, "pending")
        val original = PendingThreadSends.Entry("!room\nwith-tab\t:example.org", "$" + "event:example.org")
        val assigned = PendingThreadSends(file, "alice").append(original)
        assertThat(PendingThreadSends(file, "alice").first()).isEqualTo(assigned)
        assertThat(runCatching { PendingThreadSends(file, "bob").first() }.isFailure).isTrue()
        assertThat(PendingThreadSends(File(temporary.root, "bob"), "bob").first()).isNull()
    }

    @Test fun `failed journal persistence never reports append or completion success`() {
        val file = File(temporary.root, "pending")
        val journal = PendingThreadSends(file)
        val assigned = journal.append(entry(1))
        val blocker = File(file.path + ".tmp").apply { mkdir() }
        assertThat(runCatching { journal.append(entry(2)) }.isFailure).isTrue()
        assertThat(runCatching { journal.remove(assigned) }.isFailure).isTrue()
        assertThat(journal.snapshot()).containsExactly(assigned)
        assertThat(PendingThreadSends(file).snapshot()).containsExactly(assigned)
        blocker.delete()
    }
}
