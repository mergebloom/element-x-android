/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.element.android.features.messages.impl.attachments

import android.net.Uri
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runAndroidComposeUiTest
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.bumble.appyx.core.children.nodeOrNull
import com.bumble.appyx.core.modality.BuildContext
import com.bumble.appyx.core.navigation.transition.JumpToEndTransitionHandler
import com.bumble.appyx.core.node.Node
import com.bumble.appyx.core.node.build
import com.bumble.appyx.core.node.node
import com.bumble.appyx.navmodel.backstack.BackStack
import com.bumble.appyx.navmodel.backstack.operation.push
import com.google.common.truth.Truth.assertThat
import io.element.android.compound.colors.SemanticColorsLightDark
import io.element.android.compound.theme.ElementTheme
import io.element.android.features.enterprise.api.EnterpriseService
import io.element.android.features.messages.impl.DefaultMessageDraftNavigationGate
import io.element.android.features.messages.impl.R
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewNode
import io.element.android.features.messages.impl.attachments.preview.AttachmentsPreviewPresenter
import io.element.android.features.messages.impl.attachments.preview.OnDoneListener
import io.element.android.features.messages.impl.fixtures.aMediaAttachment
import io.element.android.features.messages.impl.messagecomposer.AttachmentCaptionDraft
import io.element.android.features.messages.impl.messagecomposer.AttachmentCaptionDrafts
import io.element.android.libraries.architecture.BackstackView
import io.element.android.libraries.architecture.BaseFlowNode
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.matrix.test.A_SESSION_ID
import io.element.android.libraries.mediaupload.test.FakeMediaPreProcessor
import io.element.android.libraries.mediaviewer.api.local.LocalMedia
import io.element.android.libraries.mediaviewer.api.local.LocalMediaRenderer
import io.element.android.libraries.mediaviewer.test.viewer.aLocalMedia
import io.element.android.libraries.testtags.TestTags
import io.element.android.tests.testutils.clickOn
import io.element.android.tests.testutils.fake.FakeTemporaryUriDeleter
import io.element.android.tests.testutils.robolectric.RobolectricTest
import io.element.android.tests.testutils.setSafeContent
import io.mockk.every
import io.mockk.mockk
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import org.junit.Test

/**
 * Production preview node, presenter, gate registration, dialogs and Appyx parent up-navigation.
 * Destination nodes model push-only user/share and no-op root continuations. This is NOT a
 * RootFlowNode/intent parser/account-switch integration test or native media-rendering test.
 */
class AttachmentsPreviewNodeNavigationTest : RobolectricTest() {
    @Test
    fun `discard retires preview before push-only user destination and Back returns to composer`() = exercise(Target.User)

    @Test
    fun `discard retires preview before push-only share destination and Back returns to composer`() = exercise(Target.Share)

    @Test
    fun `discard retires preview even when external root continuation does nothing`() = exercise(null)

    private fun exercise(destination: Target?) = runAndroidComposeUiTest<ComponentActivity> {
        val session = TestScope()
        lateinit var host: PreviewHost
        runOnUiThread {
            host = PreviewHost(session).build()
            host.updateLifecycleState(Lifecycle.State.RESUMED)
        }
        try {
            setSafeContent {
                ElementTheme {
                    host.Compose()
                }
            }
            runOnIdle { host.backstack.push(Target.Preview) }
            // Appyx attaches/builds the child and propagates the host lifecycle on Main,
            // independently of the presenter's session test scheduler.
            waitUntil(timeoutMillis = 5_000) {
                runOnUiThread {
                    host.previewCreations == 1 &&
                        host.preview.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        activity!!.captionEditor()?.isAttachedToWindow == true
                }
            }
            val original = runOnIdle {
                host.preview.also {
                    assertThat(it.parent).isSameInstanceAs(host)
                    assertThat(host.children.value.values.map { child -> child.nodeOrNull }).contains(it)
                }
            }
            runOnIdle {
                // The default fake succeeds after a simulated delay. Drain its actual
                // scheduler rather than treating Compose idleness as preprocessing completion.
                session.advanceUntilIdle()
                assertThat(host.mediaPreProcessor.processCallCount).isEqualTo(1)
            }
            awaitIdle()
            // MarkdownTextInput embeds an Android EditText; it has no Compose SetText action.
            // Exercise its installed TextWatcher, as MarkdownTextInputTest does.
            val originalEditor = runOnIdle {
                requireNotNull(activity!!.captionEditor()).also {
                    assertThat(it.isShown).isTrue()
                    assertThat(it.editableText.toString()).isEqualTo("Original caption")
                    it.setText("Edited caption")
                }
            }
            awaitIdle()

            // Use the registration installed by AttachmentsPreviewNode.View, never a manual registration.
            var navigations = 0
            val navigate = {
                assertThat(host.acknowledgements).isEqualTo(1)
                assertThat(host.deletions).isEqualTo(1)
                // This assertion runs INSIDE the continuation, before it can replace or push anything.
                assertThat(host.reachableTargets()).containsExactly(Target.Composer)
                navigations++
                destination?.let { host.backstack.push(it) }
                Unit
            }
            runOnIdle { assertThat(host.gate.intercept(navigate)).isTrue() }
            clickOn(R.string.screen_caption_keep_editing)
            awaitIdle()
            assertThat(navigations).isEqualTo(0)
            assertThat(host.acknowledgements).isEqualTo(0)
            assertThat(host.deletions).isEqualTo(0)
            assertThat(host.preview).isSameInstanceAs(original)
            assertThat(host.reachableTargets()).containsExactly(Target.Composer, Target.Preview).inOrder()
            runOnIdle {
                val editor = requireNotNull(activity!!.captionEditor())
                assertThat(editor).isSameInstanceAs(originalEditor)
                assertThat(editor.isShown).isTrue()
                assertThat(editor.editableText.toString()).isEqualTo("Edited caption")
                editor.setText("Still editable after Keep")
            }
            awaitIdle()
            runOnIdle {
                assertThat(activity!!.captionEditor()!!.editableText.toString()).isEqualTo("Still editable after Keep")
            }

            runOnIdle {
                assertThat(host.gate.intercept(navigate)).isTrue()
                // A second request must not steal the first continuation while the dialog is pending.
                assertThat(host.gate.intercept { error("Second external request replaced the first") }).isTrue()
            }
            clickOn(R.string.screen_caption_discard_attachment)
            awaitIdle()
            waitUntil(timeoutMillis = 5_000) {
                runOnUiThread {
                    original.lifecycle.currentState == Lifecycle.State.DESTROYED && activity!!.captionEditor() == null
                }
            }
            assertThat(navigations).isEqualTo(1)
            assertThat(host.acknowledgements).isEqualTo(1)
            assertThat(host.previewCreations).isEqualTo(1)
            assertThat(host.previewDestructions).isEqualTo(1)
            assertThat(host.reachableTargets()).doesNotContain(Target.Preview)
            onNodeWithText("Still editable after Keep").assertDoesNotExist()
            runOnIdle { assertThat(originalEditor.isAttachedToWindow).isFalse() }
            if (destination != null) {
                onNodeWithText(destination.name).assertExists()
                // Actual Appyx parent Back handling, not a hand-written backstack pop in the test.
                runOnUiThread { activity!!.onBackPressedDispatcher.onBackPressed() }
                awaitIdle()
            }
            onNodeWithText(Target.Composer.name).assertExists()
            assertThat(host.reachableTargets()).containsExactly(Target.Composer)
            assertThat(host.backstack.elements.value.map { it.key.navTarget }).containsExactly(Target.Composer)
            assertThat(original.lifecycle.currentState).isEqualTo(Lifecycle.State.DESTROYED)
            assertThat(host.previewCreations).isEqualTo(1)
            assertThat(host.previewDestructions).isEqualTo(1)
            // The discarded node's DisposableEffect must release the production gate registration.
            runOnIdle { assertThat(host.gate.intercept { error("Disposed preview still owns the gate") }).isFalse() }
        } finally {
            runOnUiThread { host.updateLifecycleState(Lifecycle.State.DESTROYED) }
            session.cancel()
        }
    }

    private fun ComponentActivity.captionEditor(): EditText? = window.decorView.findViewWithTag(TestTags.plainTextEditor.value)

    private enum class Target { Composer, Preview, User, Share }

    private class PreviewHost(private val session: TestScope) : BaseFlowNode<Target>(
        backstack = BackStack(initialElement = Target.Composer, savedStateMap = null),
        buildContext = BuildContext.root(null),
        plugins = emptyList(),
    ) {
        val gate = DefaultMessageDraftNavigationGate()
        val mediaPreProcessor = FakeMediaPreProcessor()
        private val captionDrafts = AttachmentCaptionDrafts()
        private val draft = captionDrafts.capture("Original caption", false, { error("Discard must not restore the source") }, {
            error("Discard must not send or consume the source caption")
        })
        var acknowledgements = 0
        var deletions = 0
        var previewCreations = 0
        var previewDestructions = 0
        lateinit var preview: AttachmentsPreviewNode

        fun reachableTargets() = backstack.elements.value
            .filter { it.targetState != BackStack.State.DESTROYED }
            .map { it.key.navTarget }

        override fun resolve(navTarget: Target, buildContext: BuildContext): Node = when (navTarget) {
            Target.Preview -> AttachmentsPreviewNode(
                buildContext = buildContext,
                plugins = listOf(
                    AttachmentsPreviewNode.Inputs(
                        persistentListOf(aMediaAttachment(aLocalMedia(Uri.parse("content://test/caption.jpg")))),
                        Timeline.Mode.Live,
                        null,
                        draft,
                    )
                ),
                presenterFactory = object : AttachmentsPreviewPresenter.Factory {
                    override fun create(
                        attachments: ImmutableList<Attachment>,
                        timelineMode: Timeline.Mode,
                        onDoneListener: OnDoneListener,
                        inReplyToEventId: EventId?,
                        captionDraft: AttachmentCaptionDraft?,
                    ): AttachmentsPreviewPresenter = with(AttachmentsPreviewPresenterTest()) {
                        session.createAttachmentsPreviewPresenter(
                            attachments = attachments,
                            timelineMode = timelineMode,
                            onDoneListener = {
                                acknowledgements++
                                onDoneListener()
                            },
                            inReplyToEventId = inReplyToEventId,
                            mediaPreProcessor = mediaPreProcessor,
                            captionDraft = captionDraft,
                            captionDrafts = captionDrafts,
                            temporaryUriDeleter = FakeTemporaryUriDeleter { deletions++ },
                        )
                    }
                },
                localMediaRenderer = object : LocalMediaRenderer {
                    @Composable override fun Render(localMedia: LocalMedia) = Unit
                },
                sessionId = A_SESSION_ID,
                enterpriseService = mockk<EnterpriseService> {
                    every { semanticColorsFlow(A_SESSION_ID) } returns flowOf(SemanticColorsLightDark.default)
                },
                draftNavigationGate = gate,
            ).also {
                preview = it
                previewCreations++
                it.lifecycle.addObserver(LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_DESTROY) previewDestructions++
                })
            }
            else -> node(buildContext) { Text(navTarget.name) }
        }

        @Composable
        override fun View(modifier: Modifier) {
            BackstackView(modifier, transitionHandler = JumpToEndTransitionHandler())
        }
    }
}
