/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.libraries.matrix.impl.threads

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.MsgLikeKind
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.TaskHandle
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineItemContent
import org.matrix.rustcomponents.sdk.TimelineListener
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking

/** One temporary, read-only native timeline. The caller always closes it. */
internal class NativeOrderedTimeline private constructor(private val timeline: Timeline) {
    private val lock = Any()
    private val items = mutableListOf<OrderedThreadEvent?>()
    private var revision = 0L
    private var failure: Exception? = null
    private lateinit var handle: TaskHandle

    suspend fun resolve(ids: Set<String>, budget: Int): List<OrderedThreadEvent> {
        // Bound resident event/timeline memory. Unresolved anchors stay Unknown, never false Read.
        while (true) {
            val before = synchronized(lock) { revision }
            val current = snapshot()
            if (ids.all { id -> current.any { it.id == id } } || current.size >= budget) {
                return currentSnapshot()
            }
            val end = timeline.paginateBackwards(100u)
            withTimeoutOrNull(1_000) { while (synchronized(lock) { revision == before }) delay(10) }
            if (end || synchronized(lock) { revision == before }) return currentSnapshot()
        }
    }

    private suspend fun currentSnapshot(): List<OrderedThreadEvent> {
        val nativeHead = timeline.latestEventId() ?: return emptyList()
        withTimeoutOrNull(1_000) { while (snapshot().lastOrNull()?.id != nativeHead) delay(10) }
        return snapshot().takeIf { it.lastOrNull()?.id == nativeHead }.orEmpty()
    }

    private fun snapshot(): List<OrderedThreadEvent> = synchronized(lock) {
        failure?.let { throw it }
        items.filterNotNull().distinctBy { it.id }
    }

    fun close() {
        handle.cancel()
        handle.destroy()
        timeline.destroy()
    }

    private fun TimelineItem.copyEvent(): OrderedThreadEvent? = asEvent()?.let { event ->
        try {
            val id = (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.eventId ?: return@let null
            val content = (event.content as? TimelineItemContent.MsgLike)?.content
            val eligible = when (content?.kind) {
                is MsgLikeKind.Message, is MsgLikeKind.Poll, is MsgLikeKind.Sticker -> true
                is MsgLikeKind.UnableToDecrypt, is MsgLikeKind.Other -> null
                else -> false
            }
            OrderedThreadEvent(id, content?.threadRoot, event.isOwn, eligible)
        } finally {
            event.destroy()
        }
    }

    private val listener = object : TimelineListener {
        override fun onUpdate(diff: List<TimelineDiff>) {
            synchronized(lock) {
                try {
                    for (update in diff) {
                        when (update) {
                            is TimelineDiff.Append -> items.addAll(update.values.map { it.copyEvent() })
                            is TimelineDiff.Clear -> items.clear()
                            is TimelineDiff.PushBack -> items.add(update.value.copyEvent())
                            is TimelineDiff.PushFront -> items.add(0, update.value.copyEvent())
                            is TimelineDiff.PopBack -> items.removeAt(items.lastIndex)
                            is TimelineDiff.PopFront -> items.removeAt(0)
                            is TimelineDiff.Insert -> items.add(update.index.toInt(), update.value.copyEvent())
                            is TimelineDiff.Set -> items[update.index.toInt()] = update.value.copyEvent()
                            is TimelineDiff.Remove -> items.removeAt(update.index.toInt())
                            is TimelineDiff.Truncate -> items.subList(update.length.toInt(), items.size).clear()
                            is TimelineDiff.Reset -> {
                                items.clear()
                                items.addAll(update.values.map { it.copyEvent() })
                            }
                        }
                    }
                    revision++
                } catch (exception: Exception) {
                    failure = exception
                } finally {
                    diff.forEach { it.destroy() }
                }
            }
        }
    }

    companion object {
        const val EVENT_BUDGET = 2_000
        suspend fun open(room: Room, focus: TimelineFocus): NativeOrderedTimeline {
            val timeline = room.timelineWithConfiguration(
                TimelineConfiguration(
                    focus = focus,
                    filter = TimelineFilter.All,
                    internalIdPrefix = "directory",
                    dateDividerMode = DateDividerMode.DAILY,
                    trackReadReceipts = TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS,
                    reportUtds = false,
                )
            )
            return NativeOrderedTimeline(timeline).also {
                try {
                    it.handle = timeline.addListener(it.listener)
                } catch (exception: Exception) {
                    timeline.destroy()
                    throw exception
                }
            }
        }
    }
}
