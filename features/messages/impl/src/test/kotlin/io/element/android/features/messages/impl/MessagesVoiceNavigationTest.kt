/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package io.element.android.features.messages.impl

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.test.AndroidComposeUiTest
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import com.bumble.appyx.core.modality.BuildContext
import com.google.common.truth.Truth.assertThat
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerEvent
import io.element.android.features.messages.api.timeline.voicemessages.composer.VoiceMessageComposerState
import io.element.android.features.messages.impl.link.LinkEvent
import io.element.android.features.messages.impl.link.aLinkState
import io.element.android.features.messages.impl.threads.ThreadedMessagesNode
import io.element.android.features.messages.impl.timeline.TimelineEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemEvent
import io.element.android.features.messages.impl.timeline.aTimelineItemReadReceipts
import io.element.android.features.messages.impl.timeline.aTimelineState
import io.element.android.features.messages.impl.timeline.components.receipt.aReadReceiptData
import io.element.android.features.messages.impl.timeline.model.TimelineItemThreadInfo
import io.element.android.libraries.architecture.AsyncAction
import io.element.android.libraries.architecture.AsyncData
import io.element.android.libraries.designsystem.components.avatar.AvatarData
import io.element.android.libraries.designsystem.components.avatar.AvatarSize
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.core.RoomId
import io.element.android.libraries.matrix.api.core.ThreadId
import io.element.android.libraries.matrix.api.core.UserId
import io.element.android.libraries.matrix.api.core.toRoomIdOrAlias
import io.element.android.libraries.matrix.api.core.toThreadId
import io.element.android.libraries.matrix.api.permalink.PermalinkData
import io.element.android.libraries.matrix.api.timeline.item.ThreadSummary
import io.element.android.libraries.matrix.test.permalink.FakePermalinkParser
import io.element.android.libraries.matrix.test.room.FakeJoinedRoom
import io.element.android.libraries.textcomposer.model.VoiceMessageRecorderEvent
import io.element.android.libraries.textcomposer.model.VoiceMessageState
import io.element.android.libraries.ui.strings.CommonPlurals
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.pressBack
import io.element.android.tests.testutils.pressBackKey
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import io.element.android.wysiwyg.link.Link
import io.mockk.every
import io.mockk.mockk
import io.mockk.registerInstanceFactory
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.test.TestScope
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

/** Real MessagesView rows/dialogs and node exits; timeline/recorder state is controlled at the presenter boundary. */
class MessagesVoiceNavigationTest : RobolectricTest() {
    init {
        registerInstanceFactory { io.element.android.libraries.matrix.test.AN_EVENT_ID }
        registerInstanceFactory { io.element.android.libraries.matrix.api.core.ThreadId(io.element.android.libraries.matrix.test.AN_EVENT_ID.value) }
        registerInstanceFactory { io.element.android.libraries.matrix.test.A_USER_ID }
        registerInstanceFactory { io.element.android.libraries.matrix.test.A_ROOM_ID }
    }
    @Test fun `thread summary pill guards recording preview and upload`() = checkRoute(Route.ThreadSummary)
    @Test fun `validated cross room link guards recording preview and upload`() = checkRoute(Route.RoomLink)
    @Test fun `validated member link guards recording preview and upload`() = checkRoute(Route.MemberLink)
    @Test fun `read receipt member guards recording preview and upload`() = checkRoute(Route.ReadReceipt)
    @Test fun `toolbar back guards recording preview and upload`() = checkRoute(Route.ToolbarBack)
    @Test fun `system back guards recording preview and upload`() = checkRoute(Route.SystemBack)

    private fun checkRoute(route: Route) {
        for (phase in Phase.entries) {
            runAndroidComposeUiTest {
                val host = Host(this, phase)
                host.mount()
                host.trigger(route)
                waitForIdle()
                assertThat(host.destinations).isEmpty()
                if (phase == Phase.Sending) {
                    onNodeWithText(activity!!.getString(R.string.screen_voice_leave_title)).assertDoesNotExist()
                    assertThat(host.events).doesNotContain(VoiceMessageComposerEvent.DeleteVoiceMessage)
                } else {
                    onNodeWithText(activity!!.getString(R.string.screen_voice_leave_title)).assertExists()
                    clickOn(R.string.screen_voice_keep)
                    assertThat(host.destinations).isEmpty()
                    assertThat(host.events).doesNotContain(VoiceMessageComposerEvent.DeleteVoiceMessage)
                    host.trigger(route)
                    clickOn(R.string.screen_voice_discard)
                    // The production guard must wait for the recorder, not just the dialog button.
                    assertThat(host.destinations).isEmpty()
                    assertThat(host.events.count { it == VoiceMessageComposerEvent.DeleteVoiceMessage }).isEqualTo(1)
                    runOnIdle { host.voice = VoiceMessageState.Idle }
                    waitForIdle()
                    assertThat(host.destinations).containsExactly(route.destination)
                }
                assertThat(host.events).doesNotContain(VoiceMessageComposerEvent.SendVoiceMessage)
                if (phase == Phase.Recording) {
                    assertThat(host.events).contains(VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop))
                }
                if (route == Route.ThreadSummary) {
                    assertThat(host.timelineEvents).contains(TimelineEvent.OpenThread(ROOT.toThreadId(), null))
                }
            }
        }
    }

    @Test
    fun `delayed thread resolution uses the node guard rather than the originating click snapshot`() = runAndroidComposeUiTest<ComponentActivity> {
        val host = Host(this, Phase.Recording)
        host.mount()
        // This is the destination callback used by TimelinePresenter after FocusOnEvent resolves asynchronously.
        runOnIdle { host.node.navigateToThread(ROOT.toThreadId(), EventId("\$focused")) }
        clickOn(R.string.screen_voice_keep)
        assertThat(host.destinations).isEmpty()
        runOnIdle { host.node.navigateToThread(ROOT.toThreadId(), EventId("\$focused")) }
        clickOn(R.string.screen_voice_discard)
        assertThat(host.destinations).isEmpty()
        runOnIdle { host.voice = VoiceMessageState.Idle }
        waitForIdle()
        assertThat(host.destinations).containsExactly("thread")
    }

    @Test
    fun `thread node shares guard for resolved thread room and member destinations`() = runAndroidComposeUiTest<ComponentActivity> {
        val destinations = mutableListOf<String>()
        val callback = mockk<ThreadedMessagesNode.Callback>(relaxed = true)
        every { callback.navigateToThread(any(), any()) } answers { destinations += "thread" }
        every { callback.handlePermalinkClick(any()) } answers { destinations += "room" }
        every { callback.navigateToRoomMemberDetails(any()) } answers { destinations += "member" }
        val node = ThreadedMessagesNode(
            buildContext = BuildContext.root(null),
            plugins = listOf(ThreadedMessagesNode.Inputs(ROOT.toThreadId(), null), callback),
            room = FakeJoinedRoom(),
            analyticsService = mockk(relaxed = true),
            messageComposerPresenterFactory = mockk(relaxed = true),
            timelinePresenterFactory = mockk(relaxed = true),
            presenterFactory = mockk(relaxed = true),
            actionListPresenterFactory = mockk(relaxed = true),
            timelineItemPresenterFactories = mockk(relaxed = true),
            permalinkParser = FakePermalinkParser(),
            appNavigationStateService = mockk(relaxed = true),
            roomMemberModerationRenderer = mockk(relaxed = true),
            emojiPickerRenderer = mockk(relaxed = true),
        )
        val guard = node.voiceDraftNavigationGuard
        val state = voiceState(preview()) {}
        for ((destination, navigate) in listOf<Pair<String, () -> Unit>>(
            "thread" to { node.navigateToThread(ThreadId("\$other-thread"), null) },
            "room" to { node.navigateToRoom(RoomId("!other:example.org"), null, emptyList()) },
            "member" to { node.navigateToMember(MEMBER) },
        )) {
            guard.update(state)
            navigate()
            assertThat(destinations).isEmpty()
            guard.keep()
            assertThat(destinations).isEmpty()
            navigate()
            guard.discard()
            assertThat(destinations).isEmpty()
            guard.update(voiceState(VoiceMessageState.Idle) {})
            assertThat(destinations).containsExactly(destination)
            destinations.clear()
        }
    }

    private class Host(val ui: AndroidComposeUiTest<ComponentActivity>, phase: Phase) {
        val events = mutableListOf<VoiceMessageComposerEvent>()
        val timelineEvents = mutableListOf<TimelineEvent>()
        val destinations = mutableListOf<String>()
        var voice by mutableStateOf<VoiceMessageState>(
            when (phase) {
                Phase.Recording -> VoiceMessageState.Recording(0.seconds, persistentListOf())
                Phase.Preview -> preview()
                Phase.Sending -> preview(sending = true)
            }
        )
        private var linkClick by mutableStateOf<AsyncAction<Link>>(AsyncAction.Uninitialized)
        private var showReceipt by mutableStateOf(false)
        private val parser = FakePermalinkParser()
        private val callback = mockk<MessagesNode.Callback>(relaxed = true).apply {
            every { navigateToThread(any(), any()) } answers { destinations += "thread" }
            every { navigateToRoomMemberDetails(any()) } answers { destinations += "member" }
            every { handlePermalinkClick(any()) } answers { destinations += "room" }
        }
        val node = MessagesNode(
            buildContext = BuildContext.root(null),
            plugins = listOf(MessagesNode.Inputs(null), callback),
            context = ui.activity!!,
            sessionCoroutineScope = TestScope().backgroundScope,
            room = FakeJoinedRoom(),
            analyticsService = mockk(relaxed = true),
            messageComposerPresenterFactory = mockk(relaxed = true),
            timelinePresenterFactory = mockk(relaxed = true),
            presenterFactory = mockk(relaxed = true),
            actionListPresenterFactory = mockk(relaxed = true),
            timelineItemPresenterFactories = mockk(relaxed = true),
            mediaPlayer = mockk(relaxed = true),
            permalinkParser = parser,
            knockRequestsBannerRenderer = mockk(relaxed = true),
            roomMemberModerationRenderer = mockk(relaxed = true),
            eventContentValidationCache = mockk(relaxed = true),
            emojiPickerRenderer = mockk(relaxed = true),
        )
        private val event = aTimelineItemEvent(
            eventId = ROOT,
            threadInfo = TimelineItemThreadInfo.ThreadRoot(ThreadSummary(AsyncData.Uninitialized, 2), null),
            readReceiptState = aTimelineItemReadReceipts(
                receipts = listOf(aReadReceiptData(0, AvatarData(MEMBER.value, "Receipt member", size = AvatarSize.ReadReceiptList))),
            ),
        )

        fun mount() = ui.setSafeContent {
            CompositionLocalProvider(LocalInspectionMode provides true) {
                val timeline = aTimelineState(
                    timelineItems = persistentListOf(event),
                    displayThreadSummaries = true,
                    eventSink = {
                        timelineEvents += it
                        if (it is TimelineEvent.OpenThread) node.navigateToThread(it.threadRootEventId, it.focusedEvent)
                    },
                )
                MessagesView(
                    state = aMessagesState(
                        timelineState = timeline,
                        voiceMessageComposerState = voiceState(voice) {
                            events += it
                            if (it == VoiceMessageComposerEvent.RecorderEvent(VoiceMessageRecorderEvent.Stop)) voice = preview()
                        },
                        linkState = aLinkState(linkClick) { if (it == LinkEvent.Cancel) linkClick = AsyncAction.Uninitialized },
                        readReceiptBottomSheetState = aReadReceiptBottomSheetState(if (showReceipt) event else null) { showReceipt = false },
                    ),
                    voiceDraftNavigationGuard = node.voiceDraftNavigationGuard,
                    onBackClick = { destinations += "back" },
                    onRoomDetailsClick = {},
                    onEventContentClick = { _, _ -> false },
                    onGalleryEventItemClick = { _, _, _ -> false },
                    onUserDataClick = node::navigateToMember,
                    onLinkClick = { url, customTab -> node.onLinkClick(ui.activity!!, false, url, timeline.eventSink, customTab) },
                    onSendLocationClick = {},
                    onCreatePollClick = {},
                    onJoinCallClick = {},
                    onViewAllPinnedMessagesClick = {},
                    onThreadsListClick = {},
                    knockRequestsBannerView = {},
                    customReactionBottomSheet = {},
                )
            }
        }

        fun trigger(route: Route) = with(ui) {
            when (route) {
                Route.ThreadSummary -> onNodeWithText(activity!!.resources.getQuantityString(CommonPlurals.common_replies, 2, 2L)).performClick()
                Route.RoomLink, Route.MemberLink -> runOnIdle {
                    parser.givenResult(
                        if (route == Route.RoomLink) {
                            PermalinkData.RoomLink(RoomId("!other:example.org").toRoomIdOrAlias(), null)
                        } else {
                            PermalinkData.UserLink(MEMBER)
                        }
                    )
                    linkClick = AsyncAction.Success(Link("https://matrix.to/#/target", "validated link"))
                }
                Route.ReadReceipt -> {
                    runOnIdle { showReceipt = true }
                    onNodeWithText("Receipt member").performClick()
                }
                Route.ToolbarBack -> pressBack()
                Route.SystemBack -> runOnUiThread { pressBackKey() }
            }
        }
    }

    private enum class Phase { Recording, Preview, Sending }
    private enum class Route(val destination: String) {
        ThreadSummary("thread"),
        RoomLink("room"),
        MemberLink("member"),
        ReadReceipt("member"),
        ToolbarBack("back"),
        SystemBack("back")
    }

    companion object {
        private val ROOT = EventId("\$thread-root")
        private val MEMBER = UserId("@receipt:example.org")
        private fun preview(sending: Boolean = false) = VoiceMessageState.Preview(
            isPlaying = false,
            isSending = sending,
            showCursor = false,
            playbackProgress = 0f,
            time = 1.seconds,
            waveform = persistentListOf(0.5f),
        )
        private fun voiceState(voice: VoiceMessageState, eventSink: (VoiceMessageComposerEvent) -> Unit) = VoiceMessageComposerState(
            voiceMessageState = voice,
            showPermissionRationaleDialog = false,
            showSendFailureDialog = false,
            keepScreenOn = false,
            eventSink = eventSink,
        )
    }
}
