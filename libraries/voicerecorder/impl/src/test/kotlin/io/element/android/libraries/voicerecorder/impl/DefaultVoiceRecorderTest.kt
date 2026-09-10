/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.libraries.voicerecorder.impl

import android.media.AudioFormat
import android.media.MediaRecorder
import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import io.element.android.appconfig.VoiceMessageConfig
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.voicerecorder.api.VoiceRecorderState
import io.element.android.libraries.voicerecorder.impl.audio.Audio
import io.element.android.libraries.voicerecorder.impl.audio.AudioConfig
import io.element.android.libraries.voicerecorder.impl.audio.AudioReader
import io.element.android.libraries.voicerecorder.impl.audio.SampleRate
import io.element.android.libraries.voicerecorder.impl.di.VoiceRecorderBindingContainer
import io.element.android.libraries.voicerecorder.test.FakeAudioLevelCalculator
import io.element.android.libraries.voicerecorder.test.FakeAudioReaderFactory
import io.element.android.libraries.voicerecorder.test.FakeEncoder
import io.element.android.libraries.voicerecorder.test.FakeFileSystem
import io.element.android.libraries.voicerecorder.test.FakeVoiceFileManager
import io.element.android.tests.testutils.testCoroutineDispatchers
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class DefaultVoiceRecorderTest {
    private val fakeFileSystem = FakeFileSystem()
    private val timeSource = TestTimeSource()
    private val audioReaderFactory = FakeAudioReaderFactory(audio = AUDIO)

    @Test
    fun `it emits the initial state`() = runTest {
        val voiceRecorder = createDefaultVoiceRecorder()
        voiceRecorder.state.test {
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Idle)
        }
    }

    @Test
    fun `when recording, it emits the recording state`() = runTest {
        val voiceRecorder = createDefaultVoiceRecorder()
        voiceRecorder.state.test {
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Idle)

            voiceRecorder.startRecord()
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(0.seconds, emptyList()))
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(0.seconds, listOf(1.0f)))
            timeSource += 1.seconds
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(1.seconds, listOf()))
            timeSource += 1.seconds
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(2.seconds, listOf(1.0f, 1.0f)))
        }
    }

    @Test
    fun `when elapsed time reaches 30 minutes, it stops recording`() = runTest {
        val voiceRecorder = createDefaultVoiceRecorder()
        voiceRecorder.state.test {
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Idle)

            voiceRecorder.startRecord()
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(0.seconds, emptyList()))
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(0.minutes, listOf(1.0f)))
            timeSource += VoiceMessageConfig.maxVoiceMessageDuration
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(VoiceMessageConfig.maxVoiceMessageDuration, listOf()))
            timeSource += 1.milliseconds

            assertThat(awaitItem()).isEqualTo(
                VoiceRecorderState.Finished(
                    file = File(FILE_PATH),
                    mimeType = MimeTypes.Ogg,
                    waveform = List(100) { 1f },
                    duration = VoiceMessageConfig.maxVoiceMessageDuration,
                )
            )
        }
    }

    @Test
    fun `when stopped, it provides a file and duration`() = runTest {
        val voiceRecorder = createDefaultVoiceRecorder()
        voiceRecorder.state.test {
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Idle)

            voiceRecorder.startRecord()
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(0.seconds, emptyList()))
            skipItems(1)
            timeSource += 5.seconds
            skipItems(2)
            voiceRecorder.stopRecord()
            assertThat(awaitItem()).isEqualTo(
                VoiceRecorderState.Finished(
                    file = File(FILE_PATH),
                    mimeType = MimeTypes.Ogg,
                    waveform = List(100) { 1f },
                    duration = 5.seconds,
                )
            )
            assertThat(fakeFileSystem.files[File(FILE_PATH)]).isEqualTo(ENCODED_DATA)
        }
    }

    @Test
    fun `when startRecord is called twice, the second call is ignored`() = runTest {
        val voiceRecorder = createDefaultVoiceRecorder()
        voiceRecorder.state.test {
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Idle)

            voiceRecorder.startRecord()
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(0.seconds, emptyList()))
            voiceRecorder.startRecord()
            assertThat(audioReaderFactory.createdCount).isEqualTo(1)

            skipItems(1)
            timeSource += 5.seconds
            skipItems(2)
            voiceRecorder.stopRecord()
            assertThat(awaitItem()).isEqualTo(
                VoiceRecorderState.Finished(
                    file = File(FILE_PATH),
                    mimeType = MimeTypes.Ogg,
                    waveform = List(100) { 1f },
                    duration = 5.seconds,
                )
            )
            expectNoEvents()
        }
    }

    @Test
    fun `when cancelled, it deletes the file`() = runTest {
        val voiceRecorder = createDefaultVoiceRecorder()
        voiceRecorder.state.test {
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Idle)

            voiceRecorder.startRecord()
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Recording(0.seconds, emptyList()))
            skipItems(3)
            voiceRecorder.stopRecord(cancelled = true)
            assertThat(awaitItem()).isEqualTo(VoiceRecorderState.Idle)
            assertThat(fakeFileSystem.files[File(FILE_PATH)]).isNull()
        }
    }

    @Test
    fun `stop before first buffer cancels the native worker and retains the empty preview`() = runTest {
        val reader = DelayedAudioReader()
        val recorder = createDefaultVoiceRecorder(reader.factory())
        recorder.startRecord()
        assertThat(recorder.state.value).isEqualTo(VoiceRecorderState.Recording(0.seconds, emptyList()))
        runCurrent()
        assertThat(reader.started).isTrue()
        recorder.stopRecord(false)
        reader.firstBuffer.complete(Unit)
        runCurrent()
        assertThat(reader.stops).isEqualTo(1)
        assertThat(reader.deliveredBuffers).isEqualTo(0)
        assertThat(recorder.state.value).isInstanceOf(VoiceRecorderState.Finished::class.java)
        assertThat((recorder.state.value as VoiceRecorderState.Finished).duration).isEqualTo(0.seconds)
    }

    @Test
    fun `cancel before native worker starts cannot later activate audio`() = runTest {
        val reader = DelayedAudioReader()
        val recorder = createDefaultVoiceRecorder(reader.factory())
        recorder.startRecord()
        recorder.stopRecord(true)
        reader.firstBuffer.complete(Unit)
        runCurrent()
        assertThat(reader.started).isFalse()
        assertThat(recorder.state.value).isEqualTo(VoiceRecorderState.Idle)
        assertThat(fakeFileSystem.files[File(FILE_PATH)]).isNull()
    }

    @Test
    fun `native startRecording exception is observable without crashing and a fresh start works`() = runTest {
        val exception = SecurityException("native startRecording failed")
        val reader = DelayedAudioReader().apply { failure = exception }
        val recorder = createDefaultVoiceRecorder(reader.factory())
        recorder.startRecord()
        runCurrent()
        assertThat(recorder.state.value).isEqualTo(VoiceRecorderState.Failure(exception))
        assertThat(reader.stops).isEqualTo(1)
        assertThat(fakeFileSystem.files[File(FILE_PATH)]).isNull()
        reader.failure = null
        recorder.startRecord()
        runCurrent()
        assertThat(recorder.state.value).isInstanceOf(VoiceRecorderState.Recording::class.java)
        recorder.stopRecord(true)
        assertThat(recorder.state.value).isEqualTo(VoiceRecorderState.Idle)
    }

    @Test
    fun `deleting an active recorder prevents late buffer publication`() = runTest {
        val reader = DelayedAudioReader()
        val recorder = createDefaultVoiceRecorder(reader.factory())
        recorder.startRecord()
        runCurrent()
        recorder.deleteRecording()
        reader.firstBuffer.complete(Unit)
        runCurrent()
        assertThat(recorder.state.value).isEqualTo(VoiceRecorderState.Idle)
        assertThat(reader.deliveredBuffers).isEqualTo(0)
        assertThat(fakeFileSystem.files[File(FILE_PATH)]).isNull()
    }

    private class DelayedAudioReader : AudioReader {
        val firstBuffer = CompletableDeferred<Unit>()
        var started = false
        var stops = 0
        var deliveredBuffers = 0
        var failure: Exception? = null
        override suspend fun record(onAudio: suspend (Audio) -> Unit) {
            started = true
            failure?.let { throw it }
            firstBuffer.await()
            deliveredBuffers++
            onAudio(Audio.Data(1, shortArrayOf(1)))
            awaitCancellation()
        }
        override fun stop() {
            stops++
        }
        fun factory() = object : AudioReader.Factory {
            override fun create(config: AudioConfig, dispatchers: CoroutineDispatchers): AudioReader = this@DelayedAudioReader
        }
    }

    private fun TestScope.createDefaultVoiceRecorder(
        readerFactory: AudioReader.Factory = audioReaderFactory,
    ): DefaultVoiceRecorder {
        val fileConfig = VoiceRecorderBindingContainer.providesVoiceFileConfig()
        return DefaultVoiceRecorder(
            dispatchers = testCoroutineDispatchers(),
            timeSource = timeSource,
            audioReaderFactory = readerFactory,
            encoder = FakeEncoder(fakeFileSystem),
            config = AudioConfig(
                format = audioFormat,
                // 24 kbps
                bitRate = 24_000,
                sampleRate = SampleRate,
                source = MediaRecorder.AudioSource.MIC,
            ),
            fileConfig = fileConfig,
            fileManager = FakeVoiceFileManager(fakeFileSystem, fileConfig, FILE_ID),
            audioLevelCalculator = FakeAudioLevelCalculator(),
            sessionCoroutineScope = backgroundScope,
        )
    }

    companion object {
        const val FILE_ID: String = "recording"
        const val FILE_PATH = "voice_recordings/$FILE_ID.ogg"
        private lateinit var audioFormat: AudioFormat

        // FakeEncoder doesn't actually encode, it just writes the data to the file
        private const val ENCODED_DATA = "[32767, 32767, 32767][32767, 32767, 32767]"
        private const val MAX_AMP = Short.MAX_VALUE
        private val AUDIO = listOf(
            Audio.Data(3, shortArrayOf(MAX_AMP, MAX_AMP, MAX_AMP)),
            Audio.Error(-1),
            Audio.Data(3, shortArrayOf(MAX_AMP, MAX_AMP, MAX_AMP)),
        )

        @BeforeClass
        @JvmStatic
        fun initAudioFormat() {
            audioFormat = mockk()
        }
    }
}
