/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.threads

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.home.impl.R
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.threads.RecentThread
import io.element.android.libraries.matrix.api.threads.ThreadCoverage
import io.element.android.libraries.matrix.api.threads.ThreadDirectoryRow
import io.element.android.libraries.matrix.api.threads.ThreadDirectorySnapshot
import io.element.android.libraries.matrix.api.threads.ThreadKey
import io.element.android.libraries.matrix.api.threads.ThreadReadState

data class ThreadsState(
    val directory: ThreadDirectorySnapshot = ThreadDirectorySnapshot(),
    val recent: List<RecentThread> = emptyList(),
    val recentSelected: Boolean = false,
)

@Composable
fun ThreadsView(
    state: ThreadsState,
    onSelectRecent: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onClear: () -> Unit,
    onOpen: (ThreadKey) -> Unit,
    onLoadMore: () -> Unit = onRefresh,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
    unreadScroll: LazyListState = rememberLazyListState(),
    recentScroll: LazyListState = rememberLazyListState(),
) {
    Column(modifier.fillMaxSize().background(ElementTheme.colors.bgCanvasDefault)) {
        TabRow(selectedTabIndex = if (state.recentSelected) 1 else 0) {
            Tab(selected = !state.recentSelected, onClick = { onSelectRecent(false) }, text = { Text(stringResource(R.string.screen_threads_unread)) })
            Tab(selected = state.recentSelected, onClick = { onSelectRecent(true) }, text = { Text(stringResource(R.string.screen_threads_recent)) })
        }
        LazyColumn(state = if (state.recentSelected) recentScroll else unreadScroll, contentPadding = contentPadding) {
            if (state.recentSelected) {
                item {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.screen_threads_on_device), style = ElementTheme.typography.fontHeadingSmMedium)
                        Text(stringResource(R.string.screen_threads_recent_description))
                        TextButton(onClick = onClear, enabled = state.recent.isNotEmpty()) { Text(stringResource(R.string.screen_threads_clear)) }
                    }
                }
                val lastOpened = state.recent.maxByOrNull { it.openedSequence }?.takeIf { it.openedSequence > 0 }?.key
                val lastSent = state.recent.maxByOrNull { it.messagedSequence }?.takeIf { it.messagedSequence > 0 }?.key
                val ordered = state.recent.sortedWith(
                    compareByDescending<RecentThread> { it.key == lastOpened || it.key == lastSent }.thenByDescending { it.sequence }
                )
                if (ordered.isEmpty()) item { Text(stringResource(R.string.screen_threads_recent_empty), Modifier.padding(16.dp)) }
                items(ordered, key = { "${it.key.accountId}/${it.key.roomId}/${it.key.rootEventId}" }) { recent ->
                    val row = state.directory.rows.firstOrNull { it.key == recent.key }
                    ThreadRow(
                        row ?: ThreadDirectoryRow(recent.key, stringResource(R.string.screen_threads_saved_room), null, ThreadReadState.Unknown),
                        onOpen
                    ) {
                        if (recent.key == lastSent) Text(stringResource(R.string.screen_threads_last_messaged))
                        if (recent.key == lastOpened) Text(stringResource(R.string.screen_threads_last_opened))
                    }
                }
            } else {
                item {
                    Column(Modifier.padding(16.dp)) {
                        val coverageText = when (state.directory.coverage) {
                            ThreadCoverage.Loading -> R.string.screen_threads_loading
                            ThreadCoverage.Partial -> R.string.screen_threads_partial
                            ThreadCoverage.Complete -> R.string.screen_threads_checked
                            ThreadCoverage.Stale -> R.string.screen_threads_stale
                            ThreadCoverage.Error -> R.string.screen_threads_error
                        }
                        Text(stringResource(coverageText))
                        Text(stringResource(R.string.screen_threads_progress, state.directory.roomsChecked, state.directory.roomsTotal))
                        if (state.directory.coverage == ThreadCoverage.Loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        TextButton(onClick = onRefresh) {
                            val action = when (state.directory.coverage) {
                                ThreadCoverage.Error, ThreadCoverage.Stale -> R.string.screen_threads_retry
                                else -> R.string.screen_threads_refresh
                            }
                            Text(stringResource(action))
                        }
                        if (state.directory.coverage == ThreadCoverage.Partial) {
                            TextButton(onClick = onLoadMore) { Text(stringResource(R.string.screen_threads_more)) }
                        }
                    }
                }
                val visible = state.directory.rows.filter { it.readState != ThreadReadState.Read }
                    .sortedBy { if (it.readState == ThreadReadState.Unread) 0 else 1 }
                if (state.directory.canShowEmptyUnread) item { Text(stringResource(R.string.screen_threads_empty), Modifier.padding(16.dp)) }
                items(visible, key = { "${it.key.accountId}/${it.key.roomId}/${it.key.rootEventId}" }) { row -> ThreadRow(row, onOpen) {} }
            }
        }
    }
}

@Composable
private fun ThreadRow(row: ThreadDirectoryRow, onOpen: (ThreadKey) -> Unit, labels: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable { onOpen(row.key) }.padding(16.dp)) {
        Text(row.roomName, style = ElementTheme.typography.fontHeadingSmMedium)
        Text(row.preview ?: stringResource(R.string.screen_threads_preview_unavailable), maxLines = 3)
        Text(stringResource(
            when (row.readState) {
            ThreadReadState.Unread -> R.string.screen_threads_unread
            ThreadReadState.Read -> R.string.screen_threads_read
            ThreadReadState.Unknown -> R.string.screen_threads_unknown
        }
        ))
        labels()
    }
    HorizontalDivider()
}

class ThreadsStatePreviewProvider : PreviewParameterProvider<ThreadsState> {
    override val values: Sequence<ThreadsState> = sequence {
        val key = ThreadKey(UserId("@sample:example.org"), RoomId("!room:example.org"), EventId("$" + "root"))
        for (coverage in ThreadCoverage.entries) {
            yield(ThreadsState(ThreadDirectorySnapshot(coverage = coverage)))
        }
        val rows = listOf(
            ThreadDirectoryRow(key, "Design discussion", "Reviewing the next release with the team", ThreadReadState.Unread, 2),
            ThreadDirectoryRow(key.copy(rootEventId = EventId("$" + "other")), "Community", "Planning our next meeting", ThreadReadState.Unknown),
        )
        yield(ThreadsState(ThreadDirectorySnapshot(rows, ThreadCoverage.Partial, 12, 15)))
        yield(ThreadsState(recentSelected = true))
        yield(ThreadsState(ThreadDirectorySnapshot(rows, ThreadCoverage.Complete, 15, 15), listOf(RecentThread(key, 3, 2)), true))
    }
}

@PreviewsDayNight
@androidx.compose.ui.tooling.preview.Preview(name = "Large text", fontScale = 2f, widthDp = 360, heightDp = 800)
@androidx.compose.ui.tooling.preview.Preview(name = "Large text dark", fontScale = 2f, widthDp = 360, heightDp = 800, uiMode = 32)
@Composable
internal fun ThreadsViewPreview(@PreviewParameter(ThreadsStatePreviewProvider::class) state: ThreadsState) = ElementPreview {
    ThreadsView(state, {}, {}, {}, {})
}
