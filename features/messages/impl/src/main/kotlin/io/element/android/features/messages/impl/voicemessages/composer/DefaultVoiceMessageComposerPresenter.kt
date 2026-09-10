/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import android.Manifest
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesBinding
import im.vector.app.features.analytics.plan.Composer
import io.element.android.features.messages.api.MessageComposerContext
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerPresenter
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.libraries.audio.api.AudioFocus
import io.element.android.libraries.audio.api.AudioFocusRequester
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.permissions.api.PermissionsEvent
import io.element.android.libraries.permissions.api.PermissionsPresenter
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import io.element.android.libraries.textcomposer.model.VoiceMessagePlayerEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.voiceplayer.api.VoiceMessageException
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.services.analytics.api.AnalyticsService
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

@AssistedInject
class DefaultVoiceMessageComposerPresenter(
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    @Assisted private val timelineMode: Timeline.Mode,
    private val voiceRecorder: VoiceRecorder,
    private val analyticsService: AnalyticsService,
    private val audioFocus: AudioFocus,
    mediaSenderFactory: MediaSenderFactory,
    private val player: VoiceMessageComposerPlayer,
    private val messageComposerContext: MessageComposerContext,
    permissionsPresenterFactory: PermissionsPresenter.Factory
) : VoiceMessageComposerPresenter {
    @ContributesBinding(RoomScope::class)
    @AssistedFactory
    interface Factory : VoiceMessageComposerPresenter.Factory {
        override fun create(timelineMode: Timeline.Mode): DefaultVoiceMessageComposerPresenter
    }

    private val permissionsPresenter = permissionsPresenterFactory.create(Manifest.permission.RECORD_AUDIO)
    private var waitingForPermission = false
    private var recordingReplyTo: EventId? = null

    // This presenter outlives its composition. Recording and upload ownership must do the same.
    private var starting by mutableStateOf(false)
    private var recordingActive by mutableStateOf(false)
    private var stopping by mutableStateOf(false)
    private var stopRequested: Boolean? = null
    private var isSending by mutableStateOf(false)
    private var showSendFailureDialog by mutableStateOf(false)
    private var microphoneReady by mutableStateOf(false)
    private var recordingError by mutableStateOf(false)
    private val mediaSender = mediaSenderFactory.create(timelineMode)

    @Composable
    override fun present(): VoiceMessageComposerState {
        val localCoroutineScope = rememberCoroutineScope()
        DisposableEffect(Unit) {
            onDispose {
                finishRecording()
                player.pause()
            }
        }
        val recorderState by voiceRecorder.state.collectAsState()
        val playerState by player.state.collectAsState(initial = VoiceMessageComposerPlayer.State.Initial)
        val keepScreenOn by remember { derivedStateOf { recorderState is VoiceRecorderState.Recording } }
        val permissionState by rememberUpdatedState(permissionsPresenter.present())

        LaunchedEffect(recorderState) {
            when (val recording = recorderState) {
                is VoiceRecorderState.Failure -> {
                    recordingActive = false
                    recordingError = true
                    audioFocus.releaseAudioFocus()
                }
                is VoiceRecorderState.Finished -> {
                    recordingActive = false
                    player.setMedia(recording.file.path)
                }
                else -> Unit
            }
        }

        LaunchedEffect(permissionState.permissionGranted) {
            if (permissionState.permissionGranted && waitingForPermission) {
                waitingForPermission = false
                microphoneReady = true
            }
        }

        fun handleLifecycleEvent(event: Lifecycle.Event) {
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    finishRecording()
                    player.pause()
                }
                Lifecycle.Event.ON_DESTROY -> {
                    // Navigation guards handle explicit discard. Destruction must never cancel an upload or delete a preview.
                    finishRecording()
                }
                else -> {}
            }
        }

        fun handleVoiceMessageRecorderEvent(event: VoiceMessageRecorderEvent) {
            when (event) {
                VoiceMessageRecorderEvent.Start -> {
                    Timber.v("Voice message record button pressed")
                    when {
                        permissionState.permissionGranted && !messageComposerContext.composerMode.isEditing &&
                            (voiceRecorder.state.value is VoiceRecorderState.Idle || voiceRecorder.state.value is VoiceRecorderState.Failure) &&
                            !starting && !recordingActive && !stopping && !isSending -> {
                            microphoneReady = false
                            recordingError = false
                            recordingReplyTo = (messageComposerContext.composerMode as? MessageComposerMode.Reply)?.eventId
                            starting = true
                            recordingActive = true
                            stopRequested = null
                            startRecording()
                        }
                        permissionState.permissionGranted -> Unit
                        else -> {
                            Timber.i("Voice message permission needed")
                            waitingForPermission = true
                            permissionState.eventSink(PermissionsEvent.RequestPermissions)
                        }
                    }
                }
                VoiceMessageRecorderEvent.Stop -> {
                    Timber.v("Voice message stop button pressed")
                    finishRecording()
                }
                VoiceMessageRecorderEvent.Cancel -> {
                    Timber.v("Voice message cancel button tapped")
                    finishRecording(cancelled = true)
                }
            }
        }

        fun handleVoiceMessagePlayerEvent(event: VoiceMessagePlayerEvent) {
            localCoroutineScope.launch {
                when (event) {
                    VoiceMessagePlayerEvent.Play -> player.play()
                    VoiceMessagePlayerEvent.Pause -> player.pause()
                    is VoiceMessagePlayerEvent.Seek -> player.seek(event.position)
                }
            }
        }

        fun sendVoiceMessage(inReplyToEventId: EventId?) {
            if (isSending || stopping) return
            val finishedState = voiceRecorder.state.value as? VoiceRecorderState.Finished
            if (finishedState == null || finishedState.duration <= Duration.ZERO) {
                val exception = VoiceMessageException.FileException("No file to send")
                analyticsService.trackError(exception)
                Timber.e(exception)
                return
            }
            isSending = true
            player.pause()
            analyticsService.captureComposerEvent()
            showSendFailureDialog = false
            sessionCoroutineScope.launch {
                try {
                    val result = sendMessage(
                        recording = finishedState,
                        inReplyToEventId = inReplyToEventId,
                    )
                    if (result.isFailure) showSendFailureDialog = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Voice message error")
                    showSendFailureDialog = true
                } finally {
                    isSending = false
                }
            }
        }

        fun handleEvent(event: VoiceMessageComposerEvent) {
            when (event) {
                is VoiceMessageComposerEvent.RecorderEvent -> handleVoiceMessageRecorderEvent(event.recorderEvent)
                is VoiceMessageComposerEvent.PlayerEvent -> handleVoiceMessagePlayerEvent(event.playerEvent)
                is VoiceMessageComposerEvent.SendVoiceMessage -> {
                    // Capture reply info eagerly before any coroutine dispatch, since CloseSpecialMode
                    // may reset composerMode before the coroutine runs.
                    val inReplyToEventId = recordingReplyTo
                    sendVoiceMessage(inReplyToEventId)
                }
                VoiceMessageComposerEvent.DeleteVoiceMessage -> {
                    if (!isSending) {
                        player.pause()
                        finishRecording(cancelled = true)
                    }
                }
                VoiceMessageComposerEvent.DismissPermissionsRationale -> {
                    permissionState.eventSink(PermissionsEvent.CloseDialog)
                }
                VoiceMessageComposerEvent.AcceptPermissionRationale -> {
                    permissionState.eventSink(PermissionsEvent.OpenSystemSettingAndCloseDialog)
                }
                is VoiceMessageComposerEvent.LifecycleEvent -> handleLifecycleEvent(event.event)
                VoiceMessageComposerEvent.DismissSendFailureDialog -> {
                    showSendFailureDialog = false
                }
            }
        }

        return VoiceMessageComposerState(
            voiceMessageState = when (val state = recorderState) {
                is VoiceRecorderState.Recording -> VoiceMessageState.Recording(
                    duration = state.elapsedTime,
                    levels = state.levels
                        // Keep only the last 128 samples for display, else we can have a crash
                        .takeLast(128)
                        .toImmutableList(),
                )
                is VoiceRecorderState.Finished ->
                    previewState(
                        playerState = playerState,
                        recorderState = recorderState,
                        isSending = isSending
                    )
                else -> if (starting || recordingActive || stopping) {
                    VoiceMessageState.Recording(Duration.ZERO, persistentListOf())
                } else {
                    VoiceMessageState.Idle
                }
            },
            showPermissionRationaleDialog = permissionState.showDialog,
            showSendFailureDialog = showSendFailureDialog,
            keepScreenOn = keepScreenOn || (recorderState !is VoiceRecorderState.Finished && (starting || recordingActive)),
            microphoneReady = microphoneReady,
            recordingError = recordingError,
            eventSink = ::handleEvent,
        )
    }

    @Composable
    private fun previewState(
        playerState: VoiceMessageComposerPlayer.State,
        recorderState: VoiceRecorderState,
        isSending: Boolean,
    ): VoiceMessageState {
        val showCursor by remember(playerState.isStopped, isSending) { derivedStateOf { !playerState.isStopped && !isSending } }
        val playerTime by remember(playerState, recorderState) { derivedStateOf { displayTime(playerState, recorderState) } }
        val waveform by remember(recorderState) { derivedStateOf { recorderState.finishedWaveform() } }

        return VoiceMessageState.Preview(
            isSending = isSending,
            isPlaying = playerState.isPlaying,
            showCursor = showCursor,
            playbackProgress = playerState.progress,
            time = playerTime,
            waveform = waveform,
        )
    }

    private fun startRecording() = sessionCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
        try {
            audioFocus.requestAudioFocus(AudioFocusRequester.RecordVoiceMessage) { finishRecording() }
            // Focus can be lost synchronously while acquiring it. Do not start after that stop.
            if (stopRequested == null) voiceRecorder.startRecord()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            recordingError = true
            recordingActive = false
            audioFocus.releaseAudioFocus()
            Timber.e(e, "Unable to start voice recording")
            if (e is SecurityException) {
                analyticsService.trackError(VoiceMessageException.PermissionMissing("Expected permission to record but none", e))
            }
        } finally {
            starting = false
            stopRequested?.let { finishRecording(cancelled = it) }
        }
    }

    private fun finishRecording(cancelled: Boolean = false) {
        if (isSending) return
        if (!starting && !recordingActive && voiceRecorder.state.value !is VoiceRecorderState.Recording &&
            !(cancelled && voiceRecorder.state.value is VoiceRecorderState.Finished)
        ) return
        // Explicit cancel wins over subsequent lifecycle/focus stops, including during native startup.
        stopRequested = stopRequested == true || cancelled
        if (stopping) return
        if (starting && voiceRecorder.state.value is VoiceRecorderState.Idle) return
        stopping = true
        sessionCoroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                if (recordingActive || voiceRecorder.state.value is VoiceRecorderState.Recording) {
                    voiceRecorder.stopRecord(cancelled = stopRequested == true)
                }
                if (stopRequested == true && voiceRecorder.state.value !is VoiceRecorderState.Idle) voiceRecorder.deleteRecording()
                recordingActive = false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                recordingError = true
                Timber.e(e, "Unable to finish voice recording")
            } finally {
                stopping = false
                audioFocus.releaseAudioFocus()
            }
        }
    }

    private suspend fun sendMessage(
        recording: VoiceRecorderState.Finished,
        inReplyToEventId: EventId? = null,
    ): Result<Unit> {
        val result = mediaSender.sendVoiceMessage(
            uri = recording.file.toUri(),
            mimeType = recording.mimeType,
            waveForm = recording.waveform,
            inReplyToEventId = inReplyToEventId,
        )

        if (result.isFailure) {
            Timber.e(result.exceptionOrNull(), "Voice message error")
            return result
        }

        // Never let a stale completion consume a replacement recording.
        if (voiceRecorder.state.value === recording) {
            voiceRecorder.deleteRecording()
        }

        return result
    }

    private fun AnalyticsService.captureComposerEvent() =
        capture(
            Composer(
                inThread = timelineMode is Timeline.Mode.Thread,
                isEditing = false,
                isReply = recordingReplyTo != null,
                messageType = Composer.MessageType.VoiceMessage,
            )
        )
}

private fun VoiceRecorderState.finishedWaveform(): ImmutableList<Float> =
    (this as? VoiceRecorderState.Finished)
        ?.waveform
        .orEmpty()
        .toImmutableList()

/**
 * The time to display depending on the player state.
 *
 * Either the current position or total duration.
 */
private fun displayTime(
    playerState: VoiceMessageComposerPlayer.State,
    recording: VoiceRecorderState
): Duration = when {
    !playerState.isStopped ->
        playerState.currentPosition.milliseconds
    recording is VoiceRecorderState.Finished ->
        recording.duration
    else ->
        0.milliseconds
}
