/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class VoiceDraftNavigationGuardTest {
    private val guard = VoiceDraftNavigationGuard()
    private val events = mutableListOf<VoiceMessageComposerEvent>()

    @Test
    fun `idle navigation is immediate`() {
        guard.update(state(VoiceMessageState.Idle))
        var navigations = 0
        guard.navigate { navigations++ }
        assertThat(navigations).isEqualTo(1)
        assertThat(events).isEmpty()
    }

    @Test
    fun `recording stops to review and Keep does not navigate or discard`() {
        guard.update(state(VoiceMessageState.Recording(0.seconds, persistentListOf())))
        var navigations = 0
        guard.navigate { navigations++ }
        assertThat(events).containsExactly(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
        assertThat(guard.showConfirmation).isTrue()
        guard.keep()
        guard.update(state(preview()))
        assertThat(navigations).isEqualTo(0)
        assertThat(guard.showConfirmation).isFalse()
    }

    @Test
    fun `discard waits for recorder deletion and retains first requested destination`() {
        guard.update(state(preview()))
        val destinations = mutableListOf<String>()
        guard.navigate { destinations += "original" }
        guard.navigate { destinations += "replacement" }
        guard.discard()
        guard.discard()
        assertThat(events).containsExactly(VoiceMessageComposerEvent.DeleteVoiceMessage)
        assertThat(destinations).isEmpty()
        guard.update(state(preview())) // Still deleting; a preview is not a deletion acknowledgement.
        assertThat(destinations).isEmpty()
        guard.update(state(VoiceMessageState.Idle))
        guard.update(state(VoiceMessageState.Idle))
        assertThat(destinations).containsExactly("original")
    }

    @Test
    fun `sending blocks every request without offering a destructive action`() {
        guard.update(state(preview(sending = true)))
        var navigations = 0
        repeat(3) { guard.navigate { navigations++ } }
        guard.discard()
        assertThat(guard.showConfirmation).isFalse()
        assertThat(navigations).isEqualTo(0)
        assertThat(events).isEmpty()
    }

    @Test
    fun `discard while stopping cannot leave before startup has settled`() {
        guard.update(state(VoiceMessageState.Recording(0.seconds, persistentListOf())))
        var navigations = 0
        guard.navigate { navigations++ }
        guard.discard()
        guard.update(state(VoiceMessageState.Recording(0.seconds, persistentListOf())))
        assertThat(navigations).isEqualTo(0)
        guard.update(state(VoiceMessageState.Idle))
        assertThat(navigations).isEqualTo(1)
        assertThat(events).containsExactly(
            VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop),
            VoiceMessageComposerEvent.DeleteVoiceMessage,
        ).inOrder()
    }

    private fun state(voice: VoiceMessageState) = VoiceMessageComposerState(
        voiceMessageState = voice,
        showPermissionRationaleDialog = false,
        showSendFailureDialog = false,
        keepScreenOn = false,
        eventSink = { events += it },
    )

    private fun preview(sending: Boolean = false) = VoiceMessageState.Preview(
        isSending = sending,
        isPlaying = false,
        showCursor = false,
        playbackProgress = 0f,
        time = 1.seconds,
        waveform = persistentListOf(0.5f),
    )
}
