/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.voicemessages.composer

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.features.messages.impl.R
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState

/** Shared by view callbacks and the node's asynchronous navigator (thread/focus resolution). */
class VoiceDraftNavigationGuard {
    private var state: VoiceMessageComposerState? = null
    private var pendingNavigation by mutableStateOf<(() -> Unit)?>(null)
    private var discarding by mutableStateOf(false)
    internal val showConfirmation: Boolean get() = pendingNavigation != null && !discarding

    internal fun update(state: VoiceMessageComposerState) {
        this.state = state
        if (discarding && state.voiceMessageState is VoiceMessageState.Idle) {
            val navigate = pendingNavigation
            pendingNavigation = null
            discarding = false
            navigate?.invoke()
        }
    }

    fun navigate(navigate: () -> Unit) {
        // Latch the original request; another click must not silently replace its destination.
        if (pendingNavigation != null || discarding) return
        when (val voice = state?.voiceMessageState) {
            null, VoiceMessageState.Idle -> navigate()
            is VoiceMessageState.Preview -> if (!voice.isSending) pendingNavigation = navigate
            is VoiceMessageState.Recording -> {
                pendingNavigation = navigate
                state?.eventSink?.invoke(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
            }
        }
    }

    internal fun keep() {
        pendingNavigation = null
    }

    internal fun discard() {
        if (!showConfirmation) return
        discarding = true
        state?.eventSink?.invoke(VoiceMessageComposerEvent.DeleteVoiceMessage)
    }
}

/** Keep navigation on the original target until deletion is acknowledged by the recorder. */
@Composable
internal fun rememberVoiceDraftNavigationGuard(
    state: VoiceMessageComposerState,
    guard: VoiceDraftNavigationGuard = remember { VoiceDraftNavigationGuard() },
): (() -> Unit) -> Unit {
    SideEffect { guard.update(state) }
    if (guard.showConfirmation) {
        ConfirmationDialog(
            title = stringResource(R.string.screen_voice_leave_title),
            content = stringResource(R.string.screen_voice_leave_body),
            submitText = stringResource(R.string.screen_voice_discard),
            cancelText = stringResource(R.string.screen_voice_keep),
            destructiveSubmit = true,
            onDismiss = guard::keep,
            onSubmitClick = guard::discard,
        )
    }
    return guard::navigate
}
