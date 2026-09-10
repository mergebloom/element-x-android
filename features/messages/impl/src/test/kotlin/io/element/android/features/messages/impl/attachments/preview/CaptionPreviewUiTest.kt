/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl.attachments.preview

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import com.github.takahirom.roborazzi.captureRoboImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.features.messages.impl.R
import io.element.android.libraries.textcomposer.model.aTextEditorStateMarkdown
import io.element.android.libraries.ui.strings.CommonStrings
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Production preview/composer/dialog rendering; only the image uses the upstream sample renderer. */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w320dp-h640dp-mdpi")
class CaptionPreviewUiTest : RobolectricTest() {
    @After fun resetSystemFontScale() {
        RuntimeEnvironment.setFontScale(1f)
    }
    @Test
    fun `capture editable caption conversion conflict and retry with accessible controls`() {
        val directory = File("build/outputs/caption-screenshots").apply { mkdirs() }
        listOf(false, true).forEach { dark ->
            listOf(1f, 2f).forEach { scale ->
                // Dialog windows read Android configuration, not the parent LocalDensity.
                RuntimeEnvironment.setFontScale(scale)
                runAndroidComposeUiTest<ComponentActivity> {
                    val events = mutableListOf<AttachmentsPreviewEvent>()
                    val state = mutableStateOf(
                        anAttachmentsPreviewState(
                            textEditorState = aTextEditorStateMarkdown(initialText = "Weekend walk 🌿\nBy the river"),
                            mediaOptimizationSelectorState = aMediaOptimisationSelectorState(displayMediaSelectorViews = false),
                        ).copy(plainTextConversion = true, eventSink = events::add)
                    )
                    setContent {
                        ElementTheme(theme = if (dark) Theme.Dark else Theme.Light) {
                            val density = LocalDensity.current.density
                            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                                AttachmentsPreviewView(state.value, SampleMediaRenderer())
                            }
                        }
                    }
                    onNodeWithText(activity!!.getString(R.string.screen_caption_plain_text)).assertIsDisplayed()
                    onNodeWithContentDescription(activity!!.getString(CommonStrings.action_send_message)).assertIsDisplayed()
                    onRoot().captureRoboImage(File(directory, "caption-$dark-$scale-edit.png").path)
                    runOnIdle { state.value = state.value.copy(showDraftConflict = true) }
                    listOf(R.string.screen_caption_keep_editing, R.string.screen_caption_discard_attachment).forEach { label ->
                        val results = mutableListOf<TextLayoutResult>()
                        onNodeWithText(activity!!.getString(label)).assertIsDisplayed()
                            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(results) }
                        assertTrue("Button text must expose its actual layout", results.isNotEmpty())
                        assertFalse("Draft action must not be ellipsized at $scale font scale", results.any { it.hasVisualOverflow })
                    }
                    onRoot().captureRoboImage(File(directory, "caption-$dark-$scale-conflict.png").path)
                    onNodeWithText(activity!!.getString(R.string.screen_caption_keep_editing)).performClick()
                    assertEquals(AttachmentsPreviewEvent.KeepEditingDraft, events.last())
                    runOnIdle {
                        state.value = state.value.copy(
                            showDraftConflict = false,
                            sendActionState = SendActionState.Failure(IllegalStateException("Synthetic failure"), emptyList()),
                        )
                    }
                    waitForIdle()
                    onNodeWithText(activity!!.getString(R.string.screen_caption_draft_conflict_title)).assertDoesNotExist()
                    onNodeWithText(activity!!.getString(CommonStrings.action_retry)).assertIsDisplayed()
                    onRoot().captureRoboImage(File(directory, "caption-$dark-$scale-retry.png").path)
                    assertEquals(0, events.count { it == AttachmentsPreviewEvent.SendAttachment })
                    onNodeWithText(activity!!.getString(CommonStrings.action_retry)).performClick()
                    assertEquals(1, events.count { it == AttachmentsPreviewEvent.SendAttachment })
                }
            }
        }
    }
}
