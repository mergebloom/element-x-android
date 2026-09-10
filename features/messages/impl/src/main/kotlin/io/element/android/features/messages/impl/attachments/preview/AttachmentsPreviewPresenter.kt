/*
 * Copyright (c) 2025 Element Creations Ltd.
 * Copyright 2023-2025 New Vector Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.attachments.preview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import io.element.android.features.messages.impl.attachments.Attachment
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditor
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEditorState
import io.element.android.features.messages.impl.attachments.preview.imageeditor.AttachmentImageEdits
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorPresenter
import io.element.android.features.messages.impl.attachments.video.MediaOptimizationSelectorState
import io.element.android.features.messages.impl.attachments.video.VideoCompressionPresetSelector
import io.element.android.features.messages.impl.messagecomposer.AttachmentCaptionDraft
import io.element.android.features.messages.impl.messagecomposer.AttachmentCaptionDrafts
import io.element.android.libraries.androidutils.file.TemporaryUriDeleter
import io.element.android.libraries.androidutils.file.safeDelete
import io.element.android.libraries.androidutils.hash.hash
import io.element.android.libraries.architecture.Presenter
import io.element.android.libraries.core.coroutine.CoroutineDispatchers
import io.element.android.libraries.core.extensions.runCatchingExceptions
import io.element.android.libraries.core.mimetype.MimeTypes.isMimeTypeVideo
import io.element.android.libraries.di.annotations.SessionCoroutineScope
import io.element.android.libraries.matrix.api.core.EventId
import io.element.android.libraries.matrix.api.permalink.PermalinkBuilder
import io.element.android.libraries.matrix.api.timeline.Timeline
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfig
import io.element.android.libraries.mediaupload.api.MediaOptimizationConfigProvider
import io.element.android.libraries.mediaupload.api.MediaSenderFactory
import io.element.android.libraries.mediaupload.api.MediaUploadInfo
import io.element.android.libraries.mediaupload.api.allFiles
import io.element.android.libraries.preferences.api.store.VideoCompressionPreset
import io.element.android.libraries.textcomposer.model.TextEditorState
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

@AssistedInject
class AttachmentsPreviewPresenter(
    @Assisted private val attachments: ImmutableList<Attachment>,
    @Assisted private val onDoneListener: OnDoneListener,
    @Assisted private val timelineMode: Timeline.Mode,
    @Assisted private val inReplyToEventId: EventId?,
    mediaSenderFactory: MediaSenderFactory,
    private val permalinkBuilder: PermalinkBuilder,
    private val temporaryUriDeleter: TemporaryUriDeleter,
    private val attachmentImageEditor: AttachmentImageEditor,
    private val mediaOptimizationSelectorPresenterFactory: MediaOptimizationSelectorPresenter.Factory,
    private val videoCompressionPresetSelector: VideoCompressionPresetSelector,
    @SessionCoroutineScope private val sessionCoroutineScope: CoroutineScope,
    private val dispatchers: CoroutineDispatchers,
    private val mediaOptimizationConfigProvider: MediaOptimizationConfigProvider,
    @Assisted private val captionDraft: AttachmentCaptionDraft? = null,
    private val captionDrafts: AttachmentCaptionDrafts = AttachmentCaptionDrafts(),
) : Presenter<AttachmentsPreviewState> {
    @AssistedFactory
    interface Factory {
        fun create(
            attachments: ImmutableList<Attachment>,
            timelineMode: Timeline.Mode,
            onDoneListener: OnDoneListener,
            inReplyToEventId: EventId?,
            captionDraft: AttachmentCaptionDraft? = null,
        ): AttachmentsPreviewPresenter
    }

    data class AttachmentAndEdits(
        val attachment: Attachment,
        val edits: AttachmentImageEdits,
    )

    private val mediaSender = mediaSenderFactory.create(timelineMode)

    // The native upload outlives composition. Its lease, caption and edited media
    // must not reset when Appyx removes/re-adds this view or the activity rotates.
    private val sendActionState = mutableStateOf<SendActionState>(SendActionState.Idle)
    private var submitting by mutableStateOf(false)
    private var cancelling by mutableStateOf(false)
    internal val blocksNavigation: Boolean get() = submitting || cancelling
    private val ongoingSendAttachmentJob = mutableStateOf<Job?>(null)
    private var preprocessMediaJob: Job? = null
    private var retainedCaptionEditor: io.element.android.libraries.textcomposer.model.MarkdownTextEditorState? = null
    private var editedTempFiles by mutableStateOf<Map<Int, File>>(emptyMap())
    private var attachmentsAndEdits by mutableStateOf(attachments.map { AttachmentAndEdits(it, AttachmentImageEdits()) })

    @Composable
    override fun present(): AttachmentsPreviewState {
        val coroutineScope = rememberCoroutineScope()

        var showDraftConflict by remember { mutableStateOf(false) }
        var canEditImage by remember { mutableStateOf(false) }
        var imageEditorState by remember { mutableStateOf<AttachmentImageEditorState?>(null) }
        var isApplyingImageEdits by remember { mutableStateOf(false) }
        var displayImageEditError by remember { mutableStateOf(false) }

        val markdownTextEditorState = androidx.compose.runtime.saveable.rememberSaveable(
            saver = io.element.android.libraries.textcomposer.model.MarkdownTextEditorStateSaver,
        ) {
            retainedCaptionEditor ?: io.element.android.libraries.textcomposer.model.MarkdownTextEditorState(captionDraft?.caption, false)
        }.also { retainedCaptionEditor = it }
        val textEditorState by rememberUpdatedState(
            TextEditorState.Markdown(markdownTextEditorState, isRoomEncrypted = null)
        )

        var currentIndex by remember { mutableIntStateOf(0) }

        val editedAttachments by remember {
            derivedStateOf {
                attachmentsAndEdits.map { it.attachment }.toImmutableList()
            }
        }

        val mediaOptimizationSelectorPresenters = remember {
            attachments
                .filterIsInstance<Attachment.Media>()
                .mapIndexed { index, attachment ->
                    mediaOptimizationSelectorPresenterFactory.create(
                        index = index,
                        localMedia = attachment.localMedia,
                        sendAsFile = attachment.sendAsFile,
                    )
                }
        }
        val mediaOptimizationSelectorStates by rememberUpdatedState(
            mediaOptimizationSelectorPresenters.map {
                it.present()
            }.toImmutableList()
        )

        var displayFileTooLargeError by remember { mutableStateOf(false) }

        LaunchedEffect(
            mediaOptimizationSelectorStates,
            imageEditorState,
            isApplyingImageEdits,
            editedAttachments,
        ) {
            if (submitting || sendActionState.value is SendActionState.Done) return@LaunchedEffect
            if (mediaOptimizationSelectorStates.any { it.displayMediaSelectorViews == true } ||
                imageEditorState != null ||
                isApplyingImageEdits
            ) {
                // If any of the media optimization selectors are displayed, we don't want to pre-process the media yet
                return@LaunchedEffect
            }
            // If the media optimization selector is not displayed, we can pre-process the media
            // to prepare it for sending. This is done to avoid blocking the UI thread when the
            // user clicks on the send button.
            val configs = mediaOptimizationSelectorStates.mapIndexed { index, mediaOptimizationSelectorState ->
                getAutoPreprocessMediaOptimizationConfig(
                    mediaAttachment = editedAttachments[index] as Attachment.Media,
                    mediaOptimizationSelectorState = mediaOptimizationSelectorState,
                )
            }
            preprocessMediaJob?.cancel()
            preprocessMediaJob = sessionCoroutineScope.launch(dispatchers.io) {
                preProcessAttachments(
                    attachments = editedAttachments,
                    mediaOptimizationConfigs = configs,
                    displayProgress = false,
                    sendActionState = sendActionState,
                )
            }
        }

        LaunchedEffect(currentIndex) {
            val currentMedia = (attachments.getOrNull(currentIndex) as? Attachment.Media)?.localMedia
            if (currentMedia != null) {
                canEditImage = currentMedia.info.canEditImage() || attachmentImageEditor.canEdit(currentMedia)
            }
        }

        val maxUploadSize = mediaOptimizationSelectorStates.firstNotNullOfOrNull {
            it.maxUploadSize.dataOrNull()
        }
        LaunchedEffect(maxUploadSize) {
            if (maxUploadSize != null) {
                // If file size is not known, we're permissive and allow sending. The SDK will cancel the upload if needed.
                displayFileTooLargeError = attachments.any { attachment ->
                    when (attachment) {
                        is Attachment.Media -> {
                            val isImageFile = attachment.localMedia.info.isImageAttachment()
                            val isVideoFile = attachment.localMedia.info.mimeType.isMimeTypeVideo()
                            if (isImageFile || isVideoFile) {
                                false
                            } else {
                                val fileSize = attachment.localMedia.info.fileSize ?: 0L
                                maxUploadSize < fileSize
                            }
                        }
                    }
                }
            }
        }

        mediaOptimizationSelectorStates.forEach { mediaOptimizationSelectorState ->
            val videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations.dataOrNull()
            LaunchedEffect(videoSizeEstimations) {
                if (videoSizeEstimations != null) {
                    // Check if the video size estimations are too large for the max upload size
                    displayFileTooLargeError = videoSizeEstimations.none { it.canUpload }
                }
            }
        }

        fun handleEvent(event: AttachmentsPreviewEvent) {
            when (event) {
                is AttachmentsPreviewEvent.SendAttachment -> {
                    if (submitting || cancelling || sendActionState.value is SendActionState.Done) return
                    submitting = true
                    val submittedCaption = markdownTextEditorState.getMessageMarkdown(permalinkBuilder).takeIf { it.isNotEmpty() }
                    // The native upload handler deletes prepared files even on failure.
                    // Retry reprocesses the retained preview URI, never reuses those files.
                    ongoingSendAttachmentJob.value = sessionCoroutineScope.launch {
                        try {
                        if (preprocessMediaJob?.isActive != true && sendActionState.value !is SendActionState.Sending.ReadyToUpload) {
                            val configs = mediaOptimizationSelectorStates.map {
                                MediaOptimizationConfig(
                                    compressImages = it.isImageOptimizationEnabled
                                        ?: mediaOptimizationConfigProvider.get().compressImages,
                                    videoCompressionPreset = it.selectedVideoPreset
                                        ?: mediaOptimizationConfigProvider.get().videoCompressionPreset,
                                )
                            }
                            preprocessMediaJob = sessionCoroutineScope.launch(dispatchers.io) {
                                preProcessAttachments(
                                    attachments = editedAttachments,
                                    mediaOptimizationConfigs = configs,
                                    displayProgress = true,
                                    sendActionState = sendActionState,
                                )
                            }
                        }

                        // If the processing was hidden before, make it visible now
                        if (sendActionState.value is SendActionState.Sending.Processing) {
                            sendActionState.value = SendActionState.Sending.Processing(displayProgress = true)
                        }

                        // Wait until the media is ready to be uploaded
                        preprocessMediaJob?.join()
                        val prepared = sendActionState.value
                        if (prepared !is SendActionState.Sending.ReadyToUpload) return@launch
                        val allMediaUploadInfos = prepared.mediaInfos

                        // Pre-processing is done, send the attachment
                        val caption = submittedCaption

                        // Failure retains the source URI; retry prepares fresh upload files.
                        withContext(dispatchers.io) {
                            sendMedia(
                                mediaUploadInfos = allMediaUploadInfos,
                                caption = caption,
                                sendActionState = sendActionState,
                                inReplyToEventId = inReplyToEventId,
                            )
                        }
                        if (sendActionState.value is SendActionState.Done) {
                            captionDraft?.let(captionDrafts::consume)
                            editedAttachments.filterIsInstance<Attachment.Media>().forEach { temporaryUriDeleter.delete(it.localMedia.uri) }
                            editedTempFiles.values.forEach { it.safeDelete() }
                            editedTempFiles = emptyMap()
                            onDoneListener()
                        }
                        } finally {
                            submitting = false
                        }
                    }
                }
                AttachmentsPreviewEvent.KeepEditingDraft -> showDraftConflict = false
                AttachmentsPreviewEvent.DiscardAttachmentDraft -> {
                    if (submitting) return
                    captionDraft?.let(captionDrafts::discard)
                    showDraftConflict = false
                    preprocessMediaJob?.cancel()
                    dismiss(editedAttachments, sendActionState, editedTempFiles)
                }
                AttachmentsPreviewEvent.CancelAndDismiss -> {
                    if (submitting) return
                    if (captionDraft != null && !captionDrafts.restore(captionDraft, markdownTextEditorState.getMessageMarkdown(permalinkBuilder))) {
                        showDraftConflict = true
                        return
                    }
                    displayFileTooLargeError = false
                    displayImageEditError = false
                    isApplyingImageEdits = false

                    // Cancel media preprocessing and sending
                    preprocessMediaJob?.cancel()
                    preprocessMediaJob = null
                    ongoingSendAttachmentJob.value?.cancel()

                    // Dismiss the screen
                    dismiss(
                        attachments = editedAttachments,
                        sendActionState = sendActionState,
                        editedTempFiles = editedTempFiles,
                    )
                }
                AttachmentsPreviewEvent.CancelAndClearSendState -> {
                    if (cancelling) return
                    cancelling = true
                    // Cancelling the waiter alone leaves the independent preparation
                    // worker alive. Join both owners before allowing a fresh attempt.
                    val sendJob = ongoingSendAttachmentJob.value
                    val prepareJob = preprocessMediaJob
                    sendJob?.cancel()
                    prepareJob?.cancel()
                    sessionCoroutineScope.launch {
                        try {
                            sendJob?.join()
                            prepareJob?.join()
                            resetPreparedMedia(sendActionState)
                            ongoingSendAttachmentJob.value = null
                            preprocessMediaJob = null
                        } finally {
                            cancelling = false
                        }
                    }
                }
                AttachmentsPreviewEvent.OpenImageEditor -> {
                    val currentLocalMedia = (attachments.getOrNull(currentIndex) as? Attachment.Media)?.localMedia ?: return
                    val resolvedCanEditImage = canEditImage || currentLocalMedia.info.canEditImage()
                    if (resolvedCanEditImage) {
                        preprocessMediaJob?.cancel()
                        preprocessMediaJob = null
                        resetPreparedMedia(sendActionState)
                        imageEditorState = AttachmentImageEditorState(
                            localMedia = currentLocalMedia,
                            edits = attachmentsAndEdits.get(currentIndex).edits,
                            previewDebug = false,
                        )
                    }
                }
                AttachmentsPreviewEvent.CloseImageEditor -> {
                    imageEditorState = null
                }
                is AttachmentsPreviewEvent.UpdateImageCropRect -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.copy(cropRect = event.cropRect)
                    )
                }
                AttachmentsPreviewEvent.RotateImageToTheLeft -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.rotateAntiClockwise()
                    )
                }
                AttachmentsPreviewEvent.FlipImageHorizontally -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipHorizontally()
                    )
                }
                AttachmentsPreviewEvent.FlipImageVertically -> {
                    val pendingState = imageEditorState ?: return
                    imageEditorState = pendingState.copy(
                        edits = pendingState.edits.flipVertically()
                    )
                }
                AttachmentsPreviewEvent.ResetImageEdits -> {
                    imageEditorState = imageEditorState?.copy(
                        edits = AttachmentImageEdits()
                    )
                }
                AttachmentsPreviewEvent.ApplyImageEdits -> {
                    val pendingState = imageEditorState ?: return
                    if (!pendingState.edits.hasChanges) {
                        editedTempFiles[currentIndex]?.safeDelete()
                        editedTempFiles = editedTempFiles - currentIndex
                        val currentAttachment = attachmentsAndEdits[currentIndex].attachment
                        attachmentsAndEdits = attachmentsAndEdits.toMutableList().also {
                            it[currentIndex] = AttachmentAndEdits(
                                currentAttachment,
                                pendingState.edits,
                            )
                        }.toImmutableList()
                        imageEditorState = null
                        resetPreparedMedia(sendActionState)
                        return
                    }
                    isApplyingImageEdits = true
                    displayImageEditError = false
                    coroutineScope.launch {
                        val result = withContext(dispatchers.io) {
                            attachmentImageEditor.exportEdits(
                                localMedia = pendingState.localMedia,
                                edits = pendingState.edits,
                            )
                        }
                        result.fold(
                            onSuccess = { editedMedia ->
                                editedTempFiles[currentIndex]?.safeDelete()
                                editedTempFiles = editedTempFiles + (currentIndex to editedMedia.file)
                                val currentAttachment = Attachment.Media(editedMedia.localMedia)
                                attachmentsAndEdits = attachmentsAndEdits.toMutableList().also {
                                    it[currentIndex] = AttachmentAndEdits(
                                        currentAttachment,
                                        pendingState.edits,
                                    )
                                }.toImmutableList()
                                imageEditorState = null
                                resetPreparedMedia(sendActionState)
                            },
                            onFailure = {
                                Timber.e(it, "Failed to apply image edits")
                                displayImageEditError = true
                            }
                        )
                        isApplyingImageEdits = false
                    }
                }
                AttachmentsPreviewEvent.ClearImageEditError -> {
                    displayImageEditError = false
                }
                is AttachmentsPreviewEvent.SetCurrentCarouselIndex -> {
                    currentIndex = event.index
                }
            }
        }

        return AttachmentsPreviewState(
            attachments = editedAttachments,
            imageEditorState = imageEditorState,
            canEditImage = canEditImage,
            isApplyingImageEdits = isApplyingImageEdits,
            displayImageEditError = displayImageEditError,
            sendActionState = sendActionState.value,
            textEditorState = textEditorState,
            mediaOptimizationSelectorState = mediaOptimizationSelectorStates[currentIndex],
            displayFileTooLargeError = displayFileTooLargeError,
            currentIndex = currentIndex,
            eventSink = ::handleEvent,
            showDraftConflict = showDraftConflict,
            plainTextConversion = captionDraft?.plainTextConversion == true,
        )
    }

    private suspend fun getAutoPreprocessMediaOptimizationConfig(
        mediaAttachment: Attachment.Media,
        mediaOptimizationSelectorState: MediaOptimizationSelectorState,
    ): MediaOptimizationConfig {
        return if (mediaAttachment.sendAsFile) {
            // If we're sending the media as a file, we can skip image compression and we should select the highest video compression preset that still fits
            // the upload limit (if the estimations are available)
            val videoCompressionPreset = videoCompressionPresetSelector.selectBestVideoPreset(
                expectedVideoPreset = VideoCompressionPreset.HIGH,
                videoSizeEstimations = mediaOptimizationSelectorState.videoSizeEstimations,
            ).dataOrNull() ?: VideoCompressionPreset.HIGH

            MediaOptimizationConfig(
                compressImages = false,
                videoCompressionPreset = videoCompressionPreset,
            )
        } else {
            MediaOptimizationConfig(
                compressImages = mediaOptimizationSelectorState.isImageOptimizationEnabled
                    ?: mediaOptimizationConfigProvider.get().compressImages,
                videoCompressionPreset = mediaOptimizationSelectorState.selectedVideoPreset
                    ?: mediaOptimizationConfigProvider.get().videoCompressionPreset,
            )
        }
    }

    private suspend fun preProcessAttachments(
        attachments: List<Attachment>,
        mediaOptimizationConfigs: List<MediaOptimizationConfig>,
        displayProgress: Boolean,
        sendActionState: MutableState<SendActionState>,
    ) {
        sendActionState.value = SendActionState.Sending.Processing(displayProgress = displayProgress)
        val mediaUploadInfos = mutableListOf<MediaUploadInfo>()
        var completed = false
        try {
            attachments.forEachIndexed { index, attachment ->
                when (attachment) {
                    is Attachment.Media -> {
                        mediaSender.preProcessMedia(
                            uri = attachment.localMedia.uri,
                            mimeType = attachment.localMedia.info.mimeType,
                            mediaOptimizationConfig = mediaOptimizationConfigs[index],
                        ).fold(
                            onSuccess = { mediaUploadInfo ->
                                Timber.d("Media ${mediaUploadInfo.file.path.orEmpty().hash()} finished processing")
                                mediaUploadInfos.add(mediaUploadInfo)
                            },
                            onFailure = {
                                Timber.e(it, "Failed to pre-process media")
                                if (it is CancellationException) {
                                    throw it
                                } else {
                                    sendActionState.value = SendActionState.Failure(it, emptyList())
                                    return
                                }
                            }
                        )
                    }
                }
            }
            sendActionState.value = SendActionState.Sending.ReadyToUpload(mediaUploadInfos)
            completed = true
        } finally {
            if (!completed) mediaUploadInfos.forEach(::cleanUp)
        }
    }

    private fun dismiss(
        attachments: List<Attachment>,
        sendActionState: MutableState<SendActionState>,
        editedTempFiles: Map<Int, File> = emptyMap(),
    ) {
        for (attachment in attachments) {
            when (attachment) {
                is Attachment.Media -> {
                    temporaryUriDeleter.delete(attachment.localMedia.uri)
                }
            }
        }
        val uploadInfos = sendActionState.value.mediaUploadInfoList()
        uploadInfos?.forEach { cleanUp(it) }
        editedTempFiles.values.forEach { it.safeDelete() }
        sendActionState.value = SendActionState.Done
        onDoneListener()
    }

    private fun cleanUp(
        mediaUploadInfo: MediaUploadInfo,
    ) {
        mediaUploadInfo.allFiles().forEach { file ->
            file.safeDelete()
        }
    }

    private fun resetPreparedMedia(sendActionState: MutableState<SendActionState>) {
        sendActionState.value.mediaUploadInfoList()?.forEach(::cleanUp)
        sendActionState.value = SendActionState.Idle
    }

    private suspend fun sendMedia(
        mediaUploadInfos: List<MediaUploadInfo>,
        caption: String?,
        sendActionState: MutableState<SendActionState>,
        inReplyToEventId: EventId?,
    ) = runCatchingExceptions {
        sendActionState.value = SendActionState.Sending.Uploading(mediaUploadInfos)
        if (mediaUploadInfos.size == 1) {
            mediaSender.sendPreProcessedMedia(
                mediaUploadInfo = mediaUploadInfos.first(),
                caption = caption,
                formattedCaption = null,
                inReplyToEventId = inReplyToEventId,
            ).getOrThrow()
        } else {
            mediaSender.sendGallery(
                mediaUploadInfos = mediaUploadInfos,
                caption = caption,
                formattedCaption = null,
                inReplyToEventId = inReplyToEventId,
            ).getOrThrow()
        }
    }.fold(
        onSuccess = {
            mediaUploadInfos.forEach { cleanUp(it) }
            sendActionState.value = SendActionState.Done
        },
        onFailure = { error ->
            Timber.e(error, "Failed to send attachment")
            if (error is CancellationException) {
                throw error
            } else {
                sendActionState.value = SendActionState.Failure(error, mediaUploadInfos)
            }
        }
    )
}
