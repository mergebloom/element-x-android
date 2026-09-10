/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.location.test.FakeLocationService
import io.element.android.features.messages.api.timeline.voicemessages.composer.aVoiceMessageComposerState
import io.element.android.features.messages.impl.FakeMessagesNavigator
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.AttachmentsPreviewPresenterTest
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewPresenter
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewState
import io.element.android.features.messages.impl.draft.FakeComposerDraftService
import io.element.android.features.messages.impl.messagecomposer.suggestions.SuggestionsProcessor
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.utils.FakeMentionSpanFormatter
import io.element.android.features.messages.impl.utils.FakeTextPillificationHelper
import io.element.android.libraries.designsystem.utils.snackbar.SnackbarDispatcher
import io.element.android.libraries.featureflag.test.FakeFeatureFlagService
import io.element.android.libraries.matrix.test.A_USER_ID
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.permalink.FakePermalinkBuilder
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.ui.media.contentvalidation.InMemoryEventContentValidationCache
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaSender
import io.element.android.libraries.mediaviewer.test.FakeLocalMediaFactory
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore
import io.element.android.libraries.push.test.notifications.conversations.FakeNotificationConversationService
import io.element.android.libraries.slashcommands.test.FakeSlashCommandService
import io.element.android.libraries.textcomposer.mentions.MentionSpanProvider
import io.element.android.libraries.textcomposer.mentions.MentionSpanTheme
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.wysiwyg.EditorEditText
import io.element.android.wysiwyg.view.models.InlineFormat
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Requires the REAL wysiwyg native library, compatible with the test host. No fake
 * connection, LocalInspectionMode, mocked editor, manual InputChanged or ignored test.
 * The Android-only Maven .so is not a Linux/JVM substitute. A missing native runtime
 * is a failed verification gate, not a reason to run this through FakeViewConnection.
 */
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h640dp-mdpi")
class RichCaptionRevisionUiTest : RobolectricTest() {
    @Test fun `real rich edit format and undo use latest callback and protect equal draft with typing disabled`() {
        exercise(editAndUndo = true)
    }

    @Test fun `real rich saved editor hydration is not a new revision on unchanged caption cancel`() {
        exercise(editAndUndo = false)
    }

    private fun exercise(editAndUndo: Boolean) = runAndroidComposeUiTest<ComponentActivity> {
        val session = TestScope()
        val drafts = AttachmentCaptionDrafts()
        var picked: List<Attachment> = emptyList()
        val navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = { attachments, _ -> picked = attachments })
        var typingNotices = 0
        var sends = 0
        val room = FakeJoinedRoom(
            typingNoticeResult = {
                typingNotices++
                Result.success(Unit)
            },
            liveTimeline = FakeTimeline().apply {
                sendFileLambda = { _, _, caption, _, _ ->
                    sends++
                    assertThat(caption).isEqualTo("Original rich caption")
                    Result.success(FakeMediaUploadHandler())
                }
            },
        )
        val composer = session.realComposer(room, navigator, drafts)
        var visible by mutableStateOf(true)
        var callbackEpoch by mutableIntStateOf(0)
        val inputEpochs = mutableListOf<Int>()
        val errors = mutableListOf<Throwable>()
        lateinit var source: MessageComposerState
        var preview by mutableStateOf<AttachmentsPreviewPresenter?>(null)
        lateinit var previewState: AttachmentsPreviewState
        var done = 0
        try {
            setContent {
                ElementTheme {
                    val holder = rememberSaveableStateHolder()
                    if (visible) {
                        holder.SaveableStateProvider("real-rich-composer") {
                            val current = composer.present()
                            source = current
                            val epoch = callbackEpoch
                            MessageComposerView(
                                state = current.copy(eventSink = { event ->
                                    if (event == MessageComposerEvent.InputChanged) inputEpochs += epoch
                                    if (event is MessageComposerEvent.Error) errors += event.error
                                    current.eventSink(event)
                                }),
                                voiceMessageState = aVoiceMessageComposerState(),
                            )
                        }
                    }
                    preview?.let { previewState = it.present() }
                }
            }
            waitForIdle()
            val originalView = runOnIdle { requireNotNull(activity!!.window.decorView.richEditor()) }
            runOnIdle { originalView.setMarkdown("Original rich caption") }
            waitForIdle()
            val originalHtml = runOnIdle { originalView.getContentAsMessageHtml() }
            runOnIdle { source.eventSink(MessageComposerEvent.PickAttachmentSource.FromFiles) }
            waitForIdle()
            assertThat(picked).hasSize(1)
            val payload = requireNotNull(navigator.lastCaptionDraft)
            assertThat(payload.caption).isEqualTo("Original rich caption")
            runOnIdle {
                preview = with(AttachmentsPreviewPresenterTest()) {
                    session.createAttachmentsPreviewPresenter(
                        room = room,
                        attachments = picked,
                        captionDraft = payload,
                        captionDrafts = drafts,
                        onDoneListener = { done++ },
                    )
                }
            }
            waitForIdle()
            session.advanceUntilIdle()
            waitForIdle()
            if (editAndUndo) {
                // Recompose without replacing the Android editor. Its installed watcher
                // must call the NEW event sink, even with network typing disabled.
                runOnIdle { callbackEpoch = 1 }
                waitForIdle()
                runOnIdle {
                    assertThat(activity!!.window.decorView.richEditor()).isSameInstanceAs(originalView)
                    inputEpochs.clear()
                    typingNotices = 0
                    originalView.setSelection(originalView.text!!.length)
                    originalView.append(" temporary")
                }
                waitForIdle()
                runOnIdle { assertThat(inputEpochs).isNotEmpty() }
                val afterEdit = inputEpochs.size
                runOnIdle { originalView.undo() }
                waitForIdle()
                runOnIdle {
                    assertThat(originalView.getContentAsMessageHtml()).isEqualTo(originalHtml)
                    assertThat(inputEpochs.size).isGreaterThan(afterEdit)
                    originalView.setSelection(0, originalView.text!!.length)
                }
                val beforeFormat = inputEpochs.size
                runOnIdle { originalView.toggleInlineFormat(InlineFormat.Bold) }
                waitForIdle()
                runOnIdle {
                    assertThat(originalView.getContentAsMessageHtml()).isNotEqualTo(originalHtml)
                    assertThat(inputEpochs.size).isGreaterThan(beforeFormat)
                }
                val beforeUndo = inputEpochs.size
                runOnIdle { originalView.undo() }
                waitForIdle()
                runOnIdle {
                    assertThat(originalView.getContentAsMessageHtml()).isEqualTo(originalHtml)
                    assertThat(inputEpochs.size).isGreaterThan(beforeUndo)
                    assertThat(inputEpochs.toSet()).containsExactly(1)
                    assertThat(typingNotices).isEqualTo(0)
                    previewState.eventSink(AttachmentsPreviewEvent.SendAttachment)
                }
                session.advanceUntilIdle()
                waitForIdle()
                runOnIdle {
                    assertThat(done).isEqualTo(1)
                    assertThat(sends).isEqualTo(1)
                    assertThat((source.textEditorState as TextEditorState.Rich).richTextEditorState.messageHtml).isEqualTo(originalHtml)
                }
            } else {
                runOnIdle { visible = false }
                waitForIdle()
                runOnIdle { assertThat(activity!!.window.decorView.richEditor()).isNull() }
                runOnIdle { visible = true }
                waitForIdle()
                runOnIdle {
                    val remounted = requireNotNull(activity!!.window.decorView.richEditor())
                    assertThat(remounted).isNotSameInstanceAs(originalView)
                    assertThat(remounted.getContentAsMessageHtml()).isEqualTo(originalHtml)
                    previewState.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
                }
                session.advanceUntilIdle()
                waitForIdle()
                runOnIdle {
                    assertThat(previewState.showDraftConflict).isFalse()
                    assertThat(done).isEqualTo(1)
                    assertThat(sends).isEqualTo(0)
                    assertThat((source.textEditorState as TextEditorState.Rich).richTextEditorState.messageHtml).isEqualTo(originalHtml)
                }
            }
            runOnIdle { assertThat(errors).isEmpty() }
        } finally {
            session.cancel()
        }
    }

    private fun View.richEditor(): EditorEditText? {
        if (this is EditorEditText) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) getChildAt(index).richEditor()?.let { return it }
        }
        return null
    }

    // The common presenter fixture hardcodes fake=true. Construct the same production
    // presenter here rather than changing another owner's fixture or mocking its editor.
    private fun TestScope.realComposer(
        room: FakeJoinedRoom,
        navigator: FakeMessagesNavigator,
        drafts: AttachmentCaptionDrafts,
    ): MessageComposerPresenter {
        val parser = FakePermalinkParser()
        val slashCommands = FakeSlashCommandService()
        val uri = Uri.parse("content://synthetic/rich-caption-file")
        return MessageComposerPresenter(
            navigator = navigator,
            timelineController = TimelineController(room, room.liveTimeline),
            threadRoot = null,
            sessionCoroutineScope = this,
            room = room,
            mediaPickerProvider = FakePickerProvider().apply { givenResult(uri) },
            sessionPreferencesStore = InMemorySessionPreferencesStore(isSendTypingNotificationsEnabled = false),
            localMediaFactory = FakeLocalMediaFactory(uri),
            mediaSenderFactory = MediaSenderFactory { FakeMediaSender() },
            snackbarDispatcher = SnackbarDispatcher(),
            analyticsService = FakeAnalyticsService(),
            locationService = FakeLocationService(true),
            messageComposerContext = DefaultMessageComposerContext(),
            richTextEditorStateFactory = DefaultRichTextEditorStateFactory(),
            roomAliasSuggestionsDataSource = FakeRoomAliasSuggestionsDataSource(),
            permalinkParser = parser,
            permalinkBuilder = FakePermalinkBuilder(),
            permissionsPresenterFactory = FakePermissionsPresenterFactory(FakePermissionsPresenter()),
            draftService = FakeComposerDraftService(),
            mentionSpanProvider = MentionSpanProvider(parser, FakeMentionSpanFormatter(), MentionSpanTheme(A_USER_ID)),
            pillificationHelper = FakeTextPillificationHelper(),
            suggestionsProcessor = SuggestionsProcessor(slashCommandService = slashCommands),
            mediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
            notificationConversationService = FakeNotificationConversationService(),
            slashCommandService = slashCommands,
            featureFlagService = FakeFeatureFlagService(),
            contentScannerService = { _, _ -> },
            contentValidationCache = InMemoryEventContentValidationCache(),
            captionDrafts = drafts,
        ).apply { showTextFormatting = true }
    }
}
