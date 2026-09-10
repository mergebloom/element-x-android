/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.net.Uri
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.core.mimetype.MimeTypes
import io.element.android.libraries.matrix.api.media.FileInfo
import io.element.android.libraries.matrix.api.media.MediaUploadHandler
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.matrix.test.timeline.FakeTimeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.tests.testutils.robolectric.RobolectricTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

class DefaultMediaSenderOwnershipTest : RobolectricTest() {
    private val optimizationConfig = MediaOptimizationConfig(
        compressImages = true,
        videoCompressionPreset = VideoCompressionPreset.STANDARD,
    )
    private val uploadInfo = MediaUploadInfo.AnyFile(
        file = File("attachment.txt"),
        fileInfo = FileInfo(mimetype = MimeTypes.Any, size = 1L, thumbnailInfo = null, thumbnailSource = null),
    )

    @Test
    fun `first immediate upload failure cancels only its handler while second upload can succeed`() = runTest {
        val first = DeferredUploadHandler()
        val second = DeferredUploadHandler()
        val fixture = createFixture(first, second)
        val failure = IllegalStateException("Upload failed")
        val firstSend = async { fixture.sender.send(SendKind.Immediate) }
        first.awaitEntered.await()
        val secondSend = async { fixture.sender.send(SendKind.Immediate) }
        second.awaitEntered.await()

        first.result.complete(Result.failure(failure))
        assertThat(firstSend.await().exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(first.cancelCalls).isEqualTo(1)
        assertThat(second.cancelCalls).isEqualTo(0)
        assertThat(secondSend.isActive).isTrue()
        assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()

        second.result.complete(Result.success(Unit))
        assertThat(secondSend.await().isSuccess).isTrue()
        assertThat(second.cancelCalls).isEqualTo(0)
        assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
    }

    @Test
    fun `first immediate upload success does not remove second upload cancellation ownership`() = runTest {
        val first = DeferredUploadHandler()
        val second = DeferredUploadHandler()
        val fixture = createFixture(first, second)
        val firstSend = async { fixture.sender.send(SendKind.Immediate) }
        first.awaitEntered.await()
        val secondSend = launch { fixture.sender.send(SendKind.Immediate) }
        second.awaitEntered.await()

        first.result.complete(Result.success(Unit))
        assertThat(firstSend.await().isSuccess).isTrue()
        assertThat(first.cancelCalls).isEqualTo(0)
        assertThat(second.cancelCalls).isEqualTo(0)
        assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()

        secondSend.cancelAndJoin()
        assertThat(first.cancelCalls).isEqualTo(0)
        assertThat(second.cancelCalls).isEqualTo(1)
        assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
    }

    @Test
    fun `second immediate upload failure leaves first upload tracked until it succeeds`() = runTest {
        val first = DeferredUploadHandler()
        val second = DeferredUploadHandler()
        val fixture = createFixture(first, second)
        val failure = IllegalStateException("Second upload failed")
        val firstSend = async { fixture.sender.send(SendKind.Immediate) }
        first.awaitEntered.await()
        val secondSend = async { fixture.sender.send(SendKind.Immediate) }
        second.awaitEntered.await()

        second.result.complete(Result.failure(failure))
        assertThat(secondSend.await().exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(first.cancelCalls).isEqualTo(0)
        assertThat(second.cancelCalls).isEqualTo(1)
        assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()

        first.result.complete(Result.success(Unit))
        assertThat(firstSend.await().isSuccess).isTrue()
        assertThat(first.cancelCalls).isEqualTo(0)
        assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
    }

    @Test
    fun `preprocessing failure cannot cancel or untrack an already active immediate upload`() = runTest {
        val active = DeferredUploadHandler()
        val fixture = createFixture(active)
        val activeSend = async { fixture.sender.send(SendKind.Immediate) }
        active.awaitEntered.await()
        val failure = IllegalArgumentException("Preprocessing failed")
        fixture.preProcessor.givenResult(Result.failure(failure))

        assertThat(fixture.sender.send(SendKind.Immediate).exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(active.cancelCalls).isEqualTo(0)
        assertThat(activeSend.isActive).isTrue()
        assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()

        active.result.complete(Result.success(Unit))
        assertThat(activeSend.await().isSuccess).isTrue()
        assertThat(active.cancelCalls).isEqualTo(0)
        assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
    }

    @Test
    fun `failure to acquire a native handler cannot cancel an already active upload`() = runTest {
        val active = DeferredUploadHandler()
        val fixture = createFixture(active)
        val activeSend = async { fixture.sender.send(SendKind.Immediate) }
        active.awaitEntered.await()
        val failure = IllegalStateException("Enqueue failed")
        fixture.timeline.sendFileLambda = { _, _, _, _, _ -> Result.failure(failure) }

        assertThat(fixture.sender.send(SendKind.PreProcessed).exceptionOrNull()).isSameInstanceAs(failure)
        assertThat(active.cancelCalls).isEqualTo(0)
        assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()

        active.result.complete(Result.success(Unit))
        assertThat(activeSend.await().isSuccess).isTrue()
        assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
    }

    @Test
    fun `cancelling each send entry point explicitly cancels only its native handler`() = runTest {
        for (kind in SendKind.entries) {
            val cancelled = DeferredUploadHandler()
            val other = DeferredUploadHandler()
            val fixture = createFixture(cancelled, other)
            var cancellationPropagated = false
            var returnedAfterCancellation = false
            val cancelledSend = launch {
                try {
                    fixture.sender.send(kind)
                    returnedAfterCancellation = true
                } catch (exception: CancellationException) {
                    cancellationPropagated = true
                    throw exception
                }
            }
            cancelled.awaitEntered.await()
            val otherSend = async { fixture.sender.send(SendKind.Immediate) }
            other.awaitEntered.await()

            cancelledSend.cancelAndJoin()
            assertThat(cancellationPropagated).isTrue()
            assertThat(returnedAfterCancellation).isFalse()
            assertThat(cancelled.cancelCalls).isEqualTo(1)
            assertThat(other.cancelCalls).isEqualTo(0)
            assertThat(otherSend.isActive).isTrue()
            assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()

            other.result.complete(Result.success(Unit))
            assertThat(otherSend.await().isSuccess).isTrue()
            assertThat(other.cancelCalls).isEqualTo(0)
            assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
        }
    }

    @Test
    fun `returned upload failures cancel their handler and allow a fresh retry for every entry point`() = runTest {
        for (kind in SendKind.entries) {
            val failed = DeferredUploadHandler()
            val retry = DeferredUploadHandler()
            val fixture = createFixture(failed, retry)
            val failure = IllegalStateException("Upload failed")
            val failedSend = async { fixture.sender.send(kind) }
            failed.awaitEntered.await()
            failed.result.complete(Result.failure(failure))

            assertThat(failedSend.await().exceptionOrNull()).isSameInstanceAs(failure)
            assertThat(failed.cancelCalls).isEqualTo(1)
            assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()

            val retrySend = async { fixture.sender.send(kind) }
            retry.awaitEntered.await()
            assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()
            retry.result.complete(Result.success(Unit))
            assertThat(retrySend.await().isSuccess).isTrue()
            assertThat(failed.cancelCalls).isEqualTo(1)
            assertThat(retry.cancelCalls).isEqualTo(0)
            assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
        }
    }

    @Test
    fun `thrown await failures cancel their handler and release tracking for every entry point`() = runTest {
        for (kind in SendKind.entries) {
            val handler = DeferredUploadHandler()
            val fixture = createFixture(handler)
            val failure = IllegalStateException("Await threw")
            val send = async { fixture.sender.send(kind) }
            handler.awaitEntered.await()
            handler.result.completeExceptionally(failure)

            assertThat(send.await().exceptionOrNull()).isSameInstanceAs(failure)
            assertThat(handler.cancelCalls).isEqualTo(1)
            assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
        }
    }

    @Test
    fun `native cancellation results propagate cancellation and release their handler for every entry point`() = runTest {
        for (kind in SendKind.entries) {
            val handler = DeferredUploadHandler()
            val fixture = createFixture(handler)
            val cancellation = CancellationException("Native upload cancelled")
            var observedCancellation: CancellationException? = null
            var returnedNormally = false
            val send = launch {
                try {
                    fixture.sender.send(kind)
                    returnedNormally = true
                } catch (exception: CancellationException) {
                    observedCancellation = exception
                    throw exception
                }
            }
            handler.awaitEntered.await()
            handler.result.complete(Result.failure(cancellation))
            send.join()

            assertThat(observedCancellation).isSameInstanceAs(cancellation)
            assertThat(returnedNormally).isFalse()
            assertThat(send.isCancelled).isTrue()
            assertThat(handler.cancelCalls).isEqualTo(1)
            assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
        }
    }

    @Test
    fun `cancelled gallery releases its handler before retrying with the same prepared items`() = runTest {
        val cancelled = DeferredUploadHandler()
        val retry = DeferredUploadHandler()
        val fixture = createFixture(cancelled, retry)
        val firstSend = launch { fixture.sender.send(SendKind.Gallery) }
        cancelled.awaitEntered.await()
        assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()

        firstSend.cancelAndJoin()
        assertThat(cancelled.cancelCalls).isEqualTo(1)
        assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()

        val retrySend = async { fixture.sender.send(SendKind.Gallery) }
        retry.awaitEntered.await()
        assertThat(fixture.sender.hasOngoingMediaUploads).isTrue()
        retry.result.complete(Result.success(Unit))
        assertThat(retrySend.await().isSuccess).isTrue()
        assertThat(cancelled.cancelCalls).isEqualTo(1)
        assertThat(retry.cancelCalls).isEqualTo(0)
        assertThat(fixture.sender.hasOngoingMediaUploads).isFalse()
    }

    private suspend fun DefaultMediaSender.send(kind: SendKind): Result<Unit> = when (kind) {
        SendKind.Immediate -> sendMedia(
            uri = Uri.parse("content://media/attachment.txt"),
            mimeType = MimeTypes.Any,
            mediaOptimizationConfig = optimizationConfig,
        )
        SendKind.PreProcessed -> sendPreProcessedMedia(uploadInfo, null, null, null)
        SendKind.Voice -> sendVoiceMessage(
            uri = Uri.parse("content://media/voice.ogg"),
            mimeType = MimeTypes.Ogg,
            waveForm = listOf(0.1f, 0.5f),
            inReplyToEventId = null,
        )
        SendKind.Gallery -> sendGallery(
            mediaUploadInfos = listOf(uploadInfo, uploadInfo.copy(file = File("second.txt"))),
            caption = null,
            formattedCaption = null,
            inReplyToEventId = null,
        )
    }

    private fun createFixture(vararg handlers: DeferredUploadHandler): Fixture {
        val pendingHandlers = ArrayDeque(handlers.toList())
        val timeline = FakeTimeline().apply {
            sendAudioLambda = { _, _, _, _, _ -> Result.success(pendingHandlers.removeFirst()) }
            sendVoiceMessageLambda = { _, _, _, _ -> Result.success(pendingHandlers.removeFirst()) }
            sendFileLambda = { _, _, _, _, _ -> Result.success(pendingHandlers.removeFirst()) }
            sendGalleryLambda = { items, _, _, _ ->
                assertThat(items).hasSize(2)
                Result.success(pendingHandlers.removeFirst())
            }
        }
        val preProcessor = FakeMediaPreProcessor().apply { givenAudioResult() }
        val sender = DefaultMediaSender(
            preProcessor = preProcessor,
            room = FakeJoinedRoom(liveTimeline = timeline),
            timelineMode = Timeline.Mode.Live,
            mediaOptimizationConfigProvider = MediaOptimizationConfigProvider { optimizationConfig },
        )
        return Fixture(sender, preProcessor, timeline)
    }

    private enum class SendKind {
        Immediate,
        PreProcessed,
        Voice,
        Gallery,
    }

    private data class Fixture(
        val sender: DefaultMediaSender,
        val preProcessor: FakeMediaPreProcessor,
        val timeline: FakeTimeline,
    )

    /** The native operation is independent of its Kotlin waiter; only cancel() records explicit cancellation. */
    private class DeferredUploadHandler : MediaUploadHandler {
        val awaitEntered = CompletableDeferred<Unit>()
        val result = CompletableDeferred<Result<Unit>>()
        var cancelCalls = 0
            private set

        override suspend fun await(): Result<Unit> {
            awaitEntered.complete(Unit)
            return result.await()
        }

        override fun cancel() {
            cancelCalls++
        }
    }
}
