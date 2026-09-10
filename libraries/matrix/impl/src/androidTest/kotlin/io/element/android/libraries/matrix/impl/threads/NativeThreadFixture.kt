/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.libraries.matrix.impl.threads

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.matrix.rustcomponents.sdk.Client
import org.matrix.rustcomponents.sdk.ClientBuilder
import org.matrix.rustcomponents.sdk.CrossProcessLockConfig
import org.matrix.rustcomponents.sdk.DateDividerMode
import org.matrix.rustcomponents.sdk.EventOrTransactionId
import org.matrix.rustcomponents.sdk.LogLevel
import org.matrix.rustcomponents.sdk.ReceiptThread
import org.matrix.rustcomponents.sdk.ReceiptType
import org.matrix.rustcomponents.sdk.Room
import org.matrix.rustcomponents.sdk.Session
import org.matrix.rustcomponents.sdk.SlidingSyncVersionBuilder
import org.matrix.rustcomponents.sdk.SqliteStoreBuilder
import org.matrix.rustcomponents.sdk.SyncService
import org.matrix.rustcomponents.sdk.Timeline
import org.matrix.rustcomponents.sdk.TimelineConfiguration
import org.matrix.rustcomponents.sdk.TimelineDiff
import org.matrix.rustcomponents.sdk.TimelineFilter
import org.matrix.rustcomponents.sdk.TimelineFocus
import org.matrix.rustcomponents.sdk.TimelineItem
import org.matrix.rustcomponents.sdk.TimelineListener
import org.matrix.rustcomponents.sdk.TracingConfiguration
import org.matrix.rustcomponents.sdk.initPlatform
import org.matrix.rustcomponents.sdk.sdkGitSha
import org.matrix.rustcomponents.sdk.use
import uniffi.matrix_sdk_ui.TimelineReadReceiptTracking
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** Test-only integration hook: actual pinned FFI objects, never NoHandle/fakes.
 * The only endpoint is the same-run, host-loopback fixture via emulator alias.
 * Helpers return IDs/sender/ownership, never persist or log tokens or bodies.
 */
class NativeThreadFixture private constructor(val seed: JSONObject, private val directory: File) {
    val rooms = seed.getJSONArray("rooms").let { array -> List(array.length()) { array.getString(it) } }
    private val sessions = mutableListOf<NativeFixtureSession>()

    fun root(roomIndex: Int = 0, rootIndex: Int = 0): String =
        seed.getJSONArray("roots").getJSONArray(roomIndex).getJSONObject(rootIndex).getString("root")

    fun reply(roomIndex: Int = 0, rootIndex: Int = 0): String =
        seed.getJSONArray("roots").getJSONArray(roomIndex).getJSONObject(rootIndex).getString("reply")

    suspend fun login(role: String = "alice"): NativeFixtureSession {
        val account = seed.getJSONObject(role)
        val store = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
        val client = buildClient(store)
        try {
            client.login(account.getString("user_id"), account.getString("password"), "Synthetic native test", null)
        } catch (failure: Throwable) {
            client.destroy()
            throw failure
        }
        return attach(client, store)
    }

    /** Reopens the *same SQLite files* and restores the same device; not an OS process restart. */
    suspend fun reopen(previous: NativeFixtureSession): NativeFixtureSession {
        val saved: Session = previous.client.session()
        val store = previous.store
        previous.close()
        val client = buildClient(store)
        client.restoreSession(saved)
        return attach(client, store)
    }

    private suspend fun buildClient(store: File): Client = ClientBuilder()
        .homeserverUrl(BASE_URL)
        .disableWellKnownLookup(true)
        .sqliteStore(SqliteStoreBuilder(File(store, "data").apply { mkdirs() }.path, File(store, "cache").apply { mkdirs() }.path))
        .crossProcessLockConfig(CrossProcessLockConfig.SingleProcess)
        .slidingSyncVersionBuilder(SlidingSyncVersionBuilder.NATIVE)
        .threadsEnabled(true, false)
        .autoEnableBackups(false)
        .autoEnableCrossSigning(false)
        .use { it.build() }

    private suspend fun attach(client: Client, store: File): NativeFixtureSession {
        val sync = client.syncService().finish()
        val session = NativeFixtureSession(client, sync, store)
        sessions += session
        // setRoomSubscriptions REPLACES the set: always pass the fixture's union.
        sync.roomListService().use { it.setRoomSubscriptions(rooms) }
        sync.start()
        eventually("native joined-room ingestion") { rooms.all { client.getRoom(it)?.use { true } == true } }
        return session
    }

    suspend fun send(room: String = rooms[0], root: String? = root(), role: String = "bob"): String =
        operation("send", JSONObject().put("room", room).put("root", root).put("role", role)).getString("event_id")

    suspend fun receipt(event: String, type: String, thread: String? = root(), room: String = rooms[0]) {
        operation("receipt", JSONObject().put("room", room).put("event", event).put("type", type)
            .put("thread", thread).put("role", "alice"))
    }

    private suspend fun operation(name: String, data: JSONObject): JSONObject =
        control(name, data.put("fixture", seed.getString("fixture")))

    suspend fun close() = withContext(NonCancellable) {
        try {
            sessions.asReversed().forEach { session ->
                // Best-effort bounded teardown of every owned native session, even after a timeout.
                runCatching { withTimeout(15_000) { session.close() } }
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    companion object {
        const val BASE_URL = "http://10.0.2.2:18949"

        private val platformInitialized by lazy {
            // Match production's Android/JNI + Tokio initialization, without any logging or telemetry.
            initPlatform(
                TracingConfiguration(
                    logLevel = LogLevel.ERROR,
                    traceLogPacks = emptyList(),
                    extraTargets = emptyList(),
                    writeToStdoutOrSystem = false,
                    writeToFiles = null,
                    sentryConfig = null,
                ),
                useLightweightTokioRuntime = false,
            )
            // The verified AAR embeds the short VERGEN_GIT_SHA, not the full source commit.
            check(sdkGitSha() == "0af7a3217") { "Unexpected pinned native SDK revision" }
            true
        }

        suspend fun create(rootCount: Int = 1): NativeThreadFixture {
            check(platformInitialized)
            val seed = control("new", JSONObject().put("root_count", rootCount))
            val directory = File(InstrumentationRegistry.getInstrumentation().context.filesDir, "native-${UUID.randomUUID()}").apply { mkdirs() }
            return NativeThreadFixture(seed, directory)
        }

        suspend fun audit(checkpoint: String? = null): JSONObject = control("audit", JSONObject().put("checkpoint", checkpoint))

        private suspend fun control(name: String, data: JSONObject): JSONObject = withContext(Dispatchers.IO) {
            val connection = URL("$BASE_URL/_fixture/$name").openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.connectTimeout = 15_000
                connection.readTimeout = 60_000
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(data.toString().toByteArray()) }
                check(connection.responseCode == 200) { "Synthetic control failed: HTTP ${connection.responseCode}" }
                connection.inputStream.bufferedReader().use { JSONObject(it.readText()) }
            } finally {
                connection.disconnect()
            }
        }
    }
}

class NativeFixtureSession(val client: Client, val sync: SyncService, val store: File) {
    private var closed = false
    private val ownedRooms = mutableListOf<Room>()
    fun room(id: String): Room = checkNotNull(client.getRoom(id)).also { ownedRooms += it }

    suspend fun close() {
        if (closed) return
        closed = true
        try {
            sync.stop()
        } finally {
            ownedRooms.forEach { it.destroy() }
            sync.destroy()
            client.destroy()
        }
    }
}

suspend fun eventually(label: String, predicate: suspend () -> Boolean) {
    withTimeout(45_000) {
        while (!predicate()) delay(100)
    }
    // Do not print native exceptions or event contents. Timeout stack identifies call site.
    check(label.isNotBlank())
}

suspend fun Room.receiptId(type: ReceiptType, scope: ReceiptThread, user: String): String? = loadUserReceipt(type, scope, user)?.eventId

data class NativeOrderedEvent(val id: String, val sender: String, val isOwn: Boolean)

/** Native timeline diff order, NOT timestamps or server fixture insertion order. */
suspend fun Room.orderedThread(root: String, expectedIds: Set<String>): List<NativeOrderedEvent> {
    val timeline: Timeline = timelineWithConfiguration(
        TimelineConfiguration(
            focus = TimelineFocus.Thread(root),
            filter = TimelineFilter.All,
            internalIdPrefix = "fixture",
            dateDividerMode = DateDividerMode.DAILY,
            trackReadReceipts = TimelineReadReceiptTracking.MESSAGE_LIKE_EVENTS,
            reportUtds = false,
        )
    )
    val rows = mutableListOf<NativeOrderedEvent?>()
    val lock = Any()
    var listenerFailure: Throwable? = null
    fun TimelineItem.copyEvent(): NativeOrderedEvent? = asEvent()?.let { event ->
        try {
            (event.eventOrTransactionId as? EventOrTransactionId.EventId)?.let { NativeOrderedEvent(it.eventId, event.sender, event.isOwn) }
        } finally {
            event.destroy()
        }
    }
    val handle = timeline.addListener(object : TimelineListener {
        override fun onUpdate(diff: List<TimelineDiff>) {
            synchronized(lock) {
                try {
                    diff.forEach { update ->
                        when (update) {
                            is TimelineDiff.Append -> rows.addAll(update.values.map { it.copyEvent() })
                            is TimelineDiff.Clear -> rows.clear()
                            is TimelineDiff.PushBack -> rows.add(update.value.copyEvent())
                            is TimelineDiff.PushFront -> rows.add(0, update.value.copyEvent())
                            is TimelineDiff.PopBack -> rows.removeAt(rows.lastIndex)
                            is TimelineDiff.PopFront -> rows.removeAt(0)
                            is TimelineDiff.Insert -> rows.add(update.index.toInt(), update.value.copyEvent())
                            is TimelineDiff.Set -> rows[update.index.toInt()] = update.value.copyEvent()
                            is TimelineDiff.Remove -> rows.removeAt(update.index.toInt())
                            is TimelineDiff.Truncate -> rows.subList(update.length.toInt(), rows.size).clear()
                            is TimelineDiff.Reset -> {
                                rows.clear()
                                rows.addAll(update.values.map { it.copyEvent() })
                            }
                        }
                    }
                } catch (failure: Throwable) {
                    listenerFailure = failure
                } finally {
                    diff.forEach { it.destroy() }
                }
            }
        }
    })
    try {
        // Bounded actual FFI pagination; an incomplete tail fails instead of becoming zero unread.
        repeat(8) {
            if (synchronized(lock) { rows.filterNotNull().map { it.id }.containsAll(expectedIds) }) return@repeat
            timeline.paginateBackwards(20u)
            delay(150)
        }
        eventually("native ordered thread tail") {
            synchronized(lock) {
                check(listenerFailure == null) { "Native timeline diff application failed" }
                rows.filterNotNull().map { it.id }.containsAll(expectedIds)
            }
        }
        return synchronized(lock) { rows.filterNotNull().filter { it.id in expectedIds } }
    } finally {
        handle.cancel()
        handle.destroy()
        timeline.destroy()
    }
}
