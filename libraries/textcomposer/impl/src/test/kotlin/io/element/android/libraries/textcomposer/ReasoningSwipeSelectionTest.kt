/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import io.element.android.libraries.textcomposer.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReasoningSwipeSelectionTest {
    private val threshold = 24f

    @Test
    fun `direction maps to discrete effort levels`() {
        assertEquals(ReasoningEffort.Low, reasoningEffortForDrag(-100f, 0f, threshold))
        assertEquals(ReasoningEffort.Medium, reasoningEffortForDrag(-100f, -80f, threshold))
        assertEquals(ReasoningEffort.High, reasoningEffortForDrag(0f, -100f, threshold))
        assertEquals(ReasoningEffort.Max, reasoningEffortForDrag(50f, -100f, threshold))
    }

    @Test
    fun `magnitude does not change selection after activation`() {
        assertEquals(ReasoningEffort.Medium, reasoningEffortForDrag(-30f, -20f, threshold))
        assertEquals(ReasoningEffort.Medium, reasoningEffortForDrag(-300f, -200f, threshold))
    }

    @Test
    fun `short downward and outward drags cancel`() {
        assertNull(reasoningEffortForDrag(-10f, -10f, threshold))
        assertNull(reasoningEffortForDrag(0f, 100f, threshold))
        assertNull(reasoningEffortForDrag(100f, 0f, threshold))
    }
}
