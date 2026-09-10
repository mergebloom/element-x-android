/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.features.messages.impl.voicemessages.composer.VoiceDraftNavigationGuard
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

class DefaultMessageDraftNavigationGateTest {
    @Test
    fun `visible child owns requests and closing stale parent cannot remove child`() {
        val gate = DefaultMessageDraftNavigationGate()
        val called = mutableListOf<String>()
        val parent = gate.register { called += "parent" }
        val child = gate.register { called += "child" }
        assertThat(gate.intercept { called += "destination" }).isTrue()
        parent.close()
        assertThat(gate.intercept { called += "destination" }).isTrue()
        assertThat(called).containsExactly("child", "child")
        child.close()
        child.close()
        assertThat(gate.intercept { called += "destination" }).isFalse()
    }

    @Test
    fun `external requests use original voice owner keep and acknowledged discard`() {
        val gate = DefaultMessageDraftNavigationGate()
        val guard = VoiceDraftNavigationGuard()
        val events = mutableListOf<VoiceMessageComposerEvent>()
        val state = VoiceMessageComposerState(
            voiceMessageState = VoiceMessageState.Preview(
                isPlaying = false,
                isSending = false,
                showCursor = false,
                playbackProgress = 0f,
                time = 1.seconds,
                waveform = persistentListOf()
            ),
            showPermissionRationaleDialog = false,
            showSendFailureDialog = false,
            keepScreenOn = false,
            eventSink = { events += it },
        )
        guard.update(state)
        val registration = gate.register(guard::navigate)
        val destinations = mutableListOf<String>()
        gate.intercept { destinations += "room" }
        assertThat(guard.showConfirmation).isTrue()
        guard.keep()
        assertThat(destinations).isEmpty()
        assertThat(events).isEmpty()
        gate.intercept { destinations += "thread" }
        gate.intercept { destinations += "other account" }
        guard.discard()
        assertThat(destinations).isEmpty()
        assertThat(events).containsExactly(VoiceMessageComposerEvent.DeleteVoiceMessage)
        guard.update(state.copy(voiceMessageState = VoiceMessageState.Idle))
        assertThat(destinations).containsExactly("thread")
        registration.close()
        assertThat(gate.intercept { destinations += "unused" }).isFalse()
    }

    @Test
    fun `external request during upload is swallowed without discard or destination`() {
        val gate = DefaultMessageDraftNavigationGate()
        val guard = VoiceDraftNavigationGuard()
        val events = mutableListOf<VoiceMessageComposerEvent>()
        guard.update(VoiceMessageComposerState(
            voiceMessageState = VoiceMessageState.Preview(
                isPlaying = false,
                isSending = true,
                showCursor = false,
                playbackProgress = 0f,
                time = 1.seconds,
                waveform = persistentListOf()
            ),
            showPermissionRationaleDialog = false,
            showSendFailureDialog = false,
            keepScreenOn = false,
            eventSink = { events += it },
        ))
        gate.register(guard::navigate)
        var navigated = false
        assertThat(gate.intercept { navigated = true }).isTrue()
        assertThat(navigated).isFalse()
        assertThat(guard.showConfirmation).isFalse()
        assertThat(events).isEmpty()
    }
}
