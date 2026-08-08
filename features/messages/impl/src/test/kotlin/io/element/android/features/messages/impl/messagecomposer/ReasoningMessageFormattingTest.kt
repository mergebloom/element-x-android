/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.textcomposer.model.ReasoningEffort
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReasoningMessageFormattingTest {
    @Test
    fun `reasoning command uses the selected wire value`() {
        assertEquals("/reasoning low", reasoningCommand(ReasoningEffort.Low))
        assertEquals("/reasoning medium", reasoningCommand(ReasoningEffort.Medium))
        assertEquals("/reasoning high", reasoningCommand(ReasoningEffort.High))
        assertEquals("/reasoning max", reasoningCommand(ReasoningEffort.Max))
    }

    @Test
    fun `reasoning command is accepted before user message is sent`() = runTest {
        val sentBodies = mutableListOf<String>()
        val timeline = FakeTimeline().apply {
            sendMessageLambda = { body, _, _, _, _ ->
                sentBodies += body
                Result.success(Unit)
            }
        }

        val result = timeline.sendAfterSettingReasoning(ReasoningEffort.High) {
            sendMessage("Explain this", null, emptyList())
        }

        assertTrue(result.isSuccess)
        assertEquals(listOf("/reasoning high", "Explain this"), sentBodies)
    }

    @Test
    fun `failed reasoning command prevents prompt from using wrong effort`() = runTest {
        val sentBodies = mutableListOf<String>()
        val timeline = FakeTimeline().apply {
            sendMessageLambda = { body, _, _, _, _ ->
                sentBodies += body
                Result.failure(IllegalStateException("command failed"))
            }
        }

        val result = timeline.sendAfterSettingReasoning(ReasoningEffort.Max) {
            sendMessage("Do not send", null, emptyList())
        }

        assertTrue(result.isFailure)
        assertEquals(listOf("/reasoning max"), sentBodies)
    }

    @Test
    fun `normal send remains one message`() = runTest {
        val sentBodies = mutableListOf<String>()
        val timeline = FakeTimeline().apply {
            sendMessageLambda = { body, _, _, _, _ ->
                sentBodies += body
                Result.success(Unit)
            }
        }

        val result = timeline.sendAfterSettingReasoning(null) {
            sendMessage("Normal", null, emptyList())
        }

        assertTrue(result.isSuccess)
        assertEquals(listOf("Normal"), sentBodies)
    }
}
