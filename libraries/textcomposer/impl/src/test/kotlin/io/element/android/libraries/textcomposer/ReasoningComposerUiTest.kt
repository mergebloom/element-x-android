/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.textcomposer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.ReasoningEffort
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.wysiwyg.display.TextDisplay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class ReasoningComposerUiTest : RobolectricTest() {
    @Test
    fun `tap sends normally and actual directional drags send once`() = runAndroidComposeUiTest {
        val efforts = mutableListOf<ReasoningEffort>()
        var normalSends = 0
        composer(onSend = { normalSends++ }, onReasoning = efforts::add)
        val send = onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_message))
        send.performTouchInput { click() }
        assertEquals(1, normalSends)
        val paths = listOf(
            Offset(-90f, 0f) to ReasoningEffort.Low,
            Offset(-70f, -56f) to ReasoningEffort.Medium,
            Offset(0f, -90f) to ReasoningEffort.High,
            Offset(20f, -55f) to ReasoningEffort.Max,
        )
        paths.forEach { (delta, effort) ->
            send.performTouchInput { swipe(center, center + delta, durationMillis = 350) }
            assertEquals(effort, efforts.last())
        }
        assertEquals(paths.map { it.second }, efforts)
        assertEquals(1, normalSends)
    }

    @Test
    fun `cancelled and downward drags never send`() = runAndroidComposeUiTest {
        var sends = 0
        composer(onSend = { sends++ }, onReasoning = { sends++ })
        val send = onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_message))
        send.performTouchInput {
            down(center)
            moveTo(center + Offset(-90f, -50f), delayMillis = 100)
            cancel()
        }
        send.performTouchInput { swipe(center, center + Offset(0f, 35f), durationMillis = 350) }
        assertEquals(0, sends)
        onNodeWithTag("reasoning-selector").assertDoesNotExist()
    }

    @Test
    fun `accessibility offers every effort without replacing ordinary tap`() = runAndroidComposeUiTest {
        val efforts = mutableListOf<ReasoningEffort>()
        composer(onReasoning = efforts::add)
        val send = onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_message))
        val actions = send.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(4, actions.size)
        runOnIdle { actions.forEach { it.action() } }
        assertEquals(ReasoningEffort.entries, efforts)
    }

    @Test
    fun `editing exposes no reasoning action even when enabled by caller`() = runAndroidComposeUiTest {
        composer(mode = aMessageComposerModeEdit())
        val edit = onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_edited_message))
        assertEquals(emptyList<Any>(), edit.fetchSemanticsNode().config[SemanticsActions.CustomActions])
    }

    @Test
    fun `circular labels do not overlap at increased font scale`() = runAndroidComposeUiTest<ComponentActivity> {
        setContent {
            ElementTheme {
                CompositionLocalProvider(LocalDensity provides Density(1f, 1.3f)) {
                    ReasoningSelector(ReasoningEffort.High)
                }
            }
        }
        val bounds = ReasoningEffort.entries.map {
            onNodeWithTag("reasoning-label-${it.wireValue}").fetchSemanticsNode().boundsInRoot
        }
        bounds.forEachIndexed { i, rect ->
            bounds.drop(i + 1).forEach { other -> assertFalse("Label cells overlap", rect.overlaps(other)) }
        }
    }

    @Test
    fun `capture actual selector states in light and dark`() {
        val dir = File("build/outputs/reasoning-screenshots").apply { mkdirs() }
        listOf(false, true).forEach { dark ->
            (listOf<ReasoningEffort?>(null) + ReasoningEffort.entries).forEach { effort ->
                captureRoboImage(file = File(dir, "selector-${if (dark) "dark" else "light"}-${effort?.wireValue ?: "cancel"}.png")) {
                    ElementTheme(theme = if (dark) Theme.Dark else Theme.Light) { ReasoningSelector(effort) }
                }
            }
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.composer(
        mode: MessageComposerMode = MessageComposerMode.Normal,
        onSend: () -> Unit = {},
        onReasoning: (ReasoningEffort) -> Unit = {},
    ) {
        setContent {
            ElementTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    TextComposer(
                        state = aTextEditorStateMarkdown(initialText = "Example message", initialFocus = true),
                        voiceMessageState = VoiceMessageState.Idle,
                        composerMode = mode,
                        onRequestFocus = {},
                        onSendMessage = onSend,
                        onSendMessageWithReasoning = onReasoning,
                        reasoningSwipeEnabled = true,
                        onResetComposerMode = {},
                        onAddAttachment = {},
                        onDismissTextFormatting = {},
                        onVoiceRecorderEvent = {},
                        onVoicePlayerEvent = {},
                        onSendVoiceMessage = {},
                        onDeleteVoiceMessage = {},
                        onError = {},
                        onTyping = {},
                        onReceiveSuggestion = {},
                        onSelectRichContent = null,
                        resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
                        resolveAtRoomMentionDisplay = { TextDisplay.Plain },
                    )
                }
            }
        }
    }
}
