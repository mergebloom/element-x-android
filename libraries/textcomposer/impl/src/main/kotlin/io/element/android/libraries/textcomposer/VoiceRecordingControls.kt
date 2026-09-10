/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.tokens.generated.CompoundIcons
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.designsystem.theme.components.CircularProgressIndicator
import io.element.android.libraries.designsystem.theme.components.Icon
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.textcomposer.components.VoiceMessageRecorderButtonIcon
import io.element.android.libraries.textcomposer.components.VoiceMessageRecording
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.ui.strings.CommonStrings
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs
import kotlin.math.max
import kotlin.time.Duration.Companion.seconds

private val VoiceGestureTravel = 72.dp
private val VoiceGestureTargetSize = 32.dp
private const val VOICE_GESTURE_DOMINANCE = 1.25f

/** UI-only interaction state; the existing recorder remains the authority on audio and permission. */
internal class VoiceGestureState {
    var held by mutableStateOf(false)
    var origin by mutableStateOf(Offset.Zero)
    var originInRoot: Offset? = null
    var drag by mutableStateOf(Offset.Zero)
    var threshold by mutableStateOf(1f)
    var locked by mutableStateOf(false)
    var pendingTerminal: VoiceMessageRecorderEvent? by mutableStateOf(null)

    fun reset() {
        held = false
        locked = false
        drag = Offset.Zero
        originInRoot = null
    }
}

@Composable
internal fun rememberVoiceGestureState(
    state: VoiceMessageState,
    onEvent: (VoiceMessageRecorderEvent) -> Unit,
): VoiceGestureState {
    val gesture = remember { VoiceGestureState() }
    val currentEvent by rememberUpdatedState(onEvent)
    var wasRecording by remember { mutableStateOf(false) }
    LaunchedEffect(state) {
        if (state is VoiceMessageState.Recording) {
            // Stop/Cancel may have arrived while permission/start was pending. Never let a late
            // Recording update resurrect a released gesture. A fresh activation clears this latch.
            gesture.pendingTerminal?.let {
                gesture.pendingTerminal = null
                currentEvent(it)
            }
        } else if (wasRecording || state is VoiceMessageState.Preview) {
            gesture.reset()
        }
        wasRecording = state is VoiceMessageState.Recording
    }
    return gesture
}

@Composable
internal fun VoiceRecordingHint(state: VoiceMessageState, gesture: VoiceGestureState, editing: Boolean) {
    val text = when {
        state is VoiceMessageState.Preview -> R.string.screen_voice_preview_hint
        gesture.locked -> R.string.screen_voice_locked
        gesture.held -> if (LocalLayoutDirection.current == LayoutDirection.Ltr) R.string.screen_voice_held_ltr else R.string.screen_voice_held_rtl
        state is VoiceMessageState.Recording -> R.string.screen_voice_hands_free
        editing -> R.string.screen_voice_edit_disabled
        else -> R.string.screen_voice_idle_hint
    }
    Text(
        text = stringResource(text),
        style = ElementTheme.typography.fontBodySmRegular,
        color = ElementTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun VoiceGestureTarget(icon: ImageVector, progress: Float, tag: String, modifier: Modifier = Modifier) {
    // These are visual commit markers, not tiny buttons. The full-size Cancel / Stop buttons
    // remain available above, with the gesture explanation announced once by the hint.
    Box(
        modifier = modifier
            .size(VoiceGestureTargetSize)
            .background(ElementTheme.colors.bgCanvasDefault, CircleShape)
            .testTag(tag)
            .semantics { hideFromAccessibility() },
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            progress = { progress.coerceIn(0f, 1f) },
            modifier = Modifier.size(VoiceGestureTargetSize),
            color = ElementTheme.colors.iconPrimary,
            trackColor = ElementTheme.colors.iconSecondary.copy(alpha = 0.3f),
            strokeWidth = 2.dp,
        )
        Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(20.dp))
    }
}

@Composable
internal fun VoiceRecordingActions(onCancel: () -> Unit, onStop: () -> Unit) {
    // Compound's string-only button truncates at one line. Native content slots retain the
    // complete action at 200% text; equal columns prevent Cancel from crowding out review.
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onCancel, modifier = Modifier.weight(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(text = stringResource(CommonStrings.action_cancel), style = ElementTheme.typography.fontBodyLgMedium, textAlign = TextAlign.Center)
        }
        TextButton(onClick = onStop, modifier = Modifier.weight(1f).sizeIn(minWidth = 48.dp, minHeight = 48.dp)) {
            Text(text = stringResource(R.string.screen_voice_stop_to_review), style = ElementTheme.typography.fontBodyLgMedium, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Keep this target at one composition slot across Idle -> Recording, including with formatting open.
 * There is deliberately no clickable pointer detector: one owner handles tap/hold and consumes the
 * stream, so a release can never become a trailing Start or Send. Semantics and keyboard offer tap.
 */
@Composable
internal fun VoiceMicrophoneButton(
    state: VoiceMessageState,
    editing: Boolean,
    gesture: VoiceGestureState,
    onEvent: (VoiceMessageRecorderEvent) -> Unit,
) {
    val enabled = !editing && state !is VoiceMessageState.Preview
    val currentEnabled by rememberUpdatedState(enabled)
    val currentState by rememberUpdatedState(state)
    val currentEvent by rememberUpdatedState(onEvent)
    val currentDirection by rememberUpdatedState(LocalLayoutDirection.current)
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    // 72dp is one 48dp target plus a 24dp deliberate travel gap, on either axis. Always exceed slop.
    val boundary by rememberUpdatedState(with(density) { VoiceGestureTravel.toPx() })

    fun start(held: Boolean) {
        gesture.pendingTerminal = null
        gesture.locked = false
        gesture.held = held
        gesture.drag = Offset.Zero
        currentEvent(VoiceMessageRecorderEvent.Start)
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    fun finish(event: VoiceMessageRecorderEvent) {
        gesture.reset()
        gesture.pendingTerminal = if (currentState is VoiceMessageState.Idle) event else null
        currentEvent(event)
    }

    val currentStart by rememberUpdatedState(::start)
    val currentFinish by rememberUpdatedState(::finish)
    val tap: () -> Unit = {
        if (currentEnabled) {
            if (currentState is VoiceMessageState.Recording) currentFinish(VoiceMessageRecorderEvent.Stop) else currentStart(false)
        }
    }
    val currentTap by rememberUpdatedState(tap)
    val label = stringResource(
        when {
            editing -> R.string.screen_voice_edit_disabled
            state is VoiceMessageState.Recording -> CommonStrings.a11y_voice_message_stop_recording
            else -> CommonStrings.a11y_voice_message_record
        }
    )
    val status = stringResource(
        when {
            gesture.locked -> R.string.screen_voice_locked
            gesture.held -> if (currentDirection == LayoutDirection.Ltr) R.string.screen_voice_held_ltr else R.string.screen_voice_held_rtl
            state is VoiceMessageState.Recording -> R.string.screen_voice_hands_free
            else -> R.string.screen_voice_idle_hint
        }
    )
    val travel = with(density) { gesture.threshold.toDp() }
    val originX = with(density) { gesture.origin.x.toDp() }
    val originY = with(density) { gesture.origin.y.toDp() }
    val originFromEnd = if (currentDirection == LayoutDirection.Ltr) 48.dp - originX else originX
    val radius = VoiceGestureTargetSize / 2
    // Reserve an L-shaped gesture area instead of painting over actions or the waveform. Keep
    // the 48dp microphone at BottomEnd so the original pointer stays on the same physical target.
    // Marker centres, not arbitrary hint-row positions, are the recognizer's commit boundaries.
    val arenaWidth = if (gesture.held) maxOf(48.dp, travel + originFromEnd + radius) else 48.dp
    val arenaHeight = if (gesture.held) maxOf(48.dp, travel + 48.dp - originY + radius) else 48.dp
    Box(
        modifier = Modifier
            .padding(
                vertical = if (state is VoiceMessageState.Preview) 0.dp else 5.dp,
                horizontal = if (state is VoiceMessageState.Preview) 0.dp else 3.dp,
            )
            .size(
                width = if (state is VoiceMessageState.Preview) 0.dp else arenaWidth,
                height = if (state is VoiceMessageState.Preview) 0.dp else arenaHeight,
            ),
    ) {
        if (gesture.held) {
            val cancel = if (currentDirection == LayoutDirection.Ltr) -gesture.drag.x else gesture.drag.x
            val up = -gesture.drag.y
            VoiceGestureTarget(
                icon = CompoundIcons.Close(),
                progress = if (cancel >= abs(gesture.drag.y) * VOICE_GESTURE_DOMINANCE) cancel / gesture.threshold else 0f,
                tag = "voice-cancel-target",
                modifier = Modifier.offset(x = arenaWidth - originFromEnd - travel - radius, y = arenaHeight - 24.dp - radius),
            )
            VoiceGestureTarget(
                icon = CompoundIcons.Lock(),
                progress = if (up >= abs(gesture.drag.x) * VOICE_GESTURE_DOMINANCE) up / gesture.threshold else 0f,
                tag = "voice-lock-target",
                modifier = Modifier.offset(x = arenaWidth - 24.dp - radius, y = arenaHeight - 48.dp + originY - travel - radius),
            )
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .size(if (state is VoiceMessageState.Preview) 0.dp else 48.dp)
                .testTag("voice-microphone")
                .alpha(if (enabled) 1f else 0.4f)
                .onGloballyPositioned {
                    coordinates = it
                    val originInRoot = gesture.originInRoot
                    if (gesture.held && originInRoot != null) gesture.origin = originInRoot - it.positionInRoot()
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (!currentEnabled) return@awaitEachGesture
                        down.consume()
                        val downInRoot = (coordinates?.positionInRoot() ?: Offset.Zero) + down.position
                        var started = false
                        var released = false
                        var aborted = false
                        var committed = false
                        try {
                            val earlyEnd = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var done = false
                                while (!done) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) {
                                        aborted = true
                                        done = true
                                    } else {
                                        released = !change.pressed
                                        val positionInRoot = (coordinates?.positionInRoot() ?: Offset.Zero) + change.position
                                        aborted = (positionInRoot - downInRoot).getDistance() > viewConfiguration.touchSlop
                                        change.consume()
                                        done = released || aborted
                                    }
                                }
                                true
                            }
                            if (earlyEnd != null) {
                                if (released && !aborted) currentTap()
                                return@awaitEachGesture
                            }
                            if (currentState !is VoiceMessageState.Idle || !currentEnabled) return@awaitEachGesture
                            started = true
                            gesture.originInRoot = downInRoot
                            gesture.origin = downInRoot - (coordinates?.positionInRoot() ?: Offset.Zero)
                            gesture.threshold = max(boundary, viewConfiguration.touchSlop)
                            currentStart(true)
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id }
                                if (change == null || change.isConsumed || event.changes.count { it.pressed } > 1) break
                                change.consume()
                                if (!change.pressed) {
                                    released = true
                                    if (gesture.held) currentFinish(VoiceMessageRecorderEvent.Stop)
                                    break // Locked release is a no-op, not Stop and never Send.
                                }
                                if (!committed && gesture.held) {
                                    val delta = (coordinates?.positionInRoot() ?: Offset.Zero) + change.position - downInRoot
                                    val threshold = gesture.threshold
                                    gesture.drag = delta
                                    val cancelX = if (currentDirection == LayoutDirection.Ltr) -delta.x else delta.x
                                    when {
                                        cancelX > threshold && cancelX >= abs(delta.y) * VOICE_GESTURE_DOMINANCE -> {
                                            committed = true
                                            currentFinish(VoiceMessageRecorderEvent.Cancel)
                                        }
                                        -delta.y > threshold && -delta.y >= abs(delta.x) * VOICE_GESTURE_DOMINANCE -> {
                                            committed = true
                                            gesture.held = false
                                            gesture.locked = true
                                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        }
                                    }
                                }
                            }
                        } finally {
                            // ACTION_CANCEL, lost pointer, extra pointer or disposal must only stop.
                            if (started && !released && (gesture.held || gesture.locked)) currentFinish(VoiceMessageRecorderEvent.Stop)
                        }
                    }
                }
                .semantics {
                    role = Role.Button
                    contentDescription = label
                    stateDescription = status
                    if (state is VoiceMessageState.Preview) hideFromAccessibility()
                    if (!enabled) {
                        disabled()
                    } else {
                        onClick {
                        currentTap()
                        true
                    }
                    }
                }
                .onKeyEvent {
                    if (currentEnabled && it.type == KeyEventType.KeyUp && it.key in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar)) {
                        currentTap()
                        true
                    } else {
                        false
                    }
                }
                .focusable(enabled),
        ) {
            VoiceMessageRecorderButtonIcon(isRecording = state is VoiceMessageState.Recording)
        }
    }
}

internal class VoiceInteractionPreviewParam : PreviewParameterProvider<Boolean> {
    override val values = sequenceOf(false, true)
}

@PreviewsDayNight
@Composable
internal fun VoiceInteractionPreview(@PreviewParameter(VoiceInteractionPreviewParam::class) locked: Boolean) = ElementPreview {
    VoiceInteractionPreviewContent(locked)
}

@Composable
internal fun VoiceInteractionPreviewContent(locked: Boolean) {
    val state = VoiceMessageState.Recording(3.seconds, persistentListOf(0.2f, 0.7f, 0.4f))
    val origin = with(LocalDensity.current) { Offset(24.dp.toPx(), 24.dp.toPx()) }
    val threshold = with(LocalDensity.current) { VoiceGestureTravel.toPx() }
    val gesture = remember(locked, origin, threshold) {
        VoiceGestureState().apply {
            this.locked = locked
            held = !locked
            this.origin = origin
            this.threshold = threshold
        }
    }
    Column(modifier = Modifier.width(360.dp)) {
        VoiceRecordingHint(state, gesture, editing = false)
        VoiceRecordingActions(onCancel = {}, onStop = {})
        Row(verticalAlignment = Alignment.Bottom) {
            VoiceMessageRecording(state.levels, state.duration, Modifier.weight(1f))
            VoiceMicrophoneButton(state, editing = false, gesture = gesture, onEvent = {})
        }
    }
}
