/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */

package io.element.android.features.home.impl.threads

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.element.android.features.home.impl.R
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.matrix.api.MatrixClient
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.threads.ThreadDirectorySnapshot
import io.element.android.libraries.matrix.api.threads.ThreadKey
import kotlinx.coroutines.launch

@Composable
fun ThreadsRoute(
    client: MatrixClient,
    onOpen: suspend (ThreadKey) -> Unit,
    onOpenRoom: (RoomId) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) = androidx.compose.runtime.key(client.sessionId.value) {
    ThreadsSessionRoute(client, onOpen, onOpenRoom, modifier, contentPadding)
}

@Composable
private fun ThreadsSessionRoute(
    client: MatrixClient,
    onOpen: suspend (ThreadKey) -> Unit,
    onOpenRoom: (RoomId) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    var recentSelected by rememberSaveable { mutableStateOf(false) }
    var directory by remember(client.sessionId) { mutableStateOf(ThreadDirectorySnapshot()) }
    val recent by client.recentThreads.entries.collectAsState()
    var recentRows by remember { mutableStateOf(emptyMap<ThreadKey, io.element.android.libraries.matrix.api.threads.ThreadDirectoryRow>()) }
    LaunchedEffect(client, recentSelected, recent) {
        if (recentSelected) {
            kotlinx.coroutines.coroutineScope {
            val work = kotlinx.coroutines.channels.Channel<ThreadKey>(2)
            launch {
                try {
                recent.forEach { work.send(it.key) }
            } finally {
                work.close()
            }
            }
            repeat(2) {
                launch {
                    for (key in work) {
                        val row = runCatchingExceptions { client.threadDirectory.summary(key) }.getOrNull()
                        if (row != null) recentRows = recentRows + (key to row)
                    }
                }
            }
        }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(client, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            client.threadDirectory.snapshots().collect { directory = it }
        }
    }
    val scope = rememberCoroutineScope()
    var unavailable by remember { mutableStateOf<ThreadKey?>(null) }
    var opening by remember { mutableStateOf(false) }
    var openingJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var roomAvailable by remember { mutableStateOf(false) }
    var clearFailed by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    ThreadsView(
        state = ThreadsState(
            if (recentSelected) {
                directory.copy(rows = directory.rows + recentRows.values.filter { cached -> directory.rows.none { it.key == cached.key } })
            } else {
                directory
            },
            recent,
            recentSelected,
        ),
        onSelectRecent = { recentSelected = it },
        onRefresh = client.threadDirectory::refresh,
        onLoadMore = client.threadDirectory::loadMore,
        onClear = { confirmClear = true },
        onOpen = { key ->
            if (!opening) {
                opening = true
                openingJob = scope.launch {
                    try {
                        val result = runCatchingExceptions {
                            check(key.accountId == client.sessionId && client.threadDirectory.isAvailable(key))
                            onOpen(key)
                        }
                        if (result.isFailure) {
                            roomAvailable = runCatchingExceptions { client.threadDirectory.isRoomAvailable(key.roomId) }.getOrDefault(false)
                            unavailable = key
                        }
                    } finally {
                        opening = false
                    }
                }
            }
        },
        modifier = modifier,
        contentPadding = contentPadding,
    )
    if (opening) {
        io.element.android.libraries.designsystem.components.ProgressDialog(
        showCancelButton = true,
        onDismissRequest = {
            openingJob?.cancel()
            opening = false
        },
    )
    }
    unavailable?.let { key ->
        AlertDialog(
            onDismissRequest = { unavailable = null },
            title = { Text(stringResource(R.string.screen_threads_unavailable)) },
            text = { Text(stringResource(R.string.screen_threads_unavailable_description)) },
            confirmButton = {
                if (roomAvailable) {
                    TextButton(onClick = {
                        unavailable = null
                        onOpenRoom(key.roomId)
                    }) { Text(stringResource(R.string.screen_threads_open_room)) }
                }
            },
            dismissButton = { TextButton(onClick = { unavailable = null }) { Text(stringResource(R.string.screen_threads_close)) } },
        )
    }
    if (confirmClear) {
        AlertDialog(
        onDismissRequest = { confirmClear = false },
        title = { Text(stringResource(R.string.screen_threads_clear)) },
        text = { Text(stringResource(if (clearFailed) R.string.screen_threads_clear_failed else R.string.screen_threads_clear_description)) },
        confirmButton = {
            TextButton(onClick = {
                scope.launch {
                    clearFailed = runCatchingExceptions { client.recentThreads.clear() }.isFailure
                    if (!clearFailed) confirmClear = false
                }
            }) { Text(stringResource(R.string.screen_threads_clear)) }
        },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.screen_threads_close)) } },
    )
    }
}
