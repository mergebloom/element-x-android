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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
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
        onNodeWithText("Check further back (uses more memory)").performClick()
        assertEquals(1, requests)
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

    @Test fun `capture production directory main states in light dark and large text`() {
        val directory = File("build/outputs/threads-screenshots").apply { mkdirs() }
        listOf(false, true).forEach { dark ->
            listOf(1f, 2f).forEach { fontScale ->
                ThreadsStatePreviewProvider().values.forEachIndexed { index, state ->
                    captureRoboImage(file = File(directory, "threads-$index-${if (dark) "dark" else "light"}-$fontScale.png")) {
                        ElementTheme(theme = if (dark) Theme.Dark else Theme.Light) {
                            CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                                Box(Modifier.size(360.dp, 800.dp)) { ThreadsView(state, {}, {}, {}, {}) }
                            }
                        }
                    }
                }
            }
        }
    }
}
