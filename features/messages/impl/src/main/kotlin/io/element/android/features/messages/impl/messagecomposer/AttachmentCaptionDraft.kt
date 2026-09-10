/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import android.os.Parcelable
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.libraries.di.RoomScope
import kotlinx.parcelize.Parcelize
import java.util.UUID

/** Immutable navigation payload. Never contains a reference to another room's editor. */
@Parcelize
data class AttachmentCaptionDraft(
    val id: String,
    val caption: String,
    val plainTextConversion: Boolean,
) : Parcelable

/**
 * A short-lived lease on the originating editor, not a second draft database.
 * Restored navigation without a live owner fails closed: keep the preview, never overwrite text.
 */
@Inject
@SingleIn(RoomScope::class)
class AttachmentCaptionDrafts {
    private val owners = mutableMapOf<String, Owner>()

    private class Owner(val restore: (String) -> Boolean, val consume: () -> Unit)

    fun capture(caption: String, plainTextConversion: Boolean, restore: (String) -> Boolean, consume: () -> Unit): AttachmentCaptionDraft {
        val draft = AttachmentCaptionDraft(UUID.randomUUID().toString(), caption, plainTextConversion)
        owners[draft.id] = Owner(restore, consume)
        return draft
    }

    fun restore(draft: AttachmentCaptionDraft, caption: String): Boolean {
        val owner = owners[draft.id] ?: return false
        if (!owner.restore(caption)) return false
        owners.remove(draft.id)
        return true
    }

    fun consume(draft: AttachmentCaptionDraft) {
        owners.remove(draft.id)?.consume?.invoke()
    }

    fun discard(draft: AttachmentCaptionDraft) {
        owners.remove(draft.id)
    }
}
