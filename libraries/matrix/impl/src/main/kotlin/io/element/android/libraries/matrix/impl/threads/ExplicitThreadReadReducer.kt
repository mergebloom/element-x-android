/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import io.element.android.libraries.matrix.api.threads.ThreadReadState

/** A gap-free native timeline, oldest first; wall-clock timestamps are deliberately absent. */
data class OrderedThreadEvent(val id: String, val root: String?, val own: Boolean, val eligible: Boolean?)
data class ExplicitThreadReadResult(val state: ThreadReadState, val count: Int = 0)

object ExplicitThreadReadReducer {
    fun classify(
        root: String,
        head: String,
        receipts: Set<String>,
        ordered: List<OrderedThreadEvent>,
        fresh: Boolean,
    ): ExplicitThreadReadResult {
        val unknown = ExplicitThreadReadResult(ThreadReadState.Unknown)
        if (!fresh || receipts.isEmpty()) return unknown
        val positions = ordered.withIndex().associate { it.value.id to it.index }
        val headPosition = positions[head] ?: return unknown
        // A proven anchor at/beyond the current head is sufficient: other receipts cannot make it less read.
        val known = receipts.mapNotNull { positions[it] }
        if (known.any { it >= headPosition }) return ExplicitThreadReadResult(ThreadReadState.Read)
        // Otherwise an unresolved anchor could turn Unread into Read; never guess its order.
        if (known.size != receipts.size) return unknown
        val anchor = known.max()
        val tail = ordered.subList(anchor + 1, headPosition + 1)
        // An undecryptable event can belong to this thread; don't infer zero or silently skip it.
        if (tail.any { !it.own && it.eligible == null }) return unknown
        val count = tail.count { it.root == root && !it.own && it.eligible == true }
        return ExplicitThreadReadResult(if (count == 0) ThreadReadState.Read else ThreadReadState.Unread, count)
    }
}
