/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(ExperimentalTestApi::class)

package io.element.android.libraries.textcomposer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.libraries.ui.utils.time.formatShort
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.wysiwyg.display.TextDisplay
import kotlinx.collections.immutable.persistentListOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import kotlin.time.Duration.Companion.seconds

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class VoiceComposerUiTest : RobolectricTest() {
    @Test
    fun `typed draft retains separate accessible microphone and text send`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        onNodeWithTag("voice-microphone").assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_message)).assertIsDisplayed()
        onNodeWithTag("voice-microphone").performClick()
        assertEquals(listOf(VoiceMessageRecorderEvent.Start), fixture.events)
        onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_message)).assertDoesNotExist()
        onNodeWithTag("reasoning-selector").assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.screen_voice_stop_to_review)).assertIsDisplayed().performClick()
        onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_voice_message)).assertIsDisplayed()
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `ordinary pointer tap starts hands free once and explicit cancel preserves text`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        onNodeWithTag("voice-microphone").performTouchInput { click() }
        assertEquals(listOf(VoiceMessageRecorderEvent.Start), fixture.events)
        onNodeWithText(activity!!.getString(CommonStrings.action_cancel)).performClick()
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Cancel), fixture.events)
        assertEquals(
            "Keep this typed draft",
            (fixture.text as io.element.android.libraries.textcomposer.model.TextEditorState.Markdown).state.text.value().toString()
        )
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `movement before long press aborts rather than accidentally starting`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        onNodeWithTag("voice-microphone").performTouchInput {
            down(center)
            moveTo(center + Offset(-40f, 0f), delayMillis = 50)
            up()
        }
        assertEquals(emptyList<VoiceMessageRecorderEvent>(), fixture.events)
    }

    @Test
    fun `system cancel after locking stops rather than leaving a recording`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        hold()
        onNodeWithTag("voice-microphone").performTouchInput { moveTo(center + Offset(0f, -100f)) }
        waitForIdle()
        onNodeWithTag("voice-microphone").performTouchInput { cancel() }
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
    }

    @Test
    fun `hold survives recording recomposition and release previews without trailing click`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        hold()
        assertEquals(listOf(VoiceMessageRecorderEvent.Start), fixture.events)
        onNodeWithTag("voice-microphone").performTouchInput { up() }
        waitForIdle()
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `cancel direction mirrors in RTL and first cancel cannot turn into lock`() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            runAndroidComposeUiTest<ComponentActivity> {
                val fixture = Fixture()
                composer(fixture, direction = direction)
                hold()
                onNodeWithTag("voice-microphone").performTouchInput {
                    moveTo(center + Offset(if (direction == LayoutDirection.Ltr) -100f else 100f, 0f))
                    moveTo(center + Offset(0f, -100f))
                    up()
                }
                assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Cancel), fixture.events)
                assertEquals(0, fixture.sends)
            }
        }
    }

    @Test
    fun `lock latches before cancel and locked release is a noop until explicit stop`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        hold()
        onNodeWithTag("voice-microphone").performTouchInput {
            moveTo(center + Offset(0f, -100f))
            moveTo(center + Offset(-120f, 0f))
            up()
        }
        assertEquals(listOf(VoiceMessageRecorderEvent.Start), fixture.events)
        assertRecordingWithoutHelper(locked = true)
        onNodeWithText(activity!!.getString(R.string.screen_voice_stop_to_review)).performClick()
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
    }

    @Test
    fun `ambiguous diagonal and sub threshold movements release to preview`() {
        listOf(Offset(-100f, -100f), Offset(-30f, 0f), Offset(0f, -30f)).forEach { delta ->
            runAndroidComposeUiTest<ComponentActivity> {
                val fixture = Fixture()
                composer(fixture)
                hold()
                onNodeWithTag("voice-microphone").performTouchInput {
                    moveTo(center + delta)
                    up()
                }
                assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
            }
        }
    }

    @Test
    fun `dominance ratio commits at one point two five but not below`() {
        listOf(Offset(-100f, -80f) to VoiceMessageRecorderEvent.Cancel, Offset(-99f, -80f) to VoiceMessageRecorderEvent.Stop).forEach { (delta, expected) ->
            runAndroidComposeUiTest<ComponentActivity> {
                val fixture = Fixture()
                composer(fixture)
                hold()
                onNodeWithTag("voice-microphone").performTouchInput {
                    moveTo(center + delta)
                    up()
                }
                assertEquals(listOf(VoiceMessageRecorderEvent.Start, expected), fixture.events)
            }
        }
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun `cancel boundary is density independent rather than seventy two physical pixels`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        hold()
        onNodeWithTag("voice-microphone").performTouchInput {
            moveTo(center + Offset(-100f, 0f))
            up()
        }
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
    }

    @Test
    fun `pointer cancellation stops to preview and never sends`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        hold()
        onNodeWithTag("voice-microphone").performTouchInput { cancel() }
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `release before asynchronous recorder start cannot orphan the late recording`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture(startImmediately = false)
        composer(fixture)
        hold()
        onNodeWithTag("voice-microphone").performTouchInput { up() }
        runOnIdle { fixture.state.value = recording() }
        waitForIdle()
        assertEquals(VoiceMessageRecorderEvent.Stop, fixture.events.last())
        assertEquals(preview(), fixture.state.value)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `active edit exposes disabled microphone and reason`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture, mode = aMessageComposerModeEdit())
        onNodeWithTag("voice-microphone").assertIsNotEnabled()
        onNodeWithText(activity!!.getString(R.string.screen_voice_edit_disabled)).assertIsDisplayed()
        onNodeWithTag("voice-microphone").performTouchInput { click() }
        assertEquals(emptyList<VoiceMessageRecorderEvent>(), fixture.events)
    }

    @Test
    fun `recorder callback refreshes without cancelling the active pointer`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        val replacement = mutableListOf<VoiceMessageRecorderEvent>()
        val callback = mutableStateOf<(VoiceMessageRecorderEvent) -> Unit>(fixture::event)
        composer(fixture, callback = callback)
        hold()
        runOnIdle {
            callback.value = {
            replacement.add(it)
            fixture.event(it)
        }
        }
        onNodeWithTag("voice-microphone").performTouchInput { up() }
        assertEquals(listOf(VoiceMessageRecorderEvent.Stop), replacement)
    }

    @Test
    fun `composer relayout under a stationary finger cannot become a lock gesture`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        fixture.bottomInset.value = 96.dp
        composer(fixture)
        val origin = hold()
        // Models the formatting/keyboard area disappearing while the recorder recomposes.
        runOnIdle { fixture.bottomInset.value = 0.dp }
        waitForIdle()
        movePointerTo(origin)
        onNodeWithTag("voice-lock-target").assertIsDisplayed()
        assertEquals(origin.y - 72f, onNodeWithTag("voice-lock-target").fetchSemanticsNode().boundsInRoot.center.y, 1f)
        onNodeWithTag("voice-microphone").performTouchInput { up() }
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
        assertEquals(preview(), fixture.state.value)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `disposing production composer while held stops safely to preview`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        val visible = mutableStateOf(true)
        setContent { ElementTheme { if (visible.value) ComposerContent(fixture) } }
        hold()
        runOnIdle { visible.value = false }
        waitForIdle()
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
        assertEquals(preview(), fixture.state.value)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `lock dominance ratio mirrors in RTL and commits at one point two five only`() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            listOf(99f, 100f).forEach { upward ->
                runAndroidComposeUiTest<ComponentActivity> {
                    val fixture = Fixture()
                    composer(fixture, direction = direction)
                    val origin = hold()
                    movePointerTo(origin + Offset(if (direction == LayoutDirection.Ltr) -80f else 80f, -upward))
                    onNodeWithTag("voice-microphone").performTouchInput { up() }
                    val expected = if (upward == 100f) {
                        listOf(VoiceMessageRecorderEvent.Start)
                    } else {
                        listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop)
                    }
                    assertEquals(expected, fixture.events)
                    assertEquals(0, fixture.sends)
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `visible targets match actual commit boundaries without covering review actions at two hundred percent`() {
        listOf(LayoutDirection.Ltr, LayoutDirection.Rtl).forEach { direction ->
            runAndroidComposeUiTest<ComponentActivity> {
                val fixture = Fixture()
                composer(fixture, direction = direction, fontScale = 2f)
                val origin = hold(position = Offset(16f, 32f))
                val cancel = onNodeWithTag("voice-cancel-target").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val lock = onNodeWithTag("voice-lock-target").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                val sign = if (direction == LayoutDirection.Ltr) -1f else 1f
                assertEquals(origin.x + sign * 72f, cancel.center.x, 1f)
                assertEquals(origin.y - 72f, lock.center.y, 1f)
                assertFalse("Targets overlap", cancel.overlaps(lock))
                listOf(CommonStrings.action_cancel, R.string.screen_voice_stop_to_review).forEach { label ->
                    val action = onNodeWithText(activity!!.getString(label))
                        .assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).fetchSemanticsNode().boundsInRoot
                    assertFalse("Cancel target covers an action", cancel.overlaps(action))
                    assertFalse("Lock target covers an action", lock.overlaps(action))
                }
                // At the displayed boundary we still hold; crossing it commits. No semantic clicks.
                movePointerTo(Offset(cancel.center.x, origin.y))
                assertEquals(listOf(VoiceMessageRecorderEvent.Start), fixture.events)
                movePointerTo(Offset(cancel.center.x + sign * 2f, origin.y))
                onNodeWithTag("voice-microphone").performTouchInput { up() }
                assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Cancel), fixture.events)
                assertEquals(0, fixture.sends)
            }
        }
    }

    @Test
    fun `visible lock boundary commits only after crossing and preserves locked release`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        val origin = hold(position = Offset(32f, 16f))
        val lock = onNodeWithTag("voice-lock-target").fetchSemanticsNode().boundsInRoot
        assertEquals(origin.y - 72f, lock.center.y, 1f)
        movePointerTo(Offset(origin.x, lock.center.y))
        onNodeWithTag("voice-lock-target").assertIsDisplayed()
        movePointerTo(Offset(origin.x, lock.center.y - 2f))
        onNodeWithTag("voice-microphone").performTouchInput { up() }
        assertRecordingWithoutHelper(locked = true)
        assertEquals(listOf(VoiceMessageRecorderEvent.Start), fixture.events)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `second pointer interrupts a hold safely to preview`() = runAndroidComposeUiTest<ComponentActivity> {
        val fixture = Fixture()
        composer(fixture)
        hold()
        onNodeWithTag("voice-microphone").performTouchInput {
            down(pointerId = 1, position = center + Offset(8f, 0f))
            up(pointerId = 1)
            up(pointerId = 0)
        }
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
        assertEquals(preview(), fixture.state.value)
        assertEquals(0, fixture.sends)
    }

    @Test
    fun `permission round trip does not record until fresh activation and clears the old release latch`() = runAndroidComposeUiTest<ComponentActivity> {
        // The recorder boundary remains Idle during permission. The real presenter owns granting
        // and denying it; this UI test proves that its delayed state cannot replay the old gesture.
        val fixture = Fixture(startImmediately = false)
        composer(fixture)
        hold()
        onNodeWithTag("voice-microphone").performTouchInput { cancel() }
        assertEquals(VoiceMessageState.Idle, fixture.state.value)
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
        runOnIdle { fixture.startImmediately = true }
        onNodeWithTag("voice-microphone").performTouchInput { click() }
        waitForIdle()
        assertEquals(recording(), fixture.state.value)
        assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop, VoiceMessageRecorderEvent.Start), fixture.events)
        assertEquals(0, fixture.sends)
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `capture production composer through held locked and preview pointer states`() {
        val dir = File("build/outputs/voice-screenshots").apply { mkdirs() }
        listOf(false, true).forEach { dark ->
            listOf(1f, 2f).forEach { scale ->
                runAndroidComposeUiTest<ComponentActivity> {
                    val direction = if (scale == 2f) LayoutDirection.Rtl else LayoutDirection.Ltr
                    val fixture = Fixture()
                    composer(fixture, direction = direction, fontScale = scale, dark = dark)
                    fun capture(state: String) {
                        onNodeWithTag("voice-composer").captureRoboImage(File(dir, "voice-$dark-$scale-$direction-$state.png").path)
                    }
                    capture("idle")
                    val origin = hold()
                    capture("held")
                    val sign = if (direction == LayoutDirection.Ltr) -1f else 1f
                    movePointerTo(origin + Offset(sign * 60f, 0f))
                    capture("near-cancel")
                    movePointerTo(origin + Offset(0f, -60f))
                    capture("near-lock")
                    movePointerTo(origin + Offset(0f, -74f))
                    onNodeWithTag("voice-microphone").performTouchInput { up() }
                    assertRecordingWithoutHelper(locked = true)
                    capture("locked")
                    onNodeWithText(activity!!.getString(R.string.screen_voice_stop_to_review)).performTouchInput { click() }
                    onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_voice_message)).assertIsDisplayed()
                    capture("preview")
                    assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
                    assertEquals(0, fixture.sends)
                }
            }
        }
    }

    @Test
    @Config(qualifiers = "w320dp-h640dp-mdpi")
    fun `hands free hides only redundant copy while keeping accessible stop and explicit preview send`() {
        val dir = File("build/outputs/voice-screenshots").apply { mkdirs() }
        listOf(false, true).forEach { dark ->
            listOf(1f, 2f).forEach { scale ->
                runAndroidComposeUiTest<ComponentActivity> {
                    val direction = if (scale == 2f) LayoutDirection.Rtl else LayoutDirection.Ltr
                    val fixture = Fixture()
                    composer(fixture, direction = direction, fontScale = scale, dark = dark)
                    onNodeWithText(activity!!.getString(R.string.screen_voice_idle_hint)).assertIsDisplayed()
                    onNodeWithTag("voice-microphone").performClick()
                    assertRecordingWithoutHelper(locked = false)
                    onNodeWithTag("voice-composer").captureRoboImage(File(dir, "voice-$dark-$scale-$direction-hands-free.png").path)
                    assertEquals(listOf(VoiceMessageRecorderEvent.Start), fixture.events)
                    assertEquals(0, fixture.sends)
                    onNodeWithText(activity!!.getString(R.string.screen_voice_stop_to_review)).performClick()
                    onNodeWithText(activity!!.getString(R.string.screen_voice_preview_hint)).assertIsDisplayed()
                    assertEquals(listOf(VoiceMessageRecorderEvent.Start, VoiceMessageRecorderEvent.Stop), fixture.events)
                    assertEquals(0, fixture.sends)
                    onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_voice_message)).performClick()
                    assertEquals(1, fixture.sends)
                }
            }
        }
    }

    @Test
    fun `hidden hands free and locked hints leave no layout space`() {
        listOf(false, true).forEach { locked ->
            runAndroidComposeUiTest<ComponentActivity> {
                setContent {
                    ElementTheme {
                        Box(Modifier.width(320.dp).testTag("hint-slot")) {
                            VoiceRecordingHint(recording(), VoiceGestureState().apply { this.locked = locked }, editing = false)
                        }
                    }
                }
                onNodeWithTag("hint-slot").assertHeightIsEqualTo(0.dp)
            }
        }
    }

    private fun AndroidComposeUiTest<ComponentActivity>.assertRecordingWithoutHelper(locked: Boolean) {
        onNodeWithText(activity!!.getString(R.string.screen_voice_hands_free)).assertDoesNotExist()
        onNodeWithText(activity!!.getString(R.string.screen_voice_locked)).assertDoesNotExist()
        val status = activity!!.getString(if (locked) R.string.screen_voice_locked else R.string.screen_voice_hands_free)
        onNodeWithTag("voice-microphone")
            .assertIsDisplayed()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, status))
        onNodeWithContentDescription(activity!!.getString(CommonStrings.a11y_voice_message_stop_recording)).assertIsDisplayed()
        onNodeWithText(activity!!.getString(CommonStrings.action_cancel)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        onNodeWithText(activity!!.getString(R.string.screen_voice_stop_to_review)).assertIsDisplayed().assertHeightIsAtLeast(48.dp)
        onNodeWithText(3.seconds.formatShort()).assertIsDisplayed()
        onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_voice_message)).assertDoesNotExist()
        onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_message)).assertDoesNotExist()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.hold(position: Offset? = null): Offset {
        val mic = onNodeWithTag("voice-microphone").fetchSemanticsNode().boundsInRoot
        val origin = if (position == null) mic.center else mic.topLeft + position
        onNodeWithTag("voice-microphone").performTouchInput { down(position ?: center) }
        mainClock.advanceTimeBy(700)
        waitForIdle()
        return origin
    }

    private fun AndroidComposeUiTest<ComponentActivity>.movePointerTo(positionInRoot: Offset) {
        val mic = onNodeWithTag("voice-microphone").fetchSemanticsNode().boundsInRoot
        onNodeWithTag("voice-microphone").performTouchInput { moveTo(positionInRoot - mic.topLeft) }
        waitForIdle()
    }

    private fun AndroidComposeUiTest<ComponentActivity>.composer(
        fixture: Fixture,
        mode: MessageComposerMode = MessageComposerMode.Normal,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        dark: Boolean = false,
        callback: MutableState<(VoiceMessageRecorderEvent) -> Unit> = mutableStateOf(fixture::event),
    ) {
        setContent {
            ElementTheme(theme = if (dark) Theme.Dark else Theme.Light) {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalLayoutDirection provides direction, LocalDensity provides Density(density, fontScale)) {
                    ComposerContent(fixture, mode, callback.value)
                }
            }
        }
    }

    @Composable
    private fun ComposerContent(
        fixture: Fixture,
        mode: MessageComposerMode = MessageComposerMode.Normal,
        callback: (VoiceMessageRecorderEvent) -> Unit = fixture::event,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault).padding(bottom = fixture.bottomInset.value),
            contentAlignment = Alignment.BottomCenter
        ) {
            TextComposer(
                modifier = Modifier.testTag("voice-composer"),
                state = fixture.text,
                voiceMessageState = fixture.state.value,
                composerMode = mode,
                onRequestFocus = {},
                onSendMessage = { fixture.sends++ },
                onSendMessageWithReasoning = { fixture.sends++ },
                reasoningSwipeEnabled = true,
                onResetComposerMode = {},
                onAddAttachment = {},
                onDismissTextFormatting = {},
                onVoiceRecorderEvent = callback,
                onVoicePlayerEvent = {},
                onSendVoiceMessage = { fixture.sends++ },
                onDeleteVoiceMessage = { fixture.state.value = VoiceMessageState.Idle },
                onError = {},
                onTyping = {},
                onReceiveSuggestion = {},
                onSelectRichContent = null,
                resolveMentionDisplay = { _, _ -> TextDisplay.Plain },
                resolveAtRoomMentionDisplay = { TextDisplay.Plain },
            )
        }
    }

    private class Fixture(var startImmediately: Boolean = true, initial: VoiceMessageState = VoiceMessageState.Idle) {
        val text = aTextEditorStateMarkdown(initialText = "Keep this typed draft", initialFocus = true)
        val state = mutableStateOf(initial)
        val bottomInset = mutableStateOf(0.dp)
        val events = mutableListOf<VoiceMessageRecorderEvent>()
        var sends = 0

        fun event(event: VoiceMessageRecorderEvent) {
            events.add(event)
            when (event) {
                VoiceMessageRecorderEvent.Start -> if (startImmediately) state.value = recording()
                VoiceMessageRecorderEvent.Stop -> if (state.value is VoiceMessageState.Recording) state.value = preview()
                VoiceMessageRecorderEvent.Cancel -> state.value = VoiceMessageState.Idle
            }
        }
    }

    companion object {
        private fun recording() = VoiceMessageState.Recording(3.seconds, persistentListOf(0.2f, 0.7f, 0.4f))
        private fun preview() = VoiceMessageState.Preview(false, false, true, 0f, 3.seconds, persistentListOf(0.2f, 0.7f, 0.4f))
    }
}
