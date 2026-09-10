/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.mediaupload.impl

import android.net.Uri
import android.os.Looper
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Transformer
import com.google.common.truth.Truth.assertThat
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.mockk.every
import io.mockk.mockkConstructor
import io.mockk.unmockkConstructor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Test
import org.robolectric.RuntimeEnvironment
import java.io.File

/** Real compressor flow and Android builder; only the native codec worker is controlled. */
class VideoCompressorCancellationTest : RobolectricTest() {
    @Test
    fun `cancel joins native export before retry and removes only its partial file`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        mockkConstructor(Transformer::class)
        val context = RuntimeEnvironment.getApplication()
        val source = File(context.cacheDir, "synthetic-video-source.mp4").apply { writeText("retained source") }
        val other = File(context.cacheDir, "another-upload.mp4").apply { writeText("another active attempt") }
        val files = mutableListOf<File>()
        var cancellations = 0
        val started = CompletableDeferred<Unit>()
        try {
            every { anyConstructed<Transformer>().start(any<EditedMediaItem>(), any()) } answers {
                files += File(secondArg<String>()).apply { writeText("partial export") }
                started.complete(Unit)
            }
            every { anyConstructed<Transformer>().getProgress(any()) } returns Transformer.PROGRESS_STATE_NOT_STARTED
            every { anyConstructed<Transformer>().cancel() } answers {
                assertThat(Looper.myLooper()).isEqualTo(Looper.getMainLooper())
                cancellations++
            }
            val compressor = VideoCompressor(context)
            val first = launch { compressor.compress(Uri.fromFile(source), VideoCompressionPreset.STANDARD).collect() }
            started.await()
            first.cancelAndJoin()
            assertThat(cancellations).isEqualTo(1)
            assertThat(files.single().exists()).isFalse()
            val second = launch { compressor.compress(Uri.fromFile(source), VideoCompressionPreset.STANDARD).collect() }
            // Advance only current work; the native export deliberately never completes.
            testScheduler.runCurrent()
            second.cancelAndJoin()
            assertThat(cancellations).isEqualTo(2)
            assertThat(files).hasSize(2)
            assertThat(files.distinct()).hasSize(2)
            assertThat(files.any { it.exists() }).isFalse()
            assertThat(source.readText()).isEqualTo("retained source")
            assertThat(other.readText()).isEqualTo("another active attempt")
        } finally {
            unmockkConstructor(Transformer::class)
            Dispatchers.resetMain()
        }
    }
}
