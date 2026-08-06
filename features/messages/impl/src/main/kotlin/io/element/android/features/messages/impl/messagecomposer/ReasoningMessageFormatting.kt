/*
 * Copyright (c) 2026 Element Creations Ltd.
 *
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 * Please see LICENSE files in the repository root for full details.
 */

package io.element.android.features.messages.impl.messagecomposer

import io.element.android.libraries.textcomposer.model.ReasoningEffort

internal fun reasoningFormattedBody(body: String, htmlBody: String?, effort: ReasoningEffort?): String? {
    if (effort == null) return htmlBody
    val content = htmlBody ?: body.escapeHtml()
    return "<span data-hermes-reasoning=\"${effort.wireValue}\">$content</span>"
}

private fun String.escapeHtml(): String = buildString(length) {
    this@escapeHtml.forEach { character ->
        append(
            when (character) {
                '&' -> "&amp;"
                '<' -> "&lt;"
                '>' -> "&gt;"
                '"' -> "&quot;"
                '\'' -> "&#39;"
                else -> character
            }
        )
    }
}
