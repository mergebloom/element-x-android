/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.matrix.api.threads.ThreadReadState
import org.junit.Test

class ExplicitThreadReadReducerTest {
    private val events = listOf(
        OrderedThreadEvent("root", null, false, true),
        OrderedThreadEvent("receipt", "root", false, true),
        OrderedThreadEvent("incoming", "root", false, true),
        OrderedThreadEvent("own", "root", true, true),
        OrderedThreadEvent("elsewhere", null, false, true),
    )
    private fun classify(receipts: Set<String>, ordered: List<OrderedThreadEvent> = events, fresh: Boolean = true) =
        ExplicitThreadReadReducer.classify("root", "own", receipts, ordered, fresh)

    @Test fun `latest own reply cannot implicitly read preceding incoming reply`() {
        assertThat(classify(setOf("receipt"))).isEqualTo(ExplicitThreadReadResult(ThreadReadState.Unread, 1))
    }
    @Test fun `no explicit receipt is unknown not zero`() {
        assertThat(classify(emptySet()).state).isEqualTo(ThreadReadState.Unknown)
    }
    @Test fun `farthest proven public or private anchor wins independent of input order`() {
        for (receipts in listOf(linkedSetOf("receipt", "own"), linkedSetOf("own", "receipt"))) {
            assertThat(classify(receipts).state).isEqualTo(ThreadReadState.Read)
        }
    }
    @Test fun `unthreaded anchor elsewhere uses room order not timestamps`() {
        assertThat(classify(setOf("elsewhere")).state).isEqualTo(ThreadReadState.Read)
    }
    @Test fun `unthreaded same event fallback is accepted`() {
        assertThat(classify(setOf("incoming")).state).isEqualTo(ThreadReadState.Read)
    }
    @Test fun `one unresolved dominating receipt prevents classification`() {
        assertThat(classify(setOf("receipt", "missing")).state).isEqualTo(ThreadReadState.Unknown)
    }
    @Test fun `current private head dominates an older unresolved public anchor`() {
        assertThat(classify(setOf("own", "missing-old-public")).state).isEqualTo(ThreadReadState.Read)
        assertThat(classify(setOf("elsewhere", "missing-old-public")).state).isEqualTo(ThreadReadState.Read)
    }
    @Test fun `pagination resolves an initially absent receipt`() {
        assertThat(classify(setOf("receipt"), events.drop(2)).state).isEqualTo(ThreadReadState.Unknown)
        assertThat(classify(setOf("receipt"), events).state).isEqualTo(ThreadReadState.Unread)
    }
    @Test fun `stale receipt evidence is unknown`() {
        assertThat(classify(setOf("own"), fresh = false).state).isEqualTo(ThreadReadState.Unknown)
    }
    @Test fun `missing head is unknown`() {
        assertThat(classify(setOf("receipt"), events.take(3)).state).isEqualTo(ThreadReadState.Unknown)
    }
    @Test fun `unsupported encrypted candidate cannot become false read`() {
        val encrypted = events.map { if (it.id == "incoming") it.copy(root = null, eligible = null) else it }
        assertThat(classify(setOf("receipt"), encrypted).state).isEqualTo(ThreadReadState.Unknown)
    }
    @Test fun `reactions redactions and other threads do not add unread`() {
        val filtered = events.map { if (it.id == "incoming") it.copy(eligible = false) else it }
        assertThat(classify(setOf("receipt"), filtered).state).isEqualTo(ThreadReadState.Read)
        val anotherThread = events.map { if (it.id == "incoming") it.copy(root = "other") else it }
        assertThat(classify(setOf("receipt"), anotherThread).state).isEqualTo(ThreadReadState.Read)
    }
}
