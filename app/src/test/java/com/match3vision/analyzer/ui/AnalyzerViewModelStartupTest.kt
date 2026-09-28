package com.match3vision.analyzer.ui

import android.app.Application
import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.InputEnableSwitch
import org.junit.Test

/**
 * Regression: cold-start Instantiation crash when AndroidViewModelFactory
 * cannot find `AnalyzerViewModel(Application)` (Kotlin default-params alone
 * do not emit that overload without @JvmOverloads).
 */
class AnalyzerViewModelStartupTest {

    @Test
    fun applicationOnlyConstructor_existsForAndroidViewModelFactory() {
        val ctor = AnalyzerViewModel::class.java.getConstructor(Application::class.java)
        assertThat(ctor).isNotNull()
        assertThat(ctor.parameterTypes).asList().containsExactly(Application::class.java)
    }

    @Test
    fun inputEnableSwitch_defaultsDisabled() {
        val sw = InputEnableSwitch.disabledByDefault()
        assertThat(sw.isEnabled()).isFalse()
    }

    @Test
    fun autoPlayController_defaultsIdleAndDoesNotRunWithoutBubbleStart() {
        val ctrl = AutoPlayController()
        assertThat(ctrl.mode).isEqualTo(AutoPlayController.Mode.IDLE)
        assertThat(ctrl.enableSwitch().isEnabled()).isFalse()
        assertThat(ctrl.isLoopActive()).isFalse()
    }
}
