/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import io.element.android.libraries.textcomposer.model.ReasoningEffort
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Maps a deliberate drag from the send button to a reasoning effort using only
 * its direction once [activationThresholdPx] has been crossed.
 *
 * The available fan runs inward from the lower-right send button:
 * left -> low, up-left -> medium, up -> high, and up-right -> max.
 */
internal fun reasoningEffortForDrag(
    horizontalDragPx: Float,
    verticalDragPx: Float,
    activationThresholdPx: Float,
): ReasoningEffort? {
    if (activationThresholdPx <= 0f || hypot(horizontalDragPx, verticalDragPx) < activationThresholdPx) return null

    // Screen Y grows downward. Measure the angle from inward/left (0 degrees)
    // through upward (90 degrees) toward outward/right (180 degrees).
    val angleDegrees = Math.toDegrees(
        atan2(-verticalDragPx.toDouble(), -horizontalDragPx.toDouble())
    ).toFloat()

    return when {
        angleDegrees < -22.5f || angleDegrees > 145f -> null
        angleDegrees < 22.5f -> ReasoningEffort.Low
        angleDegrees < 56.25f -> ReasoningEffort.Medium
        angleDegrees < 101.25f -> ReasoningEffort.High
        else -> ReasoningEffort.Max
    }
}
