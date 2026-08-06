/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import io.element.android.libraries.textcomposer.model.ReasoningEffort
import org.junit.Test
import kotlin.test.assertEquals

class ReasoningMessageFormattingTest {
    @Test
    fun `reasoning wraps existing formatted body without changing visible html`() {
        assertEquals(
            "<span data-hermes-reasoning=\"high\"><strong>Hello</strong></span>",
            reasoningFormattedBody("Hello", "<strong>Hello</strong>", ReasoningEffort.High),
        )
    }

    @Test
    fun `reasoning creates safe formatted body while plain body stays independent`() {
        assertEquals(
            "<span data-hermes-reasoning=\"max\">A &lt; B &amp; C</span>",
            reasoningFormattedBody("A < B & C", null, ReasoningEffort.Max),
        )
    }

    @Test
    fun `normal send preserves original formatted body`() {
        assertEquals("<em>Hello</em>", reasoningFormattedBody("Hello", "<em>Hello</em>", null))
    }
}
