/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.threads

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.ThreadDirectoryRow
import io.element.android.libraries.matrix.api.threads.ThreadReadState
import io.element.android.libraries.matrix.impl.room.threads.RustThreadsListService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.use
import uniffi.matrix_sdk_ui.ThreadListPaginationState
import java.io.File

/** Real Android AAR / native SSS / SQLite qualification, not app navigation or a fake SDK test. */
@RunWith(AndroidJUnit4::class)
class NativeThreadReceiptTest {
    @Test
    fun explicitReceiptsAndOwnLatestSurviveNativeSqliteReopen() = runBlocking {
        withTimeout(240_000) {
            val fixture = NativeThreadFixture.create()
            try {
                val alice = fixture.login()
                val bob = fixture.login("bob")
                val user = alice.client.userId()
                val room = alice.room(fixture.rooms[0])
                val bobRoom = bob.room(fixture.rooms[0])
                val scope = ReceiptThread.Thread(fixture.root())
                assertNull(room.receiptId(ReceiptType.READ, scope, user))
                assertNull(room.receiptId(ReceiptType.READ_PRIVATE, scope, user))
                // Absence is raw missing evidence, never converted to zero/read in this fixture.
                fixture.receipt(fixture.reply(), "m.read")
                eventually("public receipt ingested by both native clients") {
                    room.receiptId(ReceiptType.READ, scope, user) == fixture.reply() &&
                        bobRoom.receiptId(ReceiptType.READ, scope, user) == fixture.reply()
                }
                val second = fixture.send()
                fixture.receipt(second, "m.read.private")
                eventually("private receipt ingested into native local store") {
                    room.receiptId(ReceiptType.READ_PRIVATE, scope, user) == second
                }
                val incoming = fixture.send()
                val own = fixture.send(role = "alice")
                val ordered = room.orderedThread(fixture.root(), setOf(fixture.reply(), second, incoming, own))
                assertEquals(listOf(fixture.reply(), second, incoming, own), ordered.map { it.id })
                assertTrue(ordered.last().isOwn)
                assertFalse(ordered[2].isOwn)
                // Bob has now ingested the later events, so the private-visibility check is not a pre-sync null.
                bobRoom.orderedThread(fixture.root(), setOf(incoming, own))
                assertNull(bobRoom.receiptId(ReceiptType.READ_PRIVATE, scope, user))
                val explicit = checkNotNull(room.receiptId(ReceiptType.READ_PRIVATE, scope, user))
                assertEquals(second, explicit)
                val afterAnchor = ordered.drop(ordered.indexOfFirst { it.id == explicit } + 1)
                assertEquals(listOf(incoming), afterAnchor.filterNot { it.isOwn }.map { it.id })
                // Explicit-receipt evidence: latest-own does not advance the read anchor.
                assertEquals(fixture.reply(), room.receiptId(ReceiptType.READ, scope, user))
                val device = alice.client.deviceId()
                assertTrue("Actual on-disk SQLite header required", alice.store.walkTopDown().any(::hasSqliteHeader))
                val reopened = fixture.reopen(alice)
                assertEquals(device, reopened.client.deviceId())
                val restoredRoom = reopened.room(fixture.rooms[0])
                eventually("receipt restored from same native SQLite store") {
                    restoredRoom.receiptId(ReceiptType.READ_PRIVATE, scope, user) == second
                }
                // Valid main event does not become a threaded read position.
                val mainEvent = fixture.send(root = null)
                fixture.receipt(mainEvent, "m.read", thread = "main")
                eventually("main receipt ingested") {
                    restoredRoom.receiptId(ReceiptType.READ, ReceiptThread.Main, user) == mainEvent
                }
                assertEquals(second, restoredRoom.receiptId(ReceiptType.READ_PRIVATE, scope, user))
                // Same-event private-thread/public-unthreaded collision. Fresh store must find fallback.
                fixture.receipt(second, "m.read", thread = null)
                val fresh = fixture.login()
                val freshRoom = fresh.room(fixture.rooms[0])
                eventually("applicable unthreaded receipt in a fresh native store") {
                    freshRoom.receiptId(ReceiptType.READ, ReceiptThread.Unthreaded, user) == second
                }
                // Thread-local event order proves applicability here; timestamps are never consulted.
                val fallback = checkNotNull(freshRoom.receiptId(ReceiptType.READ, ReceiptThread.Unthreaded, user))
                assertEquals(listOf(incoming), ordered.drop(ordered.indexOfFirst { it.id == fallback } + 1).filterNot { it.isOwn }.map { it.id })
                fixture.receipt(own, "m.read.private", thread = null)
                eventually("private unthreaded receipt ingested") {
                    freshRoom.receiptId(ReceiptType.READ_PRIVATE, ReceiptThread.Unthreaded, user) == own
                }
                assertNull(fresh.room(fixture.rooms[1]).receiptId(ReceiptType.READ_PRIVATE, ReceiptThread.Thread(fixture.root(1)), user))
                assertTrue(NativeThreadFixture.audit().getInt("sss_successes") > 0)
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun nativePaginationAndProductionListAdapterReachOlderRoots() = runBlocking {
        withTimeout(240_000) {
            val fixture = NativeThreadFixture.create(rootCount = 12)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val alice = fixture.login()
                val room = alice.room(fixture.rooms[0])
                val native = room.threadListService()
                val adapter = RustThreadsListService(native, scope)
                try {
                    val rows = adapter.subscribeToItemUpdates()
                    adapter.paginate().getOrThrow()
                    val first = withTimeout(30_000) { rows.first { it.isNotEmpty() } }
                    assertFalse(first.any { it.rootEvent.eventId.value == fixture.root() })
                    assertTrue(first.size < 12)
                    repeat(4) {
                        if ((native.paginationState() as? ThreadListPaginationState.Idle)?.endReached != true) {
                            adapter.paginate().getOrThrow()
                        }
                    }
                    val complete = withTimeout(30_000) { rows.first { it.size == 12 } }
                    assertEquals((0 until 12).map { fixture.root(rootIndex = it) }.toSet(), complete.map { it.rootEvent.eventId.value }.toSet())
                    assertEquals(12, complete.map { it.rootEvent.eventId.value }.distinct().size)
                    assertTrue((native.paginationState() as ThreadListPaginationState.Idle).endReached)
                    assertTrue(NativeThreadFixture.audit().getInt("thread_pages") >= 2)
                } finally {
                    adapter.destroy()
                }
            } finally {
                scope.cancel()
                fixture.close()
            }
        }
    }

    @Test
    fun receiptOnlyRefreshNewRootAndBrowsingNeverWriteReceipts() = runBlocking {
        withTimeout(240_000) {
            val fixture = NativeThreadFixture.create()
            try {
                val alice = fixture.login()
                val room = alice.room(fixture.rooms[0])
                val user = alice.client.userId()
                val scope = ReceiptThread.Thread(fixture.root())
                val before = NativeThreadFixture.audit("browse_before").getInt("receipt_writes")
                room.threadListService().use { list ->
                    list.paginate()
                    val originalHead = list.items().single().latestEvent?.eventId
                    fixture.receipt(fixture.reply(), "m.read.private")
                    eventually("receipt-only update with unchanged native list head") {
                        room.receiptId(ReceiptType.READ_PRIVATE, scope, user) == fixture.reply()
                    }
                    assertEquals(originalHead, list.items().single().latestEvent?.eventId)
                    val newer = fixture.send()
                    val newRoot = fixture.send(root = null)
                    fixture.send(root = newRoot)
                    // A bounded reset/refetch is required; don't assume unknown roots have live list diffs.
                    list.reset()
                    list.paginate()
                    val refreshed = list.items()
                    assertEquals(setOf(fixture.root(), newRoot), refreshed.map { it.rootEvent.eventId }.toSet())
                    assertEquals(newer, refreshed.single { it.rootEvent.eventId == fixture.root() }.latestEvent?.eventId)
                    room.orderedThread(fixture.root(), setOf(fixture.reply(), newer))
                    fixture.rooms.forEach { id ->
                        alice.room(id).threadListService().use { other ->
                            other.paginate()
                            other.items()
                        }
                    }
                    assertEquals(
                        "Browsing must make zero write requests (including redundant writes)",
                        before,
                        NativeThreadFixture.audit("browse_after").getInt("receipt_writes")
                    )
                }
                // Positive control: prove the proxy really detects a native receipt-write request.
                room.sendSingleReceipt(ReceiptType.READ, scope, fixture.reply())
                eventually("receipt auditing positive control") {
                    NativeThreadFixture.audit().getInt("receipt_writes") == before + 1
                }
            } finally {
                fixture.close()
            }
        }
    }

    @Test
    fun productionDirectoryResolvesReceiptChangesAcrossRoomsWithoutWriting() = runBlocking {
        withTimeout(240_000) {
            val fixture = NativeThreadFixture.create()
            try {
                val session = fixture.login()
                val account = UserId(session.client.userId())
                val source = RustThreadDirectorySource(session.client, account)
                val before = NativeThreadFixture.audit().getInt("receipt_writes")
                suspend fun rows(): List<ThreadDirectoryRow> {
                    val rooms = source.syncAndRooms()
                    assertEquals(fixture.rooms.toSet(), rooms.map { it.value }.toSet())
                    val result = mutableListOf<ThreadDirectoryRow>()
                    rooms.forEach { room -> source.scan(room) { result += it } }
                    assertEquals(2, result.size)
                    assertTrue(result.all { it.key.accountId == account })
                    return result
                }
                assertTrue(rows().all { it.readState == ThreadReadState.Unknown })
                fixture.receipt(fixture.reply(), "m.read")
                val incoming = fixture.send()
                val own = fixture.send(role = "alice")
                eventually("production explicit unread despite own latest") {
                    val current = rows()
                    current.single { it.key.roomId.value == fixture.rooms[0] }.let {
                        it.key.rootEventId.value == fixture.root() && it.readState == ThreadReadState.Unread && it.unreadCount == 1
                    } && current.single { it.key.roomId.value == fixture.rooms[1] }.readState == ThreadReadState.Unknown
                }
                // Private receipt-only advancement, not a new head, must invalidate previous Unread.
                fixture.receipt(own, "m.read.private")
                eventually("production private receipt-only refresh") {
                    rows().single { it.key.roomId.value == fixture.rooms[0] }.readState == ThreadReadState.Read
                }
                // An unthreaded receipt on another main-timeline event applies by actual native room order.
                val mainEvent = fixture.send(root = null)
                fixture.receipt(mainEvent, "m.read.private", thread = null)
                eventually("production unrelated unthreaded anchor resolves") {
                    rows().single { it.key.roomId.value == fixture.rooms[0] }.readState == ThreadReadState.Read
                }
                val latestIncoming = fixture.send()
                assertTrue(incoming != latestIncoming)
                eventually("production new incoming after room-wide explicit anchor") {
                    rows().single { it.key.roomId.value == fixture.rooms[0] }.let {
                        it.readState == ThreadReadState.Unread && it.unreadCount == 1
                    }
                }
                assertTrue(source.isAvailable(io.element.android.libraries.matrix.api.threads.ThreadKey(
                    account,
                    RoomId(fixture.rooms[0]),
                    io.element.android.libraries.matrix.api.core.EventId(fixture.root()),
                )))
                assertEquals(before, NativeThreadFixture.audit().getInt("receipt_writes"))
                assertTrue(NativeThreadFixture.audit().getInt("sss_successes") > 0)
            } finally {
                fixture.close()
            }
        }
    }

    private fun hasSqliteHeader(file: File): Boolean = file.isFile && file.length() >= 16 &&
        file.inputStream().use { input ->
            val header = ByteArray(16)
            input.read(header) == 16 && String(header, Charsets.US_ASCII) == "SQLite format 3\u0000"
        }
}
