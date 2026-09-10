/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.ThreadKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientException
import org.matrix.rustcomponents.sdk.ErrorKind
import org.matrix.rustcomponents.sdk.MessageLikeEventContent
import org.matrix.rustcomponents.sdk.RoomSendQueueUpdate
import org.matrix.rustcomponents.sdk.SendQueueRoomUpdateListener
import org.matrix.rustcomponents.sdk.TimelineEventContent
import org.matrix.rustcomponents.sdk.use
import timber.log.Timber

/** One account-wide native queue observer; no composer-specific send hooks. */
internal fun observeConfirmedThreadSends(
    client: Client,
    account: UserId,
    recent: FileRecentThreads,
    pending: PendingThreadSends,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) = scope.launch(dispatcher) {
    recent.load() // establish the shared durable sequence before accepting native callbacks
    val signal = Channel<Unit>(Channel.CONFLATED)
    launch {
        do {
            pending.drain { entry ->
                val room = client.getRoom(entry.room)
                if (room == null) {
                    Timber.w("Discarding confirmed activity for a removed room")
                    return@drain
                }
                try {
                    room.use {
                        room.loadOrFetchEvent(entry.event).use { event ->
                            val root = event.threadRootEventId()
                            event.content().use { eventContent ->
                                val content = (eventContent as? TimelineEventContent.MessageLike)?.content
                                if (root != null && event.senderId() == account.value && content is MessageLikeEventContent.RoomMessage) {
                                    recent.recordConfirmedSent(ThreadKey(account, RoomId(entry.room), EventId(root)), entry)
                                }
                            }
                        }
                    }
                } catch (exception: ClientException.MatrixApi) {
                    if (exception.kind != ErrorKind.Forbidden && exception.kind != ErrorKind.NotFound) throw exception
                    Timber.w("Discarding confirmed activity whose event is permanently inaccessible")
                }
            }
            if (pending.first() == null) {
                signal.receive()
            } else {
                withTimeoutOrNull(PendingThreadSends.RETRY_MILLIS) { signal.receive() }
            }
        } while (isActive)
    }
    while (isActive) {
        try {
            val handle = client.subscribeToSendQueueUpdates(object : SendQueueRoomUpdateListener {
                override fun onUpdate(roomId: String, update: RoomSendQueueUpdate) {
                    try {
                        if (update is RoomSendQueueUpdate.SentEvent) {
                            // Native callback thread: persist these two IDs before acknowledging the callback.
                            pending.append(PendingThreadSends.Entry(roomId, update.eventId))
                            signal.trySend(Unit)
                        }
                    } catch (exception: Exception) {
                        Timber.e(exception, "Unable to persist confirmed thread activity")
                    } finally {
                        update.destroy()
                    }
                }
            })
            try {
                kotlinx.coroutines.awaitCancellation()
            } finally {
                handle.cancel()
                handle.destroy()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            delay(5_000) // subscription setup failure must not silently disable Recent for this session
        }
    }
}
