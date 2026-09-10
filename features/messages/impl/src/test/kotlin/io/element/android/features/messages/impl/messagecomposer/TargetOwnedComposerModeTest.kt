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
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.impl.FakeMessagesNavigator
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.AttachmentsPreviewPresenterTest
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewEvent
import io.element.android.features.messages.impl.draft.DefaultComposerDraftService
import io.element.android.features.messages.impl.draft.MatrixComposerDraftStore
import io.element.android.features.messages.impl.draft.VolatileComposerDraftStore
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.features.messages.impl.voicemessages.composer.DefaultVoiceMessageComposerPresenter
import io.element.android.features.messages.impl.voicemessages.composer.VoiceMessageComposerPlayer
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.room.draft.ComposerDraft
import io.element.android.libraries.matrix.api.room.draft.ComposerDraftType
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.api.timeline.TimelineException
import io.element.android.libraries.matrix.api.timeline.item.event.toEventOrTransactionId
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.media.FakeMediaUploadHandler
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.matrix.ui.messages.reply.InReplyToDetails
import io.element.android.libraries.mediapickers.test.FakePickerProvider
import io.element.android.libraries.mediaplayer.test.FakeAudioFocus
import io.element.android.libraries.mediaplayer.test.FakeMediaPlayer
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.test.FakeMediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.TextEditorState
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.voicerecorder.test.FakeVoiceRecorder
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.Closeable
import kotlin.time.Duration.Companion.seconds

/**
 * Two production text composers share ONE room context and production draft service.
 * SaveableStateHolder exercises removal/return; native storage, timelines, picker and
 * recorder remain controlled boundaries. Caption preview and voice use production senders.
 * This is not a node/backstack, generated room-graph, or native-network test.
 */
class TargetOwnedComposerModeTest : RobolectricTest() {
    @get:Rule val warmUpRule = WarmUpRule()

    @Test
    fun `live edit to empty B sends new thread text and Back restores A edit`() = runTest {
        exerciseTextSwitch(ComposerDraftType.Edit(EVENT_A))
    }

    @Test
    fun `live reply to empty B sends new thread text and Back restores A reply`() = runTest {
        exerciseTextSwitch(ComposerDraftType.Reply(EVENT_A))
    }

    @Test
    fun `B own reply draft sends against B while Back restores A edit`() = runTest {
        exerciseTextSwitch(ComposerDraftType.Edit(EVENT_A), ComposerDraftType.Reply(EVENT_B))
    }

    @Test
    fun `B own edit draft edits only B while Back restores A reply`() = runTest {
        exerciseTextSwitch(ComposerDraftType.Reply(EVENT_A), ComposerDraftType.Edit(EVENT_B))
    }

    @Test
    fun `B own normal draft cannot reset A edit`() = runTest {
        exerciseTextSwitch(ComposerDraftType.Edit(EVENT_A), ComposerDraftType.NewMessage)
    }

    private suspend fun TestScope.exerciseTextSwitch(origin: ComposerDraftType, destination: ComposerDraftType? = null) {
        ModeFixture(destination?.let { ComposerDraft(B_DRAFT, null, it) }).use { fixture ->
            val a = createComposer(fixture, inThread = false)
            val b = createComposer(fixture, inThread = true)
            var showB by mutableStateOf(false)
            var aDisposals = 0
            moleculeFlow(RecompositionMode.Immediate) {
                val holder = rememberSaveableStateHolder()
                var current: MessageComposerState? = null
                holder.SaveableStateProvider(if (showB) "thread-b" else "live-a") {
                    if (!showB) DisposableEffect(Unit) { onDispose { aDisposals++ } }
                    current = (if (showB) b else a).present()
                }
                requireNotNull(current)
            }.test {
                awaitItem()
                advanceUntilIdle()
                var state = expectMostRecentItem()
                state.eventSink(MessageComposerEvent.SetMode(origin.mode()))
                advanceUntilIdle()
                state = expectMostRecentItem()
                state.textEditorState.setMarkdown(A_DRAFT)
                state.eventSink(MessageComposerEvent.SaveDraft)
                advanceUntilIdle()
                assertMode(state.mode, origin)
                assertThat(fixture.saved[null]).isEqualTo(ComposerDraft(A_DRAFT, null, origin))
                val originalEditor = (state.textEditorState as TextEditorState.Markdown).state

                showB = true
                advanceUntilIdle()
                state = expectMostRecentItem()
                assertThat(aDisposals).isEqualTo(1)
                assertThat(state.isInThreadTimeline).isTrue()
                assertThat(state.text()).isEqualTo(if (destination == null) "" else B_DRAFT)
                assertMode(state.mode, destination ?: ComposerDraftType.NewMessage)
                assertMode(fixture.context.composerMode, origin)
                state.textEditorState.setMarkdown("Send in B")
                state.eventSink(MessageComposerEvent.SendMessage)
                advanceUntilIdle()
                assertThat(fixture.calls).containsExactly(
                    when (destination) {
                        is ComposerDraftType.Edit -> "B:edit:${EVENT_B.value}:Send in B"
                        is ComposerDraftType.Reply -> "B:reply:${EVENT_B.value}:Send in B"
                        else -> "B:new:Send in B"
                    }
                )
                assertMode(fixture.context.composerMode, origin)

                showB = false
                advanceUntilIdle()
                state = expectMostRecentItem()
                assertThat(state.isInThreadTimeline).isFalse()
                assertThat((state.textEditorState as TextEditorState.Markdown).state).isNotSameInstanceAs(originalEditor)
                assertThat(state.text()).isEqualTo(A_DRAFT)
                assertMode(state.mode, origin)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `delayed B draft hydration cannot change mounted A reply or text`() = runTest {
        val gate = CompletableDeferred<Unit>()
        ModeFixture(ComposerDraft(B_DRAFT, null, ComposerDraftType.Edit(EVENT_B)), gate).use { fixture ->
            val a = createComposer(fixture, inThread = false)
            val b = createComposer(fixture, inThread = true)
            moleculeFlow(RecompositionMode.Immediate) { a.present() to b.present() }.test {
                awaitItem()
                advanceUntilIdle()
                val initial = expectMostRecentItem()
                assertThat(fixture.waitingForBDraft).isTrue()
                initial.first.eventSink(MessageComposerEvent.SetMode(ComposerDraftType.Reply(EVENT_A).mode()))
                advanceUntilIdle()
                val replying = expectMostRecentItem()
                replying.first.textEditorState.setMarkdown(A_DRAFT)
                assertMode(replying.first.mode, ComposerDraftType.Reply(EVENT_A))
                assertThat(replying.second.text()).isEmpty()
                assertMode(replying.second.mode, ComposerDraftType.NewMessage)

                gate.complete(Unit)
                advanceUntilIdle()
                val hydrated = expectMostRecentItem()
                assertMode(hydrated.first.mode, ComposerDraftType.Reply(EVENT_A))
                assertThat(hydrated.first.text()).isEqualTo(A_DRAFT)
                assertMode(hydrated.second.mode, ComposerDraftType.Edit(EVENT_B))
                assertThat(hydrated.second.text()).isEqualTo(B_DRAFT)
                hydrated.second.textEditorState.setMarkdown("B edited text")
                hydrated.second.eventSink(MessageComposerEvent.SendMessage)
                advanceUntilIdle()
                assertThat(fixture.calls).containsExactly("B:edit:${EVENT_B.value}:B edited text")
                assertMode(expectMostRecentItem().first.mode, ComposerDraftType.Reply(EVENT_A))
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `B caption with no own reply never inherits live reply A`() = runTest {
        exerciseCaption(null)
    }

    @Test
    fun `B caption uses B own stored reply and consumption preserves live A`() = runTest {
        exerciseCaption(ComposerDraftType.Reply(EVENT_B))
    }

    @Test
    fun `live edit does not exclude B from caption handoff`() = runTest {
        exerciseCaption(null, ComposerDraftType.Edit(EVENT_A))
    }

    private suspend fun TestScope.exerciseCaption(
        destination: ComposerDraftType.Reply?,
        origin: ComposerDraftType = ComposerDraftType.Reply(EVENT_A),
    ) {
        ModeFixture(destination?.let { ComposerDraft(B_DRAFT, null, it) }).use { fixture ->
            var picked = emptyList<Attachment>()
            var pickedReply: EventId? = EVENT_A
            val navigator = FakeMessagesNavigator(onPreviewAttachmentLambda = { attachments, reply ->
                picked = attachments
                pickedReply = reply
            })
            val a = createComposer(fixture, inThread = false)
            val b = createComposer(fixture, inThread = true, navigator = navigator)
            moleculeFlow(RecompositionMode.Immediate) { a.present() to b.present() }.test {
                awaitItem()
                advanceUntilIdle()
                var state = expectMostRecentItem()
                state.first.eventSink(MessageComposerEvent.SetMode(origin.mode()))
                advanceUntilIdle()
                state = expectMostRecentItem()
                state.first.textEditorState.setMarkdown(A_DRAFT)
                state.second.textEditorState.setMarkdown(B_DRAFT)
                state.second.eventSink(MessageComposerEvent.PickAttachmentSource.FromFiles)
                advanceUntilIdle()
                assertThat(picked).hasSize(1)
                assertThat(pickedReply).isEqualTo(destination?.eventId)
                val draft = requireNotNull(navigator.lastCaptionDraft)
                assertThat(draft.caption).isEqualTo(B_DRAFT)
                val host = this
                var done = 0
                val preview = with(AttachmentsPreviewPresenterTest()) {
                    createAttachmentsPreviewPresenter(
                        room = fixture.room,
                        timelineMode = fixture.thread.mode,
                        attachments = picked,
                        captionDraft = draft,
                        captionDrafts = fixture.captions,
                        inReplyToEventId = pickedReply,
                        onDoneListener = { done++ },
                    )
                }
                preview.test {
                    val previewState = awaitItem()
                    advanceUntilIdle()
                    previewState.textEditorState.setMarkdown("Reviewed B caption")
                    previewState.eventSink(AttachmentsPreviewEvent.SendAttachment)
                    advanceUntilIdle()
                    assertThat(done).isEqualTo(1)
                    assertThat(fixture.calls).containsExactly("B:file:${destination?.eventId?.value}:Reviewed B caption")
                    // A normal-mode caption may only mutate the existing editor, with no new mode emission.
                    val after = if (destination == null) state else host.expectMostRecentItem()
                    assertThat(after.second.text()).isEmpty()
                    assertMode(after.second.mode, ComposerDraftType.NewMessage)
                    assertThat(after.first.text()).isEqualTo(A_DRAFT)
                    assertMode(after.first.mode, origin)
                    cancelAndIgnoreRemainingEvents()
                }
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `live editing blocks its own voice but does not block B recording`() = runTest {
        exerciseVoice(ComposerDraftType.Edit(EVENT_A), null)
    }

    @Test
    fun `B voice without own reply never inherits live reply A`() = runTest {
        exerciseVoice(ComposerDraftType.Reply(EVENT_A), null)
    }

    @Test
    fun `B voice captures own reply before text mode changes and sends to B`() = runTest {
        exerciseVoice(ComposerDraftType.Reply(EVENT_A), ComposerDraftType.Reply(EVENT_B))
    }

    private suspend fun TestScope.exerciseVoice(origin: ComposerDraftType, destination: ComposerDraftType.Reply?) {
        ModeFixture(destination?.let { ComposerDraft(B_DRAFT, null, it) }).use { fixture ->
            val a = createComposer(fixture, inThread = false)
            val b = createComposer(fixture, inThread = true)
            moleculeFlow(RecompositionMode.Immediate) { a.present() to b.present() }.test {
                awaitItem()
                advanceUntilIdle()
                expectMostRecentItem().first.eventSink(MessageComposerEvent.SetMode(origin.mode()))
                advanceUntilIdle()
                val state = expectMostRecentItem()
                state.first.textEditorState.setMarkdown(A_DRAFT)
                state.second.textEditorState.setMarkdown(B_DRAFT)
                if (origin is ComposerDraftType.Edit) {
                    var liveStarts = 0
                    createVoicePresenter(fixture, Timeline.Mode.Live) { liveStarts++ }.test {
                        awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
                        advanceUntilIdle()
                        assertThat(liveStarts).isEqualTo(0)
                        cancelAndIgnoreRemainingEvents()
                    }
                }
                var threadStarts = 0
                createVoicePresenter(fixture, fixture.thread.mode) { threadStarts++ }.test {
                    awaitItem().eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Start))
                    advanceUntilIdle()
                    val recording = expectMostRecentItem()
                    assertThat(threadStarts).isEqualTo(1)
                    assertThat(recording.voiceMessageState).isInstanceOf(VoiceMessageState.Recording::class.java)
                    recording.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
                    advanceUntilIdle()
                    val preview = expectMostRecentItem()
                    assertThat(preview.voiceMessageState).isInstanceOf(VoiceMessageState.Preview::class.java)
                    // Changing B after Start must not retarget the already captured recording.
                    state.second.eventSink(MessageComposerEvent.CloseSpecialMode)
                    advanceUntilIdle()
                    preview.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
                    advanceUntilIdle()
                    assertThat(fixture.calls).containsExactly("B:voice:${destination?.eventId?.value}")
                    assertThat(expectMostRecentItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
                    cancelAndIgnoreRemainingEvents()
                }
                assertThat(state.first.text()).isEqualTo(A_DRAFT)
                assertThat(state.second.text()).isEqualTo(B_DRAFT)
                assertMode(fixture.context.composerMode, origin)
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    private fun TestScope.createComposer(
        fixture: ModeFixture,
        inThread: Boolean,
        navigator: FakeMessagesNavigator = FakeMessagesNavigator(),
    ): MessageComposerPresenter = with(MessageComposerPresenterTest()) {
        createPresenter(
            room = fixture.room,
            timelineController = if (inThread) fixture.threadController else fixture.liveController,
            threadRoot = THREAD_B.takeIf { inThread },
            messageComposerContext = fixture.context,
            draftService = fixture.drafts,
            captionDrafts = fixture.captions,
            navigator = navigator,
            pickerProvider = FakePickerProvider().apply { givenResult(Uri.parse("content://synthetic/b-document")) },
            isRichTextEditorEnabled = false,
        )
    }

    private fun TestScope.createVoicePresenter(fixture: ModeFixture, mode: Timeline.Mode, onStart: () -> Unit) =
        DefaultVoiceMessageComposerPresenter(
            sessionCoroutineScope = this,
            timelineMode = mode,
            voiceRecorder = FakeVoiceRecorder(
                recordingDuration = 1.seconds,
                startRecordResult = onStart,
                stopRecordResult = {},
                deleteRecordingResult = {},
            ),
            analyticsService = FakeAnalyticsService(),
            audioFocus = FakeAudioFocus(requestAudioFocusResult = { _, _ -> }, releaseAudioFocusResult = {}),
            mediaSenderFactory = { target ->
                DefaultMediaSender(
                    preProcessor = FakeMediaPreProcessor().apply { givenAudioResult() },
                    room = fixture.room,
                    timelineMode = target,
                    mediaOptimizationConfigProvider = FakeMediaOptimizationConfigProvider(),
                )
            },
            player = VoiceMessageComposerPlayer(FakeMediaPlayer(), this),
            messageComposerContext = fixture.context,
            permissionsPresenterFactory = FakePermissionsPresenterFactory(FakePermissionsPresenter().apply { setPermissionGranted() }),
        )

    private fun MessageComposerState.text(): String = (textEditorState as TextEditorState.Markdown).state.text.value().toString()

    private fun ComposerDraftType.mode(): MessageComposerMode = when (this) {
        ComposerDraftType.NewMessage -> MessageComposerMode.Normal
        is ComposerDraftType.Edit -> anEditMode(eventId.toEventOrTransactionId(), "Original message")
        is ComposerDraftType.Reply -> MessageComposerMode.Reply(InReplyToDetails.Loading(eventId), hideImage = false)
    }

    private fun assertMode(actual: MessageComposerMode, expected: ComposerDraftType) {
        when (expected) {
            ComposerDraftType.NewMessage -> assertThat(actual).isEqualTo(MessageComposerMode.Normal)
            is ComposerDraftType.Edit -> {
                assertThat(actual).isInstanceOf(MessageComposerMode.Edit::class.java)
                assertThat((actual as MessageComposerMode.Edit).eventOrTransactionId.eventId).isEqualTo(expected.eventId)
            }
            is ComposerDraftType.Reply -> {
                assertThat(actual).isInstanceOf(MessageComposerMode.Reply::class.java)
                assertThat((actual as MessageComposerMode.Reply).eventId).isEqualTo(expected.eventId)
            }
        }
    }

    private class ModeFixture(bDraft: ComposerDraft? = null, private val bLoadGate: CompletableDeferred<Unit>? = null) : Closeable {
        val context = DefaultMessageComposerContext()
        val captions = AttachmentCaptionDrafts()
        val calls = mutableListOf<String>()
        val saved = mutableMapOf<ThreadId?, ComposerDraft>().apply { if (bDraft != null) put(THREAD_B, bDraft) }
        var waitingForBDraft = false
        val live = timeline("A", Timeline.Mode.Live)
        val thread = timeline("B", Timeline.Mode.Thread(THREAD_B))
        private val nativeRoom = FakeJoinedRoom(
            liveTimeline = live,
            createTimelineResult = { params ->
                assertThat(params).isEqualTo(CreateTimelineParams.Threaded(THREAD_B))
                Result.success(thread)
            },
            typingNoticeResult = { Result.success(Unit) },
            editMessageLambda = { id, body, _, _ ->
                calls += "room:edit:${id.value}:$body"
                Result.success(Unit)
            },
        )

        // Keep SDK persistence as the fake boundary, not the production draft service.
        val room = object : JoinedRoom by nativeRoom {
            override suspend fun loadComposerDraft(threadRoot: ThreadId?): Result<ComposerDraft?> {
                if (threadRoot == THREAD_B && bLoadGate != null) {
                    waitingForBDraft = true
                    bLoadGate.await()
                    waitingForBDraft = false
                }
                return Result.success(saved[threadRoot])
            }

            override suspend fun saveComposerDraft(composerDraft: ComposerDraft, threadRoot: ThreadId?): Result<Unit> {
                saved[threadRoot] = composerDraft
                return Result.success(Unit)
            }

            override suspend fun clearComposerDraft(threadRoot: ThreadId?): Result<Unit> {
                saved.remove(threadRoot)
                return Result.success(Unit)
            }
        }
        val drafts = DefaultComposerDraftService(
            VolatileComposerDraftStore(),
            MatrixComposerDraftStore(FakeMatrixClient().apply { givenGetRoomResult(room.roomId, room) }),
        )
        val liveController = TimelineController(room, live)
        val threadController = TimelineController(room, thread)

        private fun timeline(name: String, mode: Timeline.Mode) = FakeTimeline(mode = mode).apply {
            sendMessageLambda = { body, _, _, _, _ ->
                calls += "$name:new:$body"
                Result.success(Unit)
            }
            replyMessageLambda = { reply, body, _, _, _, _ ->
                calls += "$name:reply:${reply?.value}:$body"
                Result.success(Unit)
            }
            editMessageLambda = { id, body, _, _ ->
                calls += "$name:edit:${id.eventId?.value}:$body"
                // Exercise the dangerous room.editMessage fallback if an A edit leaks to B.
                if (name == "B" && id.eventId == EVENT_A) Result.failure(TimelineException.EventNotFound) else Result.success(Unit)
            }
            sendFileLambda = { _, _, caption, _, reply ->
                calls += "$name:file:${reply?.value}:$caption"
                Result.success(FakeMediaUploadHandler())
            }
            sendVoiceMessageLambda = { _, _, _, reply ->
                calls += "$name:voice:${reply?.value}"
                Result.success(FakeMediaUploadHandler())
            }
        }

        override fun close() {
            liveController.close()
            threadController.close()
        }
    }

    private companion object {
        val EVENT_A = EventId("\$live-event-a")
        val EVENT_B = EventId("\$thread-b-replied-event")
        val THREAD_B = ThreadId("\$thread-b-root")
        const val A_DRAFT = "A unsent text 🌿"
        const val B_DRAFT = "B own text 🌱"
    }
}
