/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import io.element.android.libraries.textcomposer.model.ReasoningEffort
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReasoningSwipeSelectionTest {
    @Test
    fun `drag maps to discrete effort levels and clamps at max`() {
        assertEquals(ReasoningEffort.Low, reasoningEffortForDrag(25f, 0f, 48f))
        assertEquals(ReasoningEffort.Medium, reasoningEffortForDrag(72f, 0f, 48f))
        assertEquals(ReasoningEffort.High, reasoningEffortForDrag(120f, 0f, 48f))
        assertEquals(ReasoningEffort.Max, reasoningEffortForDrag(168f, 0f, 48f))
        assertEquals(ReasoningEffort.Max, reasoningEffortForDrag(400f, 0f, 48f))
    }

    @Test
    fun `short downward and far sideways drags select cancel`() {
        assertNull(reasoningEffortForDrag(23f, 0f, 48f))
        assertNull(reasoningEffortForDrag(-40f, 0f, 48f))
        assertNull(reasoningEffortForDrag(100f, 97f, 48f))
    }
}
