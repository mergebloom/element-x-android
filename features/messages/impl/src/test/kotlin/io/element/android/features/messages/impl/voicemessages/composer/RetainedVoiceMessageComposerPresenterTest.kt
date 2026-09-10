/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.voicemessages.composer

import android.Manifest
import android.media.AudioManager
import androidx.core.content.getSystemService
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.impl.DefaultAudioFocus
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import androidx.lifecycle.Lifecycle
import app.cash.turbine.TurbineTestContext
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.features.messages.impl.messagecomposer.aReplyMode
import io.element.android.features.messages.test.FakeMessageComposerContext
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.media.AudioInfo
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.AN_EVENT_ID
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaplayer.test.FakeAudioFocus
import io.element.android.libraries.mediaplayer.test.FakeMediaPlayer
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaSender
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.impl.DefaultMediaSender
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.permissions.api.aPermissionsState
import io.element.android.libraries.permissions.test.FakePermissionsPresenter
import io.element.android.libraries.permissions.test.FakePermissionsPresenterFactory
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.services.analytics.test.FakeAnalyticsService
import io.element.android.tests.testutils.WarmUpRule
import io.element.android.tests.testutils.test
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.registerInstanceFactory
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.time.Duration.Companion.seconds

class RetainedVoiceMessageComposerPresenterTest : RobolectricTest() {
    init {
        registerInstanceFactory { io.element.android.libraries.matrix.test.AN_EVENT_ID }
        registerInstanceFactory { io.element.android.libraries.matrix.api.core.ThreadId(io.element.android.libraries.matrix.test.AN_EVENT_ID.value) }
        registerInstanceFactory { io.element.android.libraries.matrix.test.A_USER_ID }
        registerInstanceFactory { io.element.android.libraries.matrix.test.A_ROOM_ID }
    }
    @get:Rule val warmUpRule = WarmUpRule()
    @get:Rule val temporaryFolder = TemporaryFolder()

    private val context = FakeMessageComposerContext()
    private val sender = mockk<MediaSender>()
    private var onFocusLost: () -> Unit = {}
    private var focusReleases = 0

    @Test
    fun `pending send survives disposal and remount then failure is retryable`() = runTest {
        val recorder = recorder()
        val upload = CompletableDeferred<Result<Unit>>()
        coEvery { sender.sendVoiceMessage(any(), any(), any(), any()) } coAnswers { upload.await() }
        val presenter = presenter(recorder)
        presenter.test {
            recordToPreview().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            awaitState { (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == true }
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        presenter.test {
            val sending = awaitState { (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == true }
            sending.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            sending.eventSink(VoiceMessageComposerEvent.DeleteVoiceMessage)
            sending.eventSink(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Cancel))
            sending.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_DESTROY))
            runCurrent()
            coVerify(exactly = 1) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            assertThat(recorder.file.exists()).isTrue()
            upload.complete(Result.failure(IllegalStateException("upload failed")))
            val failed = awaitState { it.showSendFailureDialog && (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == false }
            assertThat(recorder.deletions).isEqualTo(0)
            coEvery { sender.sendVoiceMessage(any(), any(), any(), any()) } returns Result.success(Unit)
            failed.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            awaitState { it.voiceMessageState is VoiceMessageState.Idle }
            coVerify(exactly = 2) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            assertThat(recorder.deletions).isEqualTo(1)
            assertThat(recorder.file.exists()).isFalse()
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `thrown upload failure releases single flight and preserves the draft`() = runTest {
        val recorder = recorder()
        coEvery { sender.sendVoiceMessage(any(), any(), any(), any()) } throws IllegalStateException("sender failure")
        presenter(recorder).test {
            recordToPreview().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            val failed = awaitState { it.showSendFailureDialog && (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == false }
            assertThat(recorder.file.exists()).isTrue()
            coEvery { sender.sendVoiceMessage(any(), any(), any(), any()) } returns Result.success(Unit)
            failed.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            awaitState { it.voiceMessageState is VoiceMessageState.Idle }
            coVerify(exactly = 2) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            assertThat(recorder.deletions).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `success while detached is visible on remount without another upload`() = runTest {
        val recorder = recorder()
        val upload = CompletableDeferred<Result<Unit>>()
        coEvery { sender.sendVoiceMessage(any(), any(), any(), any()) } coAnswers { upload.await() }
        val presenter = presenter(recorder)
        presenter.test {
            recordToPreview().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            awaitState { (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == true }
            runCurrent()
            cancelAndIgnoreRemainingEvents()
        }
        upload.complete(Result.success(Unit))
        runCurrent()
        presenter.test {
            assertThat(awaitItem().voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(recorder.deletions).isEqualTo(1)
            coVerify(exactly = 1) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `stale upload success cannot delete a replacement at the same file path`() = runTest {
        val recorder = recorder()
        val upload = CompletableDeferred<Result<Unit>>()
        coEvery { sender.sendVoiceMessage(any(), any(), any(), any()) } coAnswers { upload.await() }
        val presenter = presenter(recorder)
        presenter.test {
            recordToPreview().eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            awaitState { (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == true }
            runCurrent()
            val old = recorder.state.value as VoiceRecorderState.Finished
            // A different recording identity can reuse a pathname; equality of files is insufficient.
            recorder.state.value = old.copy(duration = 2.seconds)
            upload.complete(Result.success(Unit))
            awaitState { (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == false }
            assertThat(recorder.state.value).isInstanceOf(VoiceRecorderState.Finished::class.java)
            assertThat(recorder.deletions).isEqualTo(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `background after native start returns but before first buffer stops owned recording`() = runTest {
        for (event in listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_DESTROY)) {
            val recorder = recorder()
            presenter(recorder).test {
                awaitItem().record(VoiceMessageRecorderEvent.Start)
                val starting = awaitState { it.voiceMessageState is VoiceMessageState.Recording }
                assertThat(recorder.state.value).isEqualTo(VoiceRecorderState.Idle)
                starting.eventSink(VoiceMessageComposerEvent.LifecycleEvent(event))
                awaitState { it.voiceMessageState is VoiceMessageState.Preview }
                recorder.firstBuffer()
                assertThat(recorder.stops).containsExactly(false)
                assertThat(recorder.state.value).isInstanceOf(VoiceRecorderState.Finished::class.java)
                assertThat(recorder.file.exists()).isTrue()
                coVerify(exactly = 0) { sender.sendVoiceMessage(any(), any(), any(), any()) }
                cancelAndIgnoreRemainingEvents()
            }
        }
    }

    @Test
    fun `background while native start is suspended stops after its return`() = runTest {
        val recorder = recorder().apply { startGate = CompletableDeferred() }
        presenter(recorder).test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            val starting = awaitState { it.voiceMessageState is VoiceMessageState.Recording }
            starting.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            assertThat(recorder.stops).isEmpty()
            recorder.startGate!!.complete(Unit)
            awaitState { it.voiceMessageState is VoiceMessageState.Preview }
            assertThat(recorder.stops).containsExactly(false)
            assertThat(recorder.file.exists()).isTrue()
            coVerify(exactly = 0) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `production focus listener stops pending and populated recordings without lifecycle pause`() = runTest {
        val androidContext = RuntimeEnvironment.getApplication()
        val manager = requireNotNull(androidContext.getSystemService<AudioManager>())
        for (pending in listOf(false, true)) {
            for (loss in listOf(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT, AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK)) {
                val recorder = recorder().apply { if (pending) startGate = CompletableDeferred() }
                presenter(recorder, audioFocus = DefaultAudioFocus(androidContext)).test {
                    awaitItem().record(VoiceMessageRecorderEvent.Start)
                    awaitState { it.voiceMessageState is VoiceMessageState.Recording }
                    if (!pending) recorder.firstBuffer()
                    requireNotNull(shadowOf(manager).lastAudioFocusRequest).listener.onAudioFocusChange(loss)
                    recorder.startGate?.complete(Unit)
                    awaitState { it.voiceMessageState is VoiceMessageState.Preview }
                    assertThat(recorder.stops).containsExactly(false)
                    assertThat(recorder.file.exists()).isTrue()
                    coVerify(exactly = 0) { sender.sendVoiceMessage(any(), any(), any(), any()) }
                    cancelAndIgnoreRemainingEvents()
                }
            }
        }
    }

    @Test
    fun `audio focus loss before first buffer never leaves microphone active`() = runTest {
        val recorder = recorder()
        presenter(recorder).test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            awaitState { it.voiceMessageState is VoiceMessageState.Recording }
            onFocusLost()
            awaitState { it.voiceMessageState is VoiceMessageState.Preview }
            recorder.firstBuffer()
            assertThat(recorder.stops).containsExactly(false)
            assertThat(focusReleases).isGreaterThan(0)
            coVerify(exactly = 0) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `cancel wins over background while native start is suspended`() = runTest {
        val recorder = recorder().apply { startGate = CompletableDeferred() }
        presenter(recorder).test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            val starting = awaitState { it.voiceMessageState is VoiceMessageState.Recording }
            starting.record(VoiceMessageRecorderEvent.Cancel)
            starting.eventSink(VoiceMessageComposerEvent.LifecycleEvent(Lifecycle.Event.ON_PAUSE))
            onFocusLost()
            recorder.startGate!!.complete(Unit)
            awaitState { it.voiceMessageState is VoiceMessageState.Idle }
            assertThat(recorder.stops).containsExactly(true)
            assertThat(recorder.file.exists()).isFalse()
            coVerify(exactly = 0) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `composition removal during startup stops to preview after native return`() = runTest {
        val recorder = recorder().apply { startGate = CompletableDeferred() }
        val presenter = presenter(recorder)
        presenter.test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            awaitState { it.voiceMessageState is VoiceMessageState.Recording }
            cancelAndIgnoreRemainingEvents()
        }
        recorder.startGate!!.complete(Unit)
        runCurrent()
        presenter.test {
            awaitState { it.voiceMessageState is VoiceMessageState.Preview }
            assertThat(recorder.stops).containsExactly(false)
            assertThat(recorder.file.exists()).isTrue()
            coVerify(exactly = 0) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `startup navigation Keep preserves original recording and Discard waits for deletion`() = runTest {
        val recorder = recorder().apply { startGate = CompletableDeferred() }
        val guard = VoiceDraftNavigationGuard()
        var navigations = 0
        presenter(recorder).test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            guard.update(awaitState { it.voiceMessageState is VoiceMessageState.Recording })
            guard.navigate { navigations++ }
            assertThat(guard.showConfirmation).isTrue()
            guard.keep()
            recorder.startGate!!.complete(Unit)
            val preview = awaitState { it.voiceMessageState is VoiceMessageState.Preview }
            guard.update(preview)
            assertThat(navigations).isEqualTo(0)
            assertThat(recorder.file.exists()).isTrue()
            guard.navigate { navigations++ }
            guard.discard()
            assertThat(navigations).isEqualTo(0)
            guard.update(awaitState { it.voiceMessageState is VoiceMessageState.Idle })
            assertThat(navigations).isEqualTo(1)
            assertThat(recorder.file.exists()).isFalse()
            coVerify(exactly = 0) { sender.sendVoiceMessage(any(), any(), any(), any()) }
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `native worker failure is surfaced and allows a fresh activation`() = runTest {
        val recorder = recorder()
        presenter(recorder).test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            awaitState { it.voiceMessageState is VoiceMessageState.Recording }
            recorder.state.value = VoiceRecorderState.Failure(SecurityException("native startup"))
            val failure = awaitState { it.recordingError && it.voiceMessageState is VoiceMessageState.Idle }
            failure.record(VoiceMessageRecorderEvent.Start)
            awaitState { !it.recordingError && it.voiceMessageState is VoiceMessageState.Recording }
            assertThat(recorder.starts).isEqualTo(2)
            assertThat(focusReleases).isGreaterThan(0)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `first permission grant shows ready and requires fresh activation`() = runTest {
        val permissions =
            FakePermissionsPresenter(aPermissionsState(permission = Manifest.permission.RECORD_AUDIO, permissionGranted = false, showDialog = false))
        val recorder = recorder()
        presenter(recorder, permissions = permissions).test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            permissions.setPermissionGranted()
            val ready = awaitState { it.microphoneReady }
            assertThat(ready.voiceMessageState).isEqualTo(VoiceMessageState.Idle)
            assertThat(recorder.starts).isEqualTo(0)
            ready.record(VoiceMessageRecorderEvent.Start)
            awaitState { it.voiceMessageState is VoiceMessageState.Recording && !it.microphoneReady }
            assertThat(recorder.starts).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `real sender retains original thread reply and file through handler await failure and retry`() = runTest {
        val recorder = recorder().apply { startGate = CompletableDeferred() }
        val thread = Timeline.Mode.Thread(ThreadId("\$voice-thread"))
        val nativeStarted = Channel<Unit>(Channel.UNLIMITED)
        val completion = Channel<Result<Unit>>(Channel.UNLIMITED)
        val replies = mutableListOf<EventId?>()
        val timeline = FakeTimeline(mode = thread).apply {
            sendVoiceMessageLambda = { file, info, waveform, reply ->
                assertThat(info.duration).isEqualTo(1.seconds)
                assertThat(info.mimetype).isEqualTo("audio/ogg")
                assertThat(waveform).containsExactly(0.2f, 0.7f).inOrder()
                replies += reply
                Result.success(object : MediaUploadHandler {
                    override suspend fun await(): Result<Unit> {
                        nativeStarted.send(Unit)
                        return completion.receive().also { file.delete() }
                    }
                    override fun cancel() = Unit
                })
            }
        }
        val room = FakeJoinedRoom(createTimelineResult = {
            assertThat(it).isEqualTo(CreateTimelineParams.Threaded(thread.threadRootId))
            Result.success(timeline)
        })
        val preProcessor = FakeMediaPreProcessor()
        fun prepareAttempt() {
            val prepared = temporaryFolder.newFile().apply { writeBytes(recorder.file.readBytes()) }
            preProcessor.givenResult(Result.success(MediaUploadInfo.Audio(prepared, AudioInfo(1.seconds, prepared.length(), "audio/ogg"))))
        }
        val actualSender = DefaultMediaSender(
            preProcessor,
            room,
            thread,
            { MediaOptimizationConfig(true, VideoCompressionPreset.STANDARD) },
        )
        val presenter = presenter(recorder, actualSender, thread)
        context.composerMode = aReplyMode()
        presenter.test {
            awaitItem().record(VoiceMessageRecorderEvent.Start)
            val starting = awaitState { it.voiceMessageState is VoiceMessageState.Recording }
            context.composerMode = MessageComposerMode.Normal
            recorder.startGate!!.complete(Unit)
            runCurrent()
            starting.record(VoiceMessageRecorderEvent.Stop)
            val preview = awaitState { it.voiceMessageState is VoiceMessageState.Preview }
            prepareAttempt()
            preview.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            nativeStarted.receive()
            completion.send(Result.failure(IllegalStateException("native join failed")))
            val failed = awaitState { it.showSendFailureDialog && (it.voiceMessageState as? VoiceMessageState.Preview)?.isSending == false }
            assertThat(recorder.file.readText()).isEqualTo("original recording")
            prepareAttempt()
            failed.eventSink(VoiceMessageComposerEvent.SendVoiceMessage)
            nativeStarted.receive()
            completion.send(Result.success(Unit))
            awaitState { it.voiceMessageState is VoiceMessageState.Idle }
            assertThat(replies).containsExactly(AN_EVENT_ID, AN_EVENT_ID)
            assertThat(recorder.deletions).isEqualTo(1)
            cancelAndIgnoreRemainingEvents()
        }
    }

    private fun recorder() = ControlledRecorder(temporaryFolder.newFile().apply { writeText("original recording") })

    private fun TestScope.presenter(
        recorder: VoiceRecorder,
        mediaSender: MediaSender = sender,
        timelineMode: Timeline.Mode = Timeline.Mode.Live,
        permissions: FakePermissionsPresenter = FakePermissionsPresenter(
            aPermissionsState(permission = Manifest.permission.RECORD_AUDIO, permissionGranted = true, showDialog = false)
        ),
        audioFocus: AudioFocus = FakeAudioFocus(
            requestAudioFocusResult = { _, lost -> onFocusLost = lost },
            releaseAudioFocusResult = { focusReleases++ },
        ),
    ) = DefaultVoiceMessageComposerPresenter(
        sessionCoroutineScope = backgroundScope,
        timelineMode = timelineMode,
        voiceRecorder = recorder,
        analyticsService = FakeAnalyticsService(),
        audioFocus = audioFocus,
        mediaSenderFactory = { mode ->
            assertThat(mode).isEqualTo(timelineMode)
            mediaSender
        },
        player = VoiceMessageComposerPlayer(FakeMediaPlayer(), this),
        messageComposerContext = context,
        permissionsPresenterFactory = FakePermissionsPresenterFactory(permissions),
    )

    private suspend fun TurbineTestContext<VoiceMessageComposerState>.recordToPreview(): VoiceMessageComposerState {
        awaitItem().record(VoiceMessageRecorderEvent.Start)
        awaitState { it.voiceMessageState is VoiceMessageState.Recording }.record(VoiceMessageRecorderEvent.Stop)
        return awaitState { it.voiceMessageState is VoiceMessageState.Preview }
    }

    private suspend fun TurbineTestContext<VoiceMessageComposerState>.awaitState(
        predicate: (VoiceMessageComposerState) -> Boolean,
    ): VoiceMessageComposerState {
        while (true) {
            val state = awaitItem()
            if (predicate(state)) return state
        }
    }

    private fun VoiceMessageComposerState.record(event: VoiceMessageRecorderEvent) = eventSink(VoiceMessageComposerEvent.RecorderEvent(event))

    /** Matches the native contract: start can return before the first state/audio buffer. */
    private class ControlledRecorder(val file: File) : VoiceRecorder {
        override val state = MutableStateFlow<VoiceRecorderState>(VoiceRecorderState.Idle)
        var startGate: CompletableDeferred<Unit>? = null
        var starts = 0
        var deletions = 0
        val stops = mutableListOf<Boolean>()
        private var active = false
        override suspend fun startRecord() {
            starts++
            active = true
            state.value = VoiceRecorderState.Idle
            startGate?.await()
        }
        fun firstBuffer() {
            if (active) state.value = VoiceRecorderState.Recording(1.seconds, listOf(0.2f, 0.7f))
        }
        override suspend fun stopRecord(cancelled: Boolean) {
            stops += cancelled
            active = false
            if (cancelled) deleteRecording() else state.value = VoiceRecorderState.Finished(file, "audio/ogg", listOf(0.2f, 0.7f), 1.seconds)
        }
        override suspend fun deleteRecording() {
            deletions++
            active = false
            file.delete()
            state.value = VoiceRecorderState.Idle
        }
    }
}
