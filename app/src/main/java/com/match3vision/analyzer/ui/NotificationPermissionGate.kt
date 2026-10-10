package com.match3vision.analyzer.ui

/**
 * POST_NOTIFICATIONS is asked once per start. Denial does not loop.
 * Capture continues without a notification. The owner retries from a button.
 */
class NotificationPermissionGate {
    private var asked = false

    fun shouldRequest(permissionRequired: Boolean, granted: Boolean): Boolean {
        if (!permissionRequired || granted || asked) return false
        asked = true
        return true
    }

    fun onResult(granted: Boolean): String = if (granted) {
        GRANTED
    } else {
        DENIED
    }

    fun allowRetry() {
        asked = false
    }

    companion object {
        const val GRANTED = "Értesítés engedélyezve"
        const val DENIED =
            "Értesítés elutasítva. A rögzítés értesítés nélkül folytatódik."
    }
}
