/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.runtime.Composable
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.FakeMessagesNavigator
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.AttachmentsPreviewPresenterTest
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.attachments.preview.SendActionState
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediapickers.api.NoOpPickerLauncher
import io.element.android.libraries.mediapickers.api.PickerLauncher
import io.element.android.libraries.mediapickers.api.PickerProvider
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.test
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/** Real picker -> composer -> navigation payload -> preview -> media sender -> fake timeline. */
class CaptionHandoffIntegrationTest : RobolectricTest() {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test fun `text first image sends exactly one captioned attachment`() = runTest { exercise(kind = "image") }
    @Test fun `text first video sends exactly one captioned attachment`() = runTest { exercise(kind = "video") }
    @Test fun `text first document sends exactly one captioned attachment`() = runTest { exercise(kind = "file") }
    @Test fun `camera photo uses captured draft`() = runTest { exercise(kind = "camera") }
    @Test fun `cancel restores edited caption without sending`() = runTest { exercise(action = "cancel") }
    @Test fun `newer draft conflicts on cancel and survives discard`() = runTest { exercise(action = "conflict") }
    @Test fun `equal text with newer revision survives success`() = runTest { exercise(action = "equal-newer") }
    @Test fun `failed send retains caption then retry double tap sends once`() = runTest { exercise(action = "retry") }
    @Test fun `active message edit is excluded from handoff`() = runTest { exercise(action = "edit") }

    @Test fun `native await failure is retained and one retry sends once`() = runTest { exercise(action = "retry-await") }
    @Test fun `rich equal text newer input version survives success without typing notices`() = runTest { exercise(action = "equal-newer", rich = true) }
    @Test fun `rich unchanged cancel preserves original formatting`() = runTest { exercise(action = "unchanged-cancel", rich = true) }

    private suspend fun TestScope.exercise(kind: String = "file", action: String = "send", rich: Boolean = false) {
        val drafts = AttachmentCaptionDrafts()
        val sourceText = "A caption 🌿\nsecond line"
        val editedCaption = "Reviewed caption 🌿\nsecond line"
        var picked: List<Attachment> = emptyList()
        var reply: EventId? = null
        val navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = { attachments, replyTo ->
            picked = attachments
            reply = replyTo
        })
        val picker = FakePickerProvider().apply { givenResult(Uri.parse("content://synthetic/attachment")) }
        var acceptedSends = 0
        var attemptedSends = 0
        var standaloneSends = 0
        var failSend = action.startsWith("retry")
        var observedCaption: String? = null
        fun submit(caption: String?, replyTo: EventId?): Result<FakeMediaUploadHandler> {
            attemptedSends++
            observedCaption = caption
            assertThat(replyTo).isEqualTo(AN_EVENT_ID)
            if (failSend) {
                val error = IllegalStateException("Synthetic upload failure")
                return if (action == "retry-await") Result.success(FakeMediaUploadHandler(Result.failure(error))) else Result.failure(error)
            }
            acceptedSends++
            return Result.success(FakeMediaUploadHandler())
        }
        val timeline = FakeTimeline().apply {
            sendFileLambda = { _, _, caption, _, replyTo -> submit(caption, replyTo) }
            sendImageLambda = { _, _, _, caption, _, replyTo -> submit(caption, replyTo) }
            sendVideoLambda = { _, _, _, caption, _, replyTo -> submit(caption, replyTo) }
            sendMessageLambda = { _, _, _, _, _ ->
                standaloneSends++
                Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(liveTimeline = timeline, typingNoticeResult = { Result.success(Unit) })
        val composer = with(MessageComposerPresenterTest()) {
            createPresenter(
                room = room,
                navigator = navigator,
                pickerProvider = picker,
                isRichTextEditorEnabled = rich,
                sessionPreferencesStore = io.element.android.libraries.preferences.test.InMemorySessionPreferencesStore().apply {
                    setSendTypingNotifications(false)
                },
                captionDrafts = drafts,
                permissionPresenter = FakePermissionsPresenter().apply { setPermissionGranted() },
            )
        }
        composer.test {
            skipItems(1)
            val initial = awaitItem()
            initial.eventSink(MessageComposerEvent.SetMode(if (action == "edit") anEditMode() else aReplyMode()))
            advanceUntilIdle()
            val state = expectMostRecentItem()
            state.textEditorState.setMarkdown(sourceText)
            advanceUntilIdle()
            val originalHtml = (state.textEditorState as? TextEditorState.Rich)?.richTextEditorState?.messageHtml
            state.eventSink(
                when (kind) {
                    "camera" -> MessageComposerEvent.PickAttachmentSource.PhotoFromCamera
                    "image", "video" -> MessageComposerEvent.PickAttachmentSource.FromGallery
                    else -> MessageComposerEvent.PickAttachmentSource.FromFiles
                }
            )
            advanceUntilIdle()
            assertThat(picked).hasSize(1)
            assertThat(state.textEditorState.messageMarkdown()).isEqualTo(sourceText)
            if (action == "edit") {
                assertThat(navigator.lastCaptionDraft).isNull()
                assertThat(state.mode.isEditing).isTrue()
            } else {
                val draft = requireNotNull(navigator.lastCaptionDraft)
                assertThat(draft.caption).isEqualTo(sourceText)
                assertThat(reply).isEqualTo(AN_EVENT_ID)
                val processor = FakeMediaPreProcessor().apply {
                    when (kind) {
                        "image", "camera" -> givenImageResult()
                        "video" -> givenVideoResult()
                        else -> Unit
                    }
                }
                var done = 0
                val preview = with(AttachmentsPreviewPresenterTest()) {
                    createAttachmentsPreviewPresenter(
                        room = room,
                        attachments = picked,
                        mediaPreProcessor = processor,
                        captionDraft = draft,
                        captionDrafts = drafts,
                        inReplyToEventId = reply,
                        onDoneListener = { done++ },
                    )
                }
                preview.test {
                    val previewInitial = awaitItem()
                    advanceUntilIdle()
                    assertThat(previewInitial.textEditorState.messageMarkdown()).isEqualTo(sourceText)
                    if (action != "unchanged-cancel") previewInitial.textEditorState.setMarkdown(editedCaption)
                    if (action == "conflict") state.textEditorState.setMarkdown("Newer message")
                    if (action == "equal-newer") {
                        state.textEditorState.setMarkdown("Intermediate edit")
                        state.eventSink(MessageComposerEvent.InputChanged)
                        state.textEditorState.setMarkdown(sourceText)
                        state.eventSink(MessageComposerEvent.InputChanged)
                        advanceUntilIdle()
                    }
                    if (action == "cancel" || action == "conflict" || action == "unchanged-cancel") {
                        previewInitial.eventSink(AttachmentsPreviewEvent.CancelAndDismiss)
                        advanceUntilIdle()
                        val cancelled = expectMostRecentItem()
                        if (action == "conflict") {
                            assertThat(cancelled.showDraftConflict).isTrue()
                            assertThat(done).isEqualTo(0)
                            cancelled.eventSink(AttachmentsPreviewEvent.KeepEditingDraft)
                            advanceUntilIdle()
                            assertThat(expectMostRecentItem().textEditorState.messageMarkdown()).isEqualTo(editedCaption)
                            cancelled.eventSink(AttachmentsPreviewEvent.DiscardAttachmentDraft)
                            advanceUntilIdle()
                            assertThat(state.textEditorState.messageMarkdown()).isEqualTo("Newer message")
                        } else if (action == "unchanged-cancel") {
                            assertThat((state.textEditorState as TextEditorState.Rich).richTextEditorState.messageHtml).isEqualTo(originalHtml)
                        } else {
                            assertThat(state.textEditorState.messageMarkdown()).isEqualTo(editedCaption)
                        }
                        assertThat(acceptedSends).isEqualTo(0)
                    } else {
                        previewInitial.eventSink(AttachmentsPreviewEvent.SendAttachment)
                        previewInitial.eventSink(AttachmentsPreviewEvent.SendAttachment)
                        advanceUntilIdle()
                        if (action.startsWith("retry")) {
                            val failed = expectMostRecentItem()
                            assertThat(failed.sendActionState).isInstanceOf(SendActionState.Failure::class.java)
                            assertThat(failed.textEditorState.messageMarkdown()).isEqualTo(editedCaption)
                            assertThat(state.textEditorState.messageMarkdown()).isEqualTo(sourceText)
                            assertThat(done).isEqualTo(0)
                            failSend = false
                            failed.eventSink(AttachmentsPreviewEvent.SendAttachment)
                            failed.eventSink(AttachmentsPreviewEvent.SendAttachment)
                            advanceUntilIdle()
                        }
                        assertThat(acceptedSends).isEqualTo(1)
                        assertThat(attemptedSends).isEqualTo(if (action.startsWith("retry")) 2 else 1)
                        assertThat(observedCaption).isEqualTo(editedCaption)
                        assertThat(state.textEditorState.messageMarkdown()).isEqualTo(if (action == "equal-newer") sourceText else "")
                    }
                    assertThat(done).isEqualTo(1)
                    cancelAndIgnoreRemainingEvents()
                }
            }
            assertThat(standaloneSends).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `draft captured before asynchronous picker return`() = runTest {
        val picker = DelayedGalleryPicker()
        val navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = { _, _ -> })
        val composer = with(MessageComposerPresenterTest()) {
            createPresenter(navigator = navigator, pickerProvider = picker, isRichTextEditorEnabled = false)
        }
        composer.test {
            skipItems(1)
            val state = awaitItem()
            state.textEditorState.setMarkdown("Before picker")
            state.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()
            state.textEditorState.setMarkdown("After picker")
            picker.deliver()
            assertThat(navigator.lastCaptionDraft?.caption).isEqualTo("Before picker")
            assertThat(state.textEditorState.messageMarkdown()).isEqualTo("After picker")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `preview remount keeps edited caption and single flight upload ownership`() = runTest {
        val upload = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        var done = 0
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline().apply {
            sendFileLambda = { _, _, caption, _, _ ->
                calls++
                assertThat(caption).isEqualTo("Reviewed caption")
                Result.success(FakeMediaUploadHandler(awaitResult = {
                    upload.await()
                    Result.success(Unit)
                }))
            }
        })
        val preview = with(AttachmentsPreviewPresenterTest()) {
            createAttachmentsPreviewPresenter(room = room, onDoneListener = { done++ })
        }
        preview.test {
            val initial = awaitItem()
            initial.textEditorState.setMarkdown("Reviewed caption")
            initial.eventSink(AttachmentsPreviewEvent.SendAttachment)
            advanceUntilIdle()
            assertThat(calls).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
        preview.test {
            val restored = awaitItem()
            assertThat(restored.textEditorState.messageMarkdown()).isEqualTo("Reviewed caption")
            restored.eventSink(AttachmentsPreviewEvent.SendAttachment)
            advanceUntilIdle()
            assertThat(calls).isEqualTo(1)
            upload.complete(Unit)
            advanceUntilIdle()
            assertThat(done).isEqualTo(1)
            assertThat(expectMostRecentItem().sendActionState).isEqualTo(SendActionState.Done)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `empty multi attachment success consumes only captured reply lease`() = runTest {
        val drafts = AttachmentCaptionDrafts()
        val navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = { _, _ -> })
        val picker = FakePickerProvider().apply {
            givenMultipleResults(listOf(Uri.parse("content://synthetic/one"), Uri.parse("content://synthetic/two")))
        }
        val flags = io.element.android.libraries.featureflag.test.FakeFeatureFlagService().apply {
            setFeatureEnabled(io.element.android.libraries.featureflag.api.FeatureFlags.SendGalleryMessages, true)
        }
        val composer = with(MessageComposerPresenterTest()) {
            createPresenter(navigator = navigator, pickerProvider = picker, featureFlagService = flags, captionDrafts = drafts, isRichTextEditorEnabled = false)
        }
        composer.test {
            skipItems(1)
            awaitItem().eventSink(MessageComposerEvent.SetMode(aReplyMode()))
            advanceUntilIdle()
            val replying = expectMostRecentItem()
            replying.eventSink(MessageComposerEvent.PickAttachmentSource.FromGallery)
            advanceUntilIdle()
            val draft = requireNotNull(navigator.lastCaptionDraft)
            assertThat(draft.caption).isEmpty()
            assertThat(replying.mode.isReply).isTrue()
            drafts.consume(draft)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().mode.isReply).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun `progress cancellation stops the preparation owner and remount can retry once`() = runTest {
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val processor = FakeMediaPreProcessor(gate)
        var sends = 0
        var done = 0
        val room = FakeJoinedRoom(liveTimeline = FakeTimeline().apply {
            sendFileLambda = { _, _, caption, _, _ ->
                sends++
                assertThat(caption).isEqualTo("Retained caption")
                Result.success(FakeMediaUploadHandler())
            }
        })
        val preview = with(AttachmentsPreviewPresenterTest()) {
            createAttachmentsPreviewPresenter(room = room, mediaPreProcessor = processor, onDoneListener = { done++ })
        }
        preview.test {
            val initial = awaitItem()
            initial.textEditorState.setMarkdown("Retained caption")
            initial.eventSink(AttachmentsPreviewEvent.SendAttachment)
            advanceUntilIdle()
            initial.eventSink(AttachmentsPreviewEvent.CancelAndClearSendState)
            advanceUntilIdle()
            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(expectMostRecentItem().sendActionState).isEqualTo(SendActionState.Idle)
            assertThat(processor.processCallCount).isEqualTo(0)
            assertThat(sends).isEqualTo(0)
            assertThat(done).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
        preview.test {
            val restored = awaitItem()
            restored.eventSink(AttachmentsPreviewEvent.SendAttachment)
            restored.eventSink(AttachmentsPreviewEvent.SendAttachment)
            advanceUntilIdle()
            assertThat(processor.processCallCount).isEqualTo(1)
            assertThat(sends).isEqualTo(1)
            assertThat(done).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun TextEditorState.messageMarkdown(): String = when (this) {
        is TextEditorState.Markdown -> state.text.value().toString()
        is TextEditorState.Rich -> richTextEditorState.messageMarkdown
    }

    private class DelayedGalleryPicker : PickerProvider by FakePickerProvider() {
        private var callback: ((Uri?, String?) -> Unit)? = null
        @Composable
        override fun registerGalleryPicker(onResult: (Uri?, String?) -> Unit): PickerLauncher<PickVisualMediaRequest, Uri?> {
            callback = onResult
            return NoOpPickerLauncher { }
        }
        fun deliver() {
            requireNotNull(callback)(Uri.parse("content://synthetic/photo"), "image/jpeg")
        }
    }
}
