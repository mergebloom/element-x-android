/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import io.element.android.libraries.textcomposer.model.ReasoningEffort
import kotlin.math.abs

internal fun reasoningEffortForDrag(
    upwardDragPx: Float,
    horizontalDragPx: Float,
    itemHeightPx: Float,
): ReasoningEffort? {
    if (itemHeightPx <= 0f || upwardDragPx < itemHeightPx / 2f || abs(horizontalDragPx) > itemHeightPx * 2f) return null
    return ReasoningEffort.entries[((upwardDragPx - itemHeightPx / 2f) / itemHeightPx).toInt().coerceIn(0, ReasoningEffort.entries.lastIndex)]
}
