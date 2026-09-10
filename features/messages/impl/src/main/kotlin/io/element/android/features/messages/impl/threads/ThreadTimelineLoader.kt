/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.messages.impl.threads

import dev.zacsweers.metro.Inject
import io.element.android.features.messages.impl.timeline.TimelineController
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.asEventId
import io.element.android.libraries.matrix.api.room.CreateTimelineParams
import io.element.android.libraries.matrix.api.room.JoinedRoom
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.api.timeline.Timeline
import java.io.Closeable

/** Creates only the requested thread. No room timeline fallback and no read/Recent side effects while loading. */
@Inject
class ThreadTimelineLoader(
    private val room: JoinedRoom,
    private val client: MatrixClient,
) {
    suspend fun load(threadId: ThreadId): LoadedThreadTimeline {
        check(room.sessionId == client.sessionId)
        val key = ThreadKey(room.sessionId, room.roomId, threadId.asEventId())
        check(client.threadDirectory.isAvailable(key))
        val timeline = room.createTimeline(CreateTimelineParams.Threaded(threadId)).getOrThrow()
        if (timeline.mode != Timeline.Mode.Thread(threadId)) {
            // Never expose a mismatched SDK result to a presenter or its receipt lifecycle.
            if (timeline !== room.liveTimeline) timeline.close()
            error("Thread timeline unavailable")
        }
        return LoadedThreadTimeline(key, timeline, TimelineController(room, timeline), client)
    }
}

class LoadedThreadTimeline internal constructor(
    val key: ThreadKey,
    private val timeline: Timeline,
    val controller: TimelineController,
    private val client: MatrixClient,
) : Closeable {
    private var closed = false

    /** Called only from the composed, successfully loaded target node, never by directory browsing. */
    suspend fun onDisplayed() {
        if (!closed && client.threadDirectory.isAvailable(key) && !closed) {
            client.recentThreads.recordOpened(key)
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            controller.close()
            timeline.close()
        }
    }
}
