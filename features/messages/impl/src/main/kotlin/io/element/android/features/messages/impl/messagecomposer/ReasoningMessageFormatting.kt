/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.textcomposer.model.ReasoningEffort

internal fun reasoningCommand(effort: ReasoningEffort): String = "/reasoning ${effort.wireValue}"

/**
 * Sends the visible Hermes command first and only submits the user's message
 * after the Timeline reports successful submission of that command. A failed
 * submission blocks the prompt. This is not acknowledgement that a bot applied
 * the setting, nor atomic ordering against concurrent sends or across sessions.
 */
internal suspend fun Timeline.sendAfterSettingReasoning(
    effort: ReasoningEffort?,
    sendUserMessage: suspend Timeline.() -> Result<Unit>,
): Result<Unit> {
    if (effort != null) {
        val commandResult = sendMessage(
            body = reasoningCommand(effort),
            htmlBody = null,
            intentionalMentions = emptyList(),
            asPlainText = true,
        )
        if (commandResult.isFailure) return commandResult
    }
    return sendUserMessage()
}
