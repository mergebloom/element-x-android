/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.impl

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import io.element.android.features.messages.api.MessageDraftNavigationGate

/** Registrations follow actual view ownership; newest visible child wins over its parent. */
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
@Inject
class DefaultMessageDraftNavigationGate : MessageDraftNavigationGate {
    private val owners = linkedMapOf<Any, (() -> Unit) -> Unit>()

    override fun register(navigate: (() -> Unit) -> Unit): AutoCloseable {
        val key = Any()
        owners[key] = navigate
        return AutoCloseable { owners.remove(key) }
    }

    override fun intercept(navigate: () -> Unit): Boolean {
        val owner = owners.values.lastOrNull() ?: return false
        owner(navigate)
        return true
    }
}
