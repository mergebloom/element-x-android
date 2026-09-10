/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.MessageComposerContext
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.textcomposer.model.MessageComposerMode
import org.junit.Test

class MessageComposerContextTest {
    @Test
    fun `room context keeps the live mode for legacy live consumers`() {
        val room = DefaultMessageComposerContext()
        val live = room.forTimeline(Timeline.Mode.Live)
        live.composerMode = aReplyMode()
        assertThat(room.composerMode).isEqualTo(live.composerMode)
        room.forTimeline(Timeline.Mode.Thread(ThreadId("\$thread-b"))).composerMode = anEditMode()
        assertThat(room.composerMode).isEqualTo(aReplyMode())
    }

    @Test
    fun `equal target keys and already resolved contexts use one canonical owner`() {
        val room = DefaultMessageComposerContext()
        val mode = Timeline.Mode.Thread(ThreadId("\$thread-b"))
        val owner = room.forTimeline(mode)
        val readOnly: MessageComposerContext = owner
        owner.composerMode = aReplyMode()
        assertThat(room.forTimeline(Timeline.Mode.Thread(ThreadId("\$thread-b")))).isSameInstanceAs(owner)
        assertThat(readOnly.forTimeline(mode)).isSameInstanceAs(owner)
        assertThat(readOnly.forTimeline(Timeline.Mode.Live)).isSameInstanceAs(room.forTimeline(Timeline.Mode.Live))
        assertThat(readOnly.forTimeline(mode).composerMode).isEqualTo(aReplyMode())
    }

    @Test
    fun `live and distinct threads and room registries cannot overwrite each other`() {
        val room = DefaultMessageComposerContext()
        val live = room.forTimeline(Timeline.Mode.Live)
        val b = room.forTimeline(Timeline.Mode.Thread(ThreadId("\$thread-b")))
        val c = room.forTimeline(Timeline.Mode.Thread(ThreadId("\$thread-c")))
        live.composerMode = anEditMode()
        b.composerMode = aReplyMode()
        assertThat(live.composerMode).isEqualTo(anEditMode())
        assertThat(c.composerMode).isEqualTo(MessageComposerMode.Normal)
        assertThat(DefaultMessageComposerContext().forTimeline(Timeline.Mode.Thread(ThreadId("\$thread-b"))).composerMode)
            .isEqualTo(MessageComposerMode.Normal)
        b.composerMode = MessageComposerMode.Normal
        assertThat(live.composerMode).isEqualTo(anEditMode())
    }
}
