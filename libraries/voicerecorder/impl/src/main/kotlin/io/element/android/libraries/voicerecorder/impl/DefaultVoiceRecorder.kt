/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.voicerecorder.impl

import android.Manifest
import androidx.annotation.RequiresPermission
import dev.zacsweers.metro.ContributesBinding
import io.element.android.appconfig.VoiceMessageConfig
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.coroutine.childScope
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.voicerecorder.api.VoiceRecorder
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.libraries.voicerecorder.impl.audio.Audio
import io.element.android.libraries.voicerecorder.impl.audio.AudioConfig
import io.element.android.libraries.voicerecorder.impl.audio.AudioLevelCalculator
import io.element.android.libraries.voicerecorder.impl.audio.AudioReader
import io.element.android.libraries.voicerecorder.impl.audio.Encoder
import io.element.android.libraries.voicerecorder.impl.audio.resample
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileConfig
import io.element.android.libraries.voicerecorder.impl.file.VoiceFileManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import timber.log.Timber
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

@ContributesBinding(RoomScope::class)
class DefaultVoiceRecorder(
    private val dispatchers: CoroutineDispatchers,
    private val timeSource: TimeSource,
    private val audioReaderFactory: AudioReader.Factory,
    private val encoder: Encoder,
    private val fileManager: VoiceFileManager,
    private val config: AudioConfig,
    private val fileConfig: VoiceFileConfig,
    private val audioLevelCalculator: AudioLevelCalculator,
    @SessionCoroutineScope
    sessionCoroutineScope: CoroutineScope,
) : VoiceRecorder {
    private val voiceCoroutineScope by lazy {
        sessionCoroutineScope.childScope(dispatchers.io, "VoiceRecorder-${UUID.randomUUID()}")
    }

    private var outputFile: File? = null
    private var audioReader: AudioReader? = null
    private var recordingJob: Job? = null

    // List of Float between 0 and 1 representing the audio levels
    private val levels: MutableList<Float> = mutableListOf()
    private val lock = Mutex()

    private val _state = MutableStateFlow<VoiceRecorderState>(VoiceRecorderState.Idle)
    override val state: StateFlow<VoiceRecorderState> = _state

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override suspend fun startRecord(): Unit = lock.withLock {
        if (recordingJob != null) {
            Timber.w("Voice recorder is already recording, ignoring this start")
            return@withLock
        }

        Timber.i("Voice recorder started recording")
        try {
            val file = fileManager.createFile()
            outputFile = file
            encoder.init(file)
            levels.clear()
            val audioRecorder = audioReaderFactory.create(config, dispatchers).also { audioReader = it }
            // Establish ownership before returning, not on the first microphone buffer.
            _state.value = VoiceRecorderState.Recording(0.milliseconds, emptyList())
            recordingJob = voiceCoroutineScope.launch(start = CoroutineStart.LAZY) {
                val owner = currentCoroutineContext()[Job]
                try {
                    currentCoroutineContext().ensureActive()
                    val startedAt = timeSource.markNow()
                    audioRecorder.record { audio ->
                        yield()
                        val elapsedTime = startedAt.elapsedNow()
                        if (elapsedTime > VoiceMessageConfig.maxVoiceMessageDuration) {
                            stopRecord(false)
                            return@record
                        }
                        lock.withLock audioBuffer@{
                            if (recordingJob !== owner) return@audioBuffer
                            when (audio) {
                                is Audio.Data -> {
                                    encoder.encode(audio.buffer, audio.readSize)
                                    levels.add(audioLevelCalculator.calculateAudioLevel(audio.buffer))
                                    _state.value = VoiceRecorderState.Recording(elapsedTime, levels.toList())
                                }
                                is Audio.Error -> {
                                    Timber.e("Voice message error: code=${audio.audioRecordErrorCode}")
                                    _state.value = VoiceRecorderState.Recording(elapsedTime, emptyList())
                                }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // AudioRecord.startRecording runs here, after startRecord has returned.
                    // Do not crash the session or leave a phantom active recording on worker failure.
                    withContext(NonCancellable) {
                        lock.withLock {
                            if (recordingJob === owner) {
                                Timber.e(e, "Voice recorder worker failed")
                                releaseRecorder()
                                if (levels.isEmpty()) {
                                    deleteFile()
                                    _state.value = VoiceRecorderState.Failure(e)
                                } else {
                                    publishFinished()
                                }
                            }
                        }
                    }
                }
            }
            recordingJob?.start()
        } catch (e: Exception) {
            releaseRecorder()
            deleteFile()
            _state.value = VoiceRecorderState.Failure(e)
            throw e
        }
    }

    override suspend fun stopRecord(cancelled: Boolean) = lock.withLock {
        releaseRecorder()
        if (cancelled) {
            deleteFile()
            _state.value = VoiceRecorderState.Idle
        } else if (_state.value is VoiceRecorderState.Recording) {
            publishFinished()
        }
    }

    /** All resource and encoder access is serialized, including late buffers and explicit deletion. */
    override suspend fun deleteRecording() = lock.withLock {
        releaseRecorder()
        deleteFile()
        _state.value = VoiceRecorderState.Idle
    }

    private fun releaseRecorder() {
        recordingJob?.cancel()
        recordingJob = null
        runCatching { audioReader?.stop() }.onFailure { Timber.e(it, "Unable to stop audio reader") }
        audioReader = null
        runCatching { encoder.release() }.onFailure { Timber.e(it, "Unable to release voice encoder") }
    }

    private fun deleteFile() {
        outputFile?.let(fileManager::deleteFile)
        outputFile = null
        levels.clear()
    }

    private fun publishFinished() {
        val file = outputFile
        _state.value = if (file == null) {
            VoiceRecorderState.Idle
        } else {
            VoiceRecorderState.Finished(
                file = file,
                mimeType = fileConfig.mimeType,
                waveform = levels.resample(100),
                duration = (state.value as? VoiceRecorderState.Recording)?.elapsedTime ?: 0.milliseconds,
            )
        }
    }
}
