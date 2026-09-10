/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.plugin.Plugin
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedInject
import io.element.android.annotations.ContributesNode
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ForcedDarkElementTheme
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.messages.api.MessageDraftNavigationGate
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.messagecomposer.AttachmentCaptionDraft
import io.element.android.libraries.architecture.NodeInputs
import io.element.android.libraries.architecture.inputs
import io.element.android.libraries.designsystem.components.dialogs.ConfirmationDialog
import io.element.android.libraries.di.RoomScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.SessionId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import kotlinx.collections.immutable.ImmutableList

@ContributesNode(RoomScope::class)
@AssistedInject
class AttachmentsPreviewNode(
    @Assisted buildContext: BuildContext,
    @Assisted plugins: List<Plugin>,
    presenterFactory: AttachmentsPreviewPresenter.Factory,
    private val localMediaRenderer: LocalMediaRenderer,
    private val sessionId: SessionId,
    private val enterpriseService: EnterpriseService,
    private val draftNavigationGate: MessageDraftNavigationGate,
) : Node(buildContext, plugins = plugins) {
    data class Inputs(
        val attachments: ImmutableList<Attachment>,
        val timelineMode: Timeline.Mode,
        val inReplyToEventId: EventId?,
        val captionDraft: AttachmentCaptionDraft? = null,
    ) : NodeInputs

    private val inputs: Inputs = inputs()

    private var pendingExternalNavigation by mutableStateOf<(() -> Unit)?>(null)
    private var discardRequested by mutableStateOf(false)
    private val onDoneListener = OnDoneListener {
        val navigate = pendingExternalNavigation
        pendingExternalNavigation = null
        discardRequested = false
        // Retire this preview before the continuation can push a destination (or do nothing).
        // Popping afterwards could remove the new destination and leave this Done preview reachable.
        navigateUp()
        navigate?.invoke()
    }

    private val presenter = presenterFactory.create(
        attachments = inputs.attachments,
        timelineMode = inputs.timelineMode,
        onDoneListener = onDoneListener,
        inReplyToEventId = inputs.inReplyToEventId,
        captionDraft = inputs.captionDraft,
    )

    @Composable
    override fun View(modifier: Modifier) {
        val colors by remember {
            enterpriseService.semanticColorsFlow(sessionId = sessionId)
        }.collectAsState(SemanticColorsLightDark.default)
        ForcedDarkElementTheme(
            colors = colors,
        ) {
            val state = presenter.present()
            DisposableEffect(draftNavigationGate) {
                val registration = draftNavigationGate.register { navigate ->
                    if (!presenter.blocksNavigation && pendingExternalNavigation == null) pendingExternalNavigation = navigate
                }
                onDispose { registration.close() }
            }
            if (pendingExternalNavigation != null && !discardRequested) {
                ConfirmationDialog(
                    title = stringResource(R.string.screen_caption_draft_conflict_title),
                    content = stringResource(R.string.screen_caption_leave_body),
                    submitText = stringResource(R.string.screen_caption_discard_attachment),
                    cancelText = stringResource(R.string.screen_caption_keep_editing),
                    destructiveSubmit = true,
                    onDismiss = { pendingExternalNavigation = null },
                    onSubmitClick = {
                        if (!presenter.blocksNavigation) {
                            discardRequested = true
                            state.eventSink(AttachmentsPreviewEvent.DiscardAttachmentDraft)
                        }
                    },
                )
            }
            AttachmentsPreviewView(
                state = state,
                localMediaRenderer = localMediaRenderer,
                modifier = modifier
            )
        }
    }
}
