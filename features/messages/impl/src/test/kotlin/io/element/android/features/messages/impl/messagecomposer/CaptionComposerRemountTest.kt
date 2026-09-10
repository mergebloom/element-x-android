/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.messagecomposer

import android.net.Uri
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import app.cash.molecule.RecompositionMode
import app.cash.molecule.moleculeFlow
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.impl.FakeMessagesNavigator
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.AttachmentsPreviewPresenterTest
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.draft.ComposerDraftService
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

/**
 * Original composer is actually removed from a SaveableStateHolder, not merely hidden
 * or kept alive alongside the preview. Picker, both presenters and media sender are
 * production code; storage, picker result, preprocessing and timeline are test boundaries.
 */
class CaptionComposerRemountTest : RobolectricTest() {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test fun `disposed composer success overrides stale saved text after persistent clear`() = runTest {
        exercise()
    }

    @Test fun `disposed composer success overrides stale saved text while persistent clear is pending`() = runTest {
        exercise(deferWrite = true)
    }

    @Test fun `disposed composer edited cancel overrides stale saved text after persistent restore`() = runTest {
        exercise(cancel = true)
    }

    @Test fun `disposed composer edited cancel overrides stale saved text while persistent restore is pending`() = runTest {
        exercise(cancel = true, deferWrite = true)
    }

    @Test fun `remounted composer equal text newer revision survives preview success`() = runTest {
        exercise(newerBeforeResult = true)
    }

    @Test fun `remounted composer newer revision conflicts on cancel and survives discard`() = runTest {
        exercise(cancel = true, newerBeforeResult = true)
    }

    @Test fun `newer saved draft survives pending caption clear and a second remount`() = runTest {
        exercise(deferWrite = true, newerAfterResult = true)
    }

    @Test fun `newer saved draft survives pending caption restore and a second remount`() = runTest {
        exercise(cancel = true, deferWrite = true, newerAfterResult = true)
    }

    private suspend fun TestScope.exercise(
        cancel: Boolean = false,
        deferWrite: Boolean = false,
        newerBeforeResult: Boolean = false,
        newerAfterResult: Boolean = false,
    ) {
        val sourceText = "Original caption 🌿\nsecond line"
        val editedCaption = "Reviewed caption 🌿\nsecond line"
        val newerText = "Newer independent draft"
        val threadRoot = ThreadId("\$caption-remount-thread")
        val drafts = AttachmentCaptionDrafts()
        val store = DeferredDraftService()
        var picked: List<Attachment> = emptyList()
        var pickedReply: EventId? = null
        val navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = { attachments, reply ->
            picked = attachments
            pickedReply = reply
        })
        var attachmentSends = 0
        var standaloneSends = 0
        var parentSends = 0
        val timeline = FakeTimeline(mode = Timeline.Mode.Thread(threadRoot)).apply {
            sendFileLambda = { _, _, caption, _, reply ->
                attachmentSends++
                assertThat(caption).isEqualTo(editedCaption)
                assertThat(reply).isEqualTo(AN_EVENT_ID)
                Result.success(FakeMediaUploadHandler())
            }
            sendMessageLambda = { _, _, _, _, _ ->
                standaloneSends++
                Result.success(Unit)
            }
        }
        val parentTimeline = FakeTimeline().apply {
            sendFileLambda = { _, _, _, _, _ ->
                parentSends++
                Result.success(FakeMediaUploadHandler())
            }
            sendMessageLambda = { _, _, _, _, _ ->
                standaloneSends++
                Result.success(Unit)
            }
        }
        val room = FakeJoinedRoom(
            liveTimeline = parentTimeline,
            createTimelineResult = { params ->
                assertThat(params).isEqualTo(CreateTimelineParams.Threaded(threadRoot))
                Result.success(timeline)
            },
            typingNoticeResult = { Result.success(Unit) },
        )
        val unrelatedDraft = ComposerDraft("Unrelated room draft", null, ComposerDraftType.NewMessage)
        store.values[room.roomId to null] = unrelatedDraft
        val composer = with(MessageComposerPresenterTest()) {
            createPresenter(
                room = room,
                timeline = timeline,
                navigator = navigator,
                pickerProvider = FakePickerProvider().apply { givenResult(Uri.parse("content://synthetic/document")) },
                draftService = store,
                captionDrafts = drafts,
                threadRoot = threadRoot,
                isRichTextEditorEnabled = false,
            )
        }
        var visible by mutableStateOf(true)
        var disposals = 0
        var current by mutableStateOf<MessageComposerState?>(null)
        moleculeFlow(RecompositionMode.Immediate) {
            val holder = rememberSaveableStateHolder()
            if (visible) {
                holder.SaveableStateProvider("original-composer") {
                    DisposableEffect(Unit) { onDispose { disposals++ } }
                    current = composer.present()
                }
            } else {
                current = null
            }
            current
        }.test {
            var source = requireNotNull(awaitItem())
            advanceUntilIdle()
            source.eventSink(MessageComposerEvent.SetMode(aReplyMode()))
            advanceUntilIdle()
            source = requireNotNull(expectMostRecentItem())
            source.textEditorState.setMarkdown(sourceText)
            source.eventSink(MessageComposerEvent.SaveDraft)
            advanceUntilIdle()
            assertThat(store.loadDraft(room.roomId, threadRoot, false)?.plainText).isEqualTo(sourceText)
            source.eventSink(MessageComposerEvent.PickAttachmentSource.FromFiles)
            advanceUntilIdle()
            assertThat(picked).hasSize(1)
            assertThat(pickedReply).isEqualTo(AN_EVENT_ID)
            val payload = requireNotNull(navigator.lastCaptionDraft)
            assertThat(payload.caption).isEqualTo(sourceText)
            val originalEditor = (source.textEditorState as TextEditorState.Markdown).state
            visible = false
            advanceUntilIdle()
            assertThat(expectMostRecentItem()).isNull()
            assertThat(disposals).isEqualTo(1)

            var done = 0
            val preview = with(AttachmentsPreviewPresenterTest()) {
                createAttachmentsPreviewPresenter(
                    room = room,
                    timelineMode = timeline.mode,
                    attachments = picked,
                    captionDraft = payload,
                    captionDrafts = drafts,
                    inReplyToEventId = pickedReply,
                    onDoneListener = { done++ },
                )
            }
            // Keep the host turbine separate: only the preview is composed here.
            val host = this
            preview.test {
                val previewState = awaitItem()
                advanceUntilIdle()
                previewState.textEditorState.setMarkdown(editedCaption)
                if (newerBeforeResult) {
                    visible = true
                    advanceUntilIdle()
                    source = requireNotNull(host.expectMostRecentItem())
                    assertThat((source.textEditorState as TextEditorState.Markdown).state).isNotSameInstanceAs(originalEditor)
                    assertThat(source.text()).isEqualTo(sourceText)
                    // Equal content is still a newer revision, without manually injecting InputChanged.
                    source.textEditorState.setMarkdown("Intermediate edit")
                    source.textEditorState.setMarkdown(sourceText)
                    source.eventSink(MessageComposerEvent.SaveDraft)
                    advanceUntilIdle()
                    visible = false
                    advanceUntilIdle()
                    assertThat(host.expectMostRecentItem()).isNull()
                }
                val gate = if (deferWrite) store.deferNextWrite() else null
                previewState.eventSink(if (cancel) AttachmentsPreviewEvent.CancelAndDismiss else AttachmentsPreviewEvent.SendAttachment)
                advanceUntilIdle()
                if (cancel && newerBeforeResult) {
                    val conflict = expectMostRecentItem()
                    assertThat(conflict.showDraftConflict).isTrue()
                    assertThat(done).isEqualTo(0)
                    conflict.eventSink(AttachmentsPreviewEvent.DiscardAttachmentDraft)
                    advanceUntilIdle()
                }
                assertThat(done).isEqualTo(1)
                assertThat(attachmentSends).isEqualTo(if (cancel) 0 else 1)
                if (deferWrite) {
                    assertThat(store.waitingWrites).isEqualTo(1)
                    assertThat(store.loadDraft(room.roomId, threadRoot, false)?.plainText).isEqualTo(sourceText)
                } else if (!newerBeforeResult && !cancel) {
                    // The important null-load case: there is no persistent draft to overwrite stale UI.
                    assertThat(store.loadDraft(room.roomId, threadRoot, false)).isNull()
                }
                visible = true
                advanceUntilIdle()
                source = requireNotNull(host.expectMostRecentItem())
                assertThat((source.textEditorState as TextEditorState.Markdown).state).isNotSameInstanceAs(originalEditor)
                val expected = if (newerBeforeResult) {
                    sourceText
                } else if (cancel) {
                    editedCaption
                } else {
                    ""
                }
                assertThat(source.text()).isEqualTo(expected)
                assertThat(source.mode.isReply).isEqualTo(cancel || newerBeforeResult)
                if (!cancel && !newerBeforeResult) assertThat(source.mode).isEqualTo(MessageComposerMode.Normal)
                if (newerAfterResult) {
                    source.textEditorState.setMarkdown(newerText)
                    source.eventSink(MessageComposerEvent.SaveDraft)
                    advanceUntilIdle()
                    // The newer save must wait behind the old clear/restore, not commit before it.
                    assertThat(store.loadDraft(room.roomId, threadRoot, false)?.plainText).isEqualTo(sourceText)
                    visible = false
                    advanceUntilIdle()
                    assertThat(host.expectMostRecentItem()).isNull()
                    visible = true
                    advanceUntilIdle()
                    source = requireNotNull(host.expectMostRecentItem())
                    assertThat(source.text()).isEqualTo(newerText)
                }
                gate?.complete(Unit)
                advanceUntilIdle()
                val expectedSaved = if (newerAfterResult) newerText else expected
                val reloaded = store.loadDraft(room.roomId, threadRoot, false)
                assertThat(reloaded?.plainText.orEmpty()).isEqualTo(expectedSaved)
                if (reloaded != null) {
                    assertThat(reloaded.draftType).isEqualTo(
                        if (cancel || newerBeforeResult) ComposerDraftType.Reply(AN_EVENT_ID) else ComposerDraftType.NewMessage
                    )
                }
                assertThat(source.text()).isEqualTo(expectedSaved)
                assertThat(store.values[room.roomId to null]).isEqualTo(unrelatedDraft)
                assertThat(standaloneSends).isEqualTo(0)
                assertThat(parentSends).isEqualTo(0)
                cancelAndIgnoreRemainingEvents()
            }
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun MessageComposerState.text(): String = (textEditorState as TextEditorState.Markdown).state.text.value().toString()

    private class DeferredDraftService : ComposerDraftService {
        val values = mutableMapOf<Pair<RoomId, ThreadId?>, ComposerDraft?>()
        private var nextWrite: CompletableDeferred<Unit>? = null
        var waitingWrites = 0
            private set

        fun deferNextWrite(): CompletableDeferred<Unit> = CompletableDeferred<Unit>().also { nextWrite = it }

        override suspend fun loadDraft(roomId: RoomId, threadRoot: ThreadId?, isVolatile: Boolean): ComposerDraft? {
            check(!isVolatile)
            return values[roomId to threadRoot]
        }

        override suspend fun updateDraft(roomId: RoomId, threadRoot: ThreadId?, draft: ComposerDraft?, isVolatile: Boolean) {
            check(!isVolatile)
            val gate = nextWrite
            nextWrite = null
            if (gate != null) {
                waitingWrites++
                try {
                    gate.await()
                } finally {
                    waitingWrites--
                }
            }
            values[roomId to threadRoot] = draft
        }
    }
}
