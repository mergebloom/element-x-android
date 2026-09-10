/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.ThreadDirectoryRow
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.api.threads.ThreadReadState
import kotlinx.coroutines.CancellationException
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.Membership
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.SyncSettingsV2
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.use
import uniffi.matrix_sdk_ui.ThreadListPaginationState
import java.util.concurrent.atomic.AtomicInteger

/** Only temporary native Room handles; no JoinedRoom, UI read lifecycle, or receipt writes. */
class RustThreadDirectorySource(private val client: Client, private val account: UserId) : ThreadDirectorySource {
    private val eventBudget = AtomicInteger(NativeOrderedTimeline.EVENT_BUDGET)
    private data class EvidenceKey(val room: RoomId, val root: String, val head: String, val receipts: Set<String>)
    private val resolved = object : LinkedHashMap<EvidenceKey, ExplicitThreadReadResult>(128, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<EvidenceKey, ExplicitThreadReadResult>?) = size > 1_000
    }

    override fun loadMore() {
        eventBudget.updateAndGet { (it.toLong() + NativeOrderedTimeline.EVENT_BUDGET).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    }

    override suspend fun syncAndRooms(): List<RoomId> {
        // Native one-shot sync processes receipts and advances its persisted v2 sync token before returning.
        // No calls to setRoomSubscriptions: the SSS UI subscription set remains untouched.
        client.syncOnceV2(SyncSettingsV2(timeoutMs = 0u, fullState = false))
        // Stable IDs alone do not prove unchanged redactions/decryption/eligibility across sync generations.
        synchronized(resolved) { resolved.clear() }
        return client.rooms().mapNotNull { room ->
            room.use {
                val info = it.roomInfo()
                try {
                    RoomId(it.id()).takeIf { info.membership == Membership.JOINED && !info.isSpace }
                } finally {
                    info.destroy()
                }
            }
        }
    }

    private data class Receipts(val ids: Set<String>, val unthreaded: Boolean)
    private suspend fun receipts(room: Room, root: String): Receipts {
        val ids = mutableSetOf<String>()
        var unthreaded = false
        for (type in listOf(ReceiptType.READ, ReceiptType.READ_PRIVATE)) {
            room.loadUserReceipt(type, ReceiptThread.Thread(root), account.value)?.let { ids += it.eventId }
            room.loadUserReceipt(type, ReceiptThread.Unthreaded, account.value)?.let {
                ids += it.eventId
                unthreaded = true
            }
        }
        return Receipts(ids, unthreaded)
    }

    override suspend fun scan(room: RoomId, emit: suspend (ThreadDirectoryRow) -> Unit) {
        checkNotNull(client.getRoom(room.value)).use { source ->
            check(source.membership() == Membership.JOINED)
            val info = source.roomInfo()
            val name = try {
                info.displayName ?: info.rawName ?: room.value
            } finally {
                info.destroy()
            }
            val list = source.threadListService()
            val seen = mutableSetOf<String>()
            // Shared room ordering for ALL unthreaded anchors in this worker; never a full room timeline per root.
            var roomTimeline: NativeOrderedTimeline? = null
            val budget = eventBudget.get()
            try {
                list.reset()
                do {
                    list.paginate()
                    val page = list.items()
                    try {
                        for (item in page) {
                            val root = item.rootEvent.eventId
                            if (!seen.add(root)) continue
                            val head = item.latestEvent?.eventId ?: root
                            val result = try {
                                val before = receipts(source, root)
                                val evidence = EvidenceKey(room, root, head, before.ids)
                                val cached = synchronized(resolved) { resolved[evidence] }
                                if (cached != null) {
                                    cached
                                } else {
                                    val ordered = when {
                                        before.ids.isEmpty() -> emptyList()
                                        before.unthreaded -> {
                                            val timeline =
                                                roomTimeline ?: NativeOrderedTimeline.open(source, TimelineFocus.Live(false)).also { roomTimeline = it }
                                            timeline.resolve(before.ids + head, budget)
                                        }
                                        else -> {
                                            val timeline = NativeOrderedTimeline.open(source, TimelineFocus.Thread(root))
                                            try {
                                                timeline.resolve(before.ids + head, budget)
                                            } finally {
                                                timeline.close()
                                            }
                                        }
                                    }
                                    // Don't classify a stale list head if the native live window already contains later thread replies.
                                    val fetchedHeadIndex = ordered.indexOfFirst { it.id == head }
                                    val observedHead = if (fetchedHeadIndex < 0) {
                                        head
                                    } else {
                                        ordered.drop(fetchedHeadIndex).lastOrNull { it.root == root || it.id == head }?.id ?: head
                                    }
                                    val unchanged = before == receipts(source, root)
                                    val classified = ExplicitThreadReadReducer.classify(
                                        root,
                                        observedHead,
                                        before.ids,
                                        ordered,
                                        fresh = unchanged && fetchedHeadIndex >= 0,
                                    )
                                    if (unchanged && observedHead == head && classified.state != ThreadReadState.Unknown) {
                                        synchronized(resolved) { resolved[evidence] = classified }
                                    }
                                    classified
                                }
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (_: Exception) {
                                // One inaccessible, malformed or undecryptable thread must not starve later rows/pages.
                                ExplicitThreadReadResult(ThreadReadState.Unknown)
                            }
                            val content = (item.rootEvent.content as? TimelineItemContent.MsgLike)?.content?.kind
                            val preview = (content as? MsgLikeKind.Message)?.content?.body
                            emit(ThreadDirectoryRow(ThreadKey(account, room, EventId(root)), name, preview, result.state, result.count))
                        }
                    } finally {
                        page.forEach { it.destroy() }
                    }
                } while ((list.paginationState() as? ThreadListPaginationState.Idle)?.endReached == false)
            } finally {
                roomTimeline?.close()
                list.destroy()
            }
        }
    }

    override suspend fun isRoomAvailable(room: RoomId): Boolean =
        client.getRoom(room.value)?.use { it.membership() == Membership.JOINED } == true

    override suspend fun summary(key: ThreadKey): ThreadDirectoryRow? {
        if (key.accountId != account) return null
        return client.getRoom(key.roomId.value)?.use { room ->
            val info = room.roomInfo()
            val name = try {
                info.displayName ?: info.rawName ?: key.roomId.value
            } finally {
                info.destroy()
            }
            val preview = try {
                room.loadOrFetchEvent(key.rootEventId.value).use { event ->
                    val content = (event.content() as? org.matrix.rustcomponents.sdk.TimelineEventContent.MessageLike)?.content
                    val message = (content as? org.matrix.rustcomponents.sdk.MessageLikeEventContent.RoomMessage)?.messageType
                    when (message) {
                        is org.matrix.rustcomponents.sdk.MessageType.Text -> message.content.body
                        is org.matrix.rustcomponents.sdk.MessageType.Image -> message.content.filename
                        is org.matrix.rustcomponents.sdk.MessageType.Video -> message.content.filename
                        is org.matrix.rustcomponents.sdk.MessageType.Audio -> message.content.filename
                        is org.matrix.rustcomponents.sdk.MessageType.File -> message.content.filename
                        else -> null
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            }
            ThreadDirectoryRow(key, name, preview, ThreadReadState.Unknown)
        }
    }

    override suspend fun isAvailable(key: ThreadKey): Boolean {
        if (key.accountId != account) return false
        return client.getRoom(key.roomId.value)?.use { room ->
            if (room.membership() != Membership.JOINED) {
                false
            } else {
                room.loadOrFetchEvent(key.rootEventId.value).use { it.eventId() == key.rootEventId.value }
            }
        } == true
    }
}
