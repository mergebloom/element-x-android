/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.libraries.textcomposer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import androidx.compose.ui.unit.dp
import io.element.android.compound.theme.ElementTheme
import io.element.android.libraries.designsystem.theme.components.Text
import io.element.android.libraries.designsystem.preview.ElementPreview
import io.element.android.libraries.designsystem.preview.PreviewsDayNight
import io.element.android.libraries.textcomposer.model.ReasoningEffort

/** Actual selector used by the send-button popup; short labels have disjoint layout cells. */
@Composable
internal fun ReasoningSelector(selected: ReasoningEffort?, modifier: Modifier = Modifier) {
    val labels = listOf(
        stringResource(R.string.screen_reasoning_low),
        stringResource(R.string.screen_reasoning_medium),
        stringResource(R.string.screen_reasoning_high),
        stringResource(R.string.screen_reasoning_max),
    )
    val restingColor = ElementTheme.colors.bgSubtleSecondary
    val selectedColor = ElementTheme.colors.bgActionPrimaryRest
    val dividerColor = ElementTheme.colors.borderDisabled
    Box(modifier = modifier.size(width = 288.dp, height = 176.dp).testTag("reasoning-selector")) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val radius = size.width / 2f
            val center = Offset(size.width / 2f, size.height - 8.dp.toPx())
            val topLeft = Offset(center.x - radius, center.y - radius)
            val arcSize = Size(radius * 2f, radius * 2f)
            ReasoningEffort.entries.forEachIndexed { index, effort ->
                drawArc(
                    color = if (selected == effort) selectedColor else restingColor,
                    startAngle = 180f + index * 45f,
                    sweepAngle = 45f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                )
                drawArc(
                    color = dividerColor,
                    startAngle = 180f + index * 45f,
                    sweepAngle = 45f,
                    useCenter = true,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            drawCircle(color = restingColor, radius = 45.dp.toPx(), center = center)
            drawCircle(color = dividerColor, radius = 45.dp.toPx(), center = center, style = Stroke(width = 1.dp.toPx()))
        }
        // Short labels sit inside separate cells; full instructions remain in accessibility actions.
        val centers = listOf(51 to 129, 105 to 75, 183 to 75, 237 to 129)
        ReasoningEffort.entries.forEachIndexed { index, effort ->
            Text(
                text = labels[index],
                modifier = Modifier
                    .offset(x = (centers[index].first - 36).dp, y = (centers[index].second - 12).dp)
                    .width(72.dp)
                    .testTag("reasoning-label-${effort.wireValue}"),
                style = ElementTheme.typography.fontBodyMdRegular,
                color = if (selected == effort) ElementTheme.colors.textOnSolidPrimary else ElementTheme.colors.textPrimary,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(R.string.rich_text_editor_reasoning_cancel),
            modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-16).dp),
            style = ElementTheme.typography.fontBodySmRegular,
            color = ElementTheme.colors.textSecondary,
        )
    }
}

internal class ReasoningSelectorPreviewParameterProvider : PreviewParameterProvider<ReasoningEffort?> {
    override val values: Sequence<ReasoningEffort?> = sequenceOf(null) + ReasoningEffort.entries.asSequence()
}

@PreviewsDayNight
@Composable
internal fun ReasoningSelectorPreview(@PreviewParameter(ReasoningSelectorPreviewParameterProvider::class) selected: ReasoningEffort?) = ElementPreview {
    ReasoningSelector(selected)
}
