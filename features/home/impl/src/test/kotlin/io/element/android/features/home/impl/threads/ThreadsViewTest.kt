/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.home.impl.threads

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import io.element.android.compound.theme.ElementTheme
import io.element.android.compound.theme.Theme
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.RecentThread
import io.element.android.libraries.matrix.api.threads.ThreadCoverage
import io.element.android.libraries.matrix.api.threads.ThreadDirectoryRow
import io.element.android.libraries.matrix.api.threads.ThreadDirectorySnapshot
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.api.threads.ThreadReadState
import io.element.android.libraries.matrix.test.FakeMatrixClient
import io.element.android.libraries.matrix.test.threads.FakeRecentThreads
import io.element.android.libraries.matrix.test.threads.FakeThreadDirectory
import io.element.android.tests.testutils.robolectric.RobolectricTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-mdpi")
class ThreadsViewTest : RobolectricTest() {
    private val key = ThreadKey(UserId("@alice:example.org"), RoomId("!room:example.org"), EventId("$" + "root"))
    private val row = ThreadDirectoryRow(key, "Room name", "Root preview", ThreadReadState.Unread, 1)

    @Test fun `production route browsing and tab changes never record opened or sent`() = runAndroidComposeUiTest<ComponentActivity> {
        val directory = FakeThreadDirectory().apply { state.value = ThreadDirectorySnapshot(listOf(row), ThreadCoverage.Complete) }
        val recent = FakeRecentThreads()
        val client = FakeMatrixClient(sessionId = key.accountId, threadDirectory = directory, recentThreads = recent)
        setContent { ElementTheme { ThreadsRoute(client, {}, {}) } }
        onNodeWithText("Recent").performClick()
        onNodeWithText("Recent").assertIsSelected()
        onNodeWithText("Unread").performClick()
        assertEquals(emptyList<RecentThread>(), recent.entries.value)
        assertEquals(0, directory.refreshCount)
    }

    @Test fun `production route opens exact account room root without recording from directory`() = runAndroidComposeUiTest<ComponentActivity> {
        val directory = FakeThreadDirectory().apply { state.value = ThreadDirectorySnapshot(listOf(row), ThreadCoverage.Complete) }
        val recent = FakeRecentThreads()
        val client = FakeMatrixClient(sessionId = key.accountId, threadDirectory = directory, recentThreads = recent)
        val opened = mutableListOf<ThreadKey>()
        setContent { ElementTheme { ThreadsRoute(client, { opened += it }, {}) } }
        onNodeWithText("Root preview").performClick()
        waitForIdle()
        assertEquals(listOf(key), opened)
        assertEquals(emptyList<RecentThread>(), recent.entries.value)
    }

    @Test fun `unavailable target cannot silently open room and account switch dismisses old dialog`() = runAndroidComposeUiTest<ComponentActivity> {
        val directory = FakeThreadDirectory().apply {
            available = false
            state.value = ThreadDirectorySnapshot(listOf(row), ThreadCoverage.Partial)
        }
        val client = mutableStateOf(FakeMatrixClient(sessionId = key.accountId, threadDirectory = directory))
        val rooms = mutableListOf<RoomId>()
        setContent { ElementTheme { ThreadsRoute(client.value, {}, { rooms += it }) } }
        onNodeWithText("Root preview").performClick()
        onNodeWithText("Thread unavailable").assertIsDisplayed()
        val output = File("build/outputs/threads-screenshots").apply { mkdirs() }
        onNode(isDialog()).captureRoboImage(File(output, "threads-unavailable-dialog.png").path)
        onNodeWithText("Open room").assertDoesNotExist()
        runOnIdle { client.value = FakeMatrixClient(sessionId = UserId("@bob:example.org")) }
        onNodeWithText("Thread unavailable").assertDoesNotExist()
        assertEquals(emptyList<RoomId>(), rooms)
    }

    @Test fun `partial never displays false empty and check further back is actionable`() = runAndroidComposeUiTest<ComponentActivity> {
        var requests = 0
        setContent {
            ElementTheme {
                ThreadsView(ThreadsState(ThreadDirectorySnapshot(coverage = ThreadCoverage.Partial)), {}, {}, {}, {}, onLoadMore = { requests++ })
            }
        }
        onNodeWithText("No unread threads").assertDoesNotExist()
        onNodeWithText("Check older replies").performClick()
        assertEquals(1, requests)
    }

    @Test fun `routine refresh and failure retry have distinct plain labels`() = runAndroidComposeUiTest<ComponentActivity> {
        val state = mutableStateOf(ThreadsState(ThreadDirectorySnapshot(coverage = ThreadCoverage.Partial)))
        setContent { ElementTheme { ThreadsView(state.value, {}, {}, {}, {}) } }
        onNodeWithText("Refresh").assertIsDisplayed()
        onNodeWithText("Check older replies").assertIsDisplayed()
        onNodeWithText("Retry").assertDoesNotExist()
        for (coverage in listOf(ThreadCoverage.Stale, ThreadCoverage.Error)) {
            runOnIdle { state.value = ThreadsState(ThreadDirectorySnapshot(coverage = coverage)) }
            onNodeWithText("Retry").assertIsDisplayed()
            onNodeWithText("Refresh").assertDoesNotExist()
        }
    }

    @Test fun `clear recent requires confirmation and does not navigate`() = runAndroidComposeUiTest<ComponentActivity> {
        val directory = FakeThreadDirectory().apply { state.value = ThreadDirectorySnapshot(listOf(row), ThreadCoverage.Complete) }
        val recent = FakeRecentThreads().apply { entries.value = listOf(RecentThread(key, openedSequence = 1)) }
        val client = FakeMatrixClient(sessionId = key.accountId, threadDirectory = directory, recentThreads = recent)
        val opened = mutableListOf<ThreadKey>()
        setContent { ElementTheme { ThreadsRoute(client, { opened += it }, {}) } }
        onNodeWithText("Recent").performClick()
        onNodeWithText("Clear recent history").performClick()
        val output = File("build/outputs/threads-screenshots").apply { mkdirs() }
        onNode(isDialog()).captureRoboImage(File(output, "threads-clear-dialog.png").path)
        assertEquals(1, recent.entries.value.size)
        onNodeWithText("Close").performClick()
        assertEquals(1, recent.entries.value.size)
        onNodeWithText("Clear recent history").performClick()
        onNode(hasText("Clear recent history") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        waitForIdle()
        assertEquals(emptyList<RecentThread>(), recent.entries.value)
        assertEquals(emptyList<ThreadKey>(), opened)
        onNodeWithText("Threads you open or message will appear here.").assertIsDisplayed()
    }

    @Test fun `recent keeps both different shortcuts visible`() = runAndroidComposeUiTest<ComponentActivity> {
        val other = key.copy(rootEventId = EventId("$" + "other"))
        setContent {
            ElementTheme {
                ThreadsView(
                    ThreadsState(
                        ThreadDirectorySnapshot(listOf(row)),
                        listOf(RecentThread(key, messagedSequence = 1), RecentThread(other, openedSequence = 2)),
                        true
                    ),
                    {},
                    {},
                    {},
                    {},
                )
            }
        }
        onNodeWithText("Last messaged").assertIsDisplayed()
        onNodeWithText("Last opened").assertIsDisplayed()
    }

    @Test fun `home exposes Threads and preserves Recent when visiting Chats`() = runAndroidComposeUiTest<ComponentActivity> {
        val directory = FakeThreadDirectory().apply { state.value = ThreadDirectorySnapshot(listOf(row), ThreadCoverage.Complete) }
        val client = FakeMatrixClient(sessionId = key.accountId, threadDirectory = directory)
        val navigation = mutableStateOf(io.element.android.features.home.impl.HomeNavigationBarItem.Chats)
        setContent {
            ElementTheme {
                io.element.android.features.home.impl.HomeView(
                    homeState = io.element.android.features.home.impl.aHomeState(
                        currentHomeNavigationBarItem = navigation.value,
                        eventSink = { event ->
                            if (event is io.element.android.features.home.impl.HomeEvent.SelectHomeNavigationBarItem) navigation.value = event.item
                        },
                    ),
                    onRoomClick = { _, _ -> },
                    onSettingsClick = {},
                    onSetUpRecoveryClick = {},
                    onConfirmRecoveryKeyClick = {},
                    onStartChatClick = {},
                    onCreateSpaceClick = {},
                    onRoomSettingsClick = {},
                    onMenuActionClick = {},
                    onReportRoomClick = {},
                    onDeclineInviteAndBlockUser = {},
                    acceptDeclineInviteView = {},
                    leaveRoomView = {},
                    threadsContent = { modifier, padding -> ThreadsRoute(client, {}, {}, modifier, padding) },
                )
            }
        }
        onNodeWithText("Threads").performClick()
        onNodeWithText("Root preview").assertIsDisplayed()
        val output = File("build/outputs/threads-screenshots").apply { mkdirs() }
        onRoot().captureRoboImage(File(output, "home-threads-unread.png").path)
        onNodeWithText("Recent").performClick()
        onNodeWithText("On this device").assertIsDisplayed()
        onRoot().captureRoboImage(File(output, "home-threads-recent.png").path)
        onNodeWithText("Chats").performClick()
        onNodeWithText("Threads").performClick()
        onNodeWithText("Recent").assertIsSelected()
    }

    @Test fun `capture production directory main states in light dark and large text`() = runAndroidComposeUiTest<ComponentActivity> {
        val directory = File("build/outputs/threads-screenshots").apply { mkdirs() }
        val states = ThreadsStatePreviewProvider().values.toList()
        val scenario = mutableStateOf(Triple(false, 1f, states.first()))
        // Use the same real Activity/semantics path as the interaction tests. The
        // standalone composable renderer can hang while creating repeated native windows.
        setContent {
            val (dark, fontScale, state) = scenario.value
            ElementTheme(theme = if (dark) Theme.Dark else Theme.Light) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.size(360.dp, 800.dp)) { ThreadsView(state, {}, {}, {}, {}) }
                }
            }
        }
        listOf(false, true).forEach { dark ->
            listOf(1f, 2f).forEach { fontScale ->
                states.forEachIndexed { index, state ->
                    runOnIdle { scenario.value = Triple(dark, fontScale, state) }
                    val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
                    val unread = onNode(hasText("Unread") and tabRole).assertIsDisplayed()
                    val recent = onNode(hasText("Recent") and tabRole).assertIsDisplayed()
                    if (state.recentSelected) {
                        recent.assertIsSelected()
                        unread.assertIsNotSelected()
                    } else {
                        unread.assertIsSelected()
                        recent.assertIsNotSelected()
                    }
                    onRoot().captureRoboImage(File(directory, "threads-$index-${if (dark) "dark" else "light"}-$fontScale.png").path)
                }
            }
        }
    }
}
