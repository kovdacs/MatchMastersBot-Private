package com.match3vision.analyzer.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NotificationPermissionGateTest {
    @Test
    fun denial_isRemembered_andRetryAsksOnce() {
        val gate = NotificationPermissionGate()
        assertThat(gate.shouldRequest(permissionRequired = false, granted = false)).isFalse()
        assertThat(gate.shouldRequest(permissionRequired = true, granted = true)).isFalse()
        assertThat(gate.shouldRequest(permissionRequired = true, granted = false)).isTrue()
        assertThat(gate.shouldRequest(permissionRequired = true, granted = false)).isFalse()

        assertThat(gate.onResult(granted = false)).isEqualTo(NotificationPermissionGate.DENIED)
        assertThat(gate.onResult(granted = false)).contains("Értesítés elutasítva")
        assertThat(gate.onResult(granted = true)).isEqualTo(NotificationPermissionGate.GRANTED)

        gate.allowRetry()
        assertThat(gate.shouldRequest(permissionRequired = true, granted = false)).isTrue()
        assertThat(gate.shouldRequest(permissionRequired = true, granted = false)).isFalse()
    }
}
