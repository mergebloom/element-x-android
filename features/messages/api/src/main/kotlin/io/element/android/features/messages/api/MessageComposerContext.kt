/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.api

import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.textcomposer.model.MessageComposerMode

/**
 * Hoist-able state of the message composer.
 *
 * Typical use case is inside other presenters, to know if
 * the composer is in a thread, if it's editing a message, etc.
 */
interface MessageComposerContext {
    /** What the composer is currently doing: writing a new message, editing one, replying, or composing in a thread. */
    val composerMode: MessageComposerMode

    /**
     * Resolve the owner for a composer's main timeline (live or thread), not its temporary
     * focused-event timeline. Matching text/voice consumers must use the same owner;
     * another thread must not share its mutable edit/reply state. Room registries must
     * return a stable owner for equal modes, including when resolving an existing owner.
     * The default is for implementations that are already scoped to a single composer.
     */
    fun forTimeline(mode: Timeline.Mode): MessageComposerContext = this
}
