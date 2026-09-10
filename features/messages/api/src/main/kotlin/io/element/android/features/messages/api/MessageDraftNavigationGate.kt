/*
 * Copyright (c) 2026 Element Creations Ltd.
 * SPDX-License-Identifier: AGPL-3.0-only OR LicenseRef-Element-Commercial.
 */
package io.element.android.features.messages.api

/** App-wide routing boundary for discretionary navigation, never authentication revocation. */
interface MessageDraftNavigationGate {
    /** Register a visible draft owner. Closing removes only that registration. Call on the UI thread. */
    fun register(navigate: (() -> Unit) -> Unit): AutoCloseable

    /** Return true when a visible owner took responsibility for accepting or declining the navigation. */
    fun intercept(navigate: () -> Unit): Boolean
}
