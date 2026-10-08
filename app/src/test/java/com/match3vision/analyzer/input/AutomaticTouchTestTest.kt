package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit/harness proof for isolated AUTOMATIC TOUCH TEST.
 * No Vision, no PASS/HOLD/grid — fixed coords + a11y/executor path only.
 * On-device visible swipe remains operator verification.
 */
class AutomaticTouchTestTest {

    @Test
    fun resolveCoords_fixedFormula_for1080x2340() {
        val t = AutomaticTouchTest(
            executor = RecordingInputGestureExecutor(),
            a11yConnected = { true },
        )
        val g = t.resolveCoords(1080, 2340)
        assertThat(g.startX).isEqualTo(540f)
        assertThat(g.startY).isEqualTo(1053f) // 2340 * 45 / 100
        assertThat(g.endX).isEqualTo(740f) // 540 + 200
        assertThat(g.endY).isEqualTo(1053f)
        assertThat(g.durationMs).isEqualTo(AutomaticTouchTest.GESTURE_DURATION_MS)
        assertThat(g.isSwipe).isTrue()
    }

    @Test
    fun runOnce_success_logsEnabledCreatedDispatchSuccess() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val logger = SmokeTestLogger()
        val t = AutomaticTouchTest(
            executor = exec,
            a11yConnected = { true },
            a11yDiagnose = { "connected=true canPerformGestures=true" },
            logger = logger,
        )
        val r = t.runOnce(1080, 2340)
        assertThat(r.success).isTrue()
        assertThat(r.a11yEnabled).isTrue()
        assertThat(r.gestureCreated).isTrue()
        assertThat(r.dispatchAttempted).isTrue()
        assertThat(r.startX).isEqualTo(540f)
        assertThat(r.startY).isEqualTo(1053f)
        assertThat(r.endX).isEqualTo(740f)
        assertThat(exec.dispatched).hasSize(1)
        assertThat(exec.dispatched[0].startX).isEqualTo(540f)
        assertThat(r.huStatus).contains("a11y=IGEN")
        assertThat(r.huStatus).contains("OK")

        val dump = logger.dump()
        assertThat(dump).contains("AccessibilityService ENABLED=true")
        assertThat(dump).contains("gesture created:")
        assertThat(dump).contains("gesture dispatch happening")
        assertThat(dump).contains("gesture dispatch SUCCESS")
        assertThat(dump).contains("AUTOMATIC TOUCH TEST PASS")
        // Must NOT involve Vision / PASS gates / play loop wording
        assertThat(dump.lowercase()).doesNotContain("gridconf")
        assertThat(dump.lowercase()).doesNotContain("boardconf")
        assertThat(dump).doesNotContain("Vision PASS")
    }

    @Test
    fun runOnce_a11yDisabled_failsWithoutDispatch() {
        val exec = RecordingInputGestureExecutor(ready = false)
        val logger = SmokeTestLogger()
        val t = AutomaticTouchTest(
            executor = exec,
            a11yConnected = { false },
            a11yDiagnose = { "connected=false canPerformGestures=false" },
            logger = logger,
        )
        val r = t.runOnce(1080, 2340)
        assertThat(r.success).isFalse()
        assertThat(r.a11yEnabled).isFalse()
        assertThat(r.gestureCreated).isFalse()
        assertThat(r.dispatchAttempted).isFalse()
        assertThat(exec.dispatched).isEmpty()
        assertThat(r.huStatus).contains("a11y=NEM")
        assertThat(logger.dump()).contains("AccessibilityService ENABLED=false")
        assertThat(logger.dump()).contains("FAIL — AccessibilityService not ENABLED")
    }

    @Test
    fun runOnce_connectedButCannotGesture_failsWithoutDispatch() {
        val exec = RecordingInputGestureExecutor(ready = false)
        val logger = SmokeTestLogger()
        val t = AutomaticTouchTest(
            executor = exec,
            a11yConnected = { true },
            a11yDiagnose = { "connected=true canPerformGestures=false" },
            logger = logger,
        )
        val r = t.runOnce(1080, 2340)
        assertThat(r.success).isFalse()
        assertThat(r.gestureCreated).isFalse()
        assertThat(r.dispatchAttempted).isFalse()
        assertThat(exec.dispatched).isEmpty()
        assertThat(r.huStatus).contains("canPerformGestures=false")
        assertThat(logger.dump()).contains("canPerformGestures=false")
    }

    @Test
    fun runOnce_dispatchFails_logsFail() {
        val exec = RecordingInputGestureExecutor(ready = true)
        exec.failNextDispatch(true)
        val logger = SmokeTestLogger()
        val t = AutomaticTouchTest(
            executor = exec,
            a11yConnected = { true },
            a11yDiagnose = { "connected=true canPerformGestures=true" },
            logger = logger,
        )
        val r = t.runOnce(720, 1600)
        assertThat(r.success).isFalse()
        assertThat(r.gestureCreated).isTrue()
        assertThat(r.dispatchAttempted).isTrue()
        assertThat(r.startX).isEqualTo(360f)
        assertThat(r.startY).isEqualTo(720f) // 1600 * 45 / 100
        assertThat(r.huStatus).contains("FAIL")
        assertThat(logger.dump()).contains("gesture dispatch FAIL")
        assertThat(logger.dump()).contains("AUTOMATIC TOUCH TEST FAIL")
    }

    @Test
    fun exampleConstants_matchDocumented1080x2340() {
        assertThat(AutomaticTouchTest.EXAMPLE_1080x2340_X).isEqualTo(540)
        assertThat(AutomaticTouchTest.EXAMPLE_1080x2340_Y).isEqualTo(1053)
        assertThat(AutomaticTouchTest.EXAMPLE_1080x2340_END_X).isEqualTo(740)
    }

    @Test
    fun accessibilityGestureExecutor_notReady_whenServiceMissing() {
        val exec = AccessibilityGestureExecutor(serviceProvider = { null })
        assertThat(exec.isReady()).isFalse()
        val refused = exec.dispatch(GestureSpec.tap(100f, 200f))
        assertThat(refused).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat((refused as InputDispatchResult.Failed).reason)
            .contains("unguarded dispatch()")
        val manual = exec.dispatchManualTest(GestureSpec.tap(100f, 200f))
        assertThat(manual).isInstanceOf(InputDispatchResult.Failed::class.java)
        assertThat((manual as InputDispatchResult.Failed).reason)
            .contains("AccessibilityService not connected")
    }

    @Test
    fun touchTestHarness_passReportFields() {
        val exec = RecordingInputGestureExecutor(ready = true)
        val t = AutomaticTouchTest(
            executor = exec,
            a11yConnected = { true },
            a11yDiagnose = { "connected=true canPerformGestures=true capabilities=0x20" },
        )
        val r = t.runOnce(1080, 2340)
        // TOUCH_TEST PASS harness fields for parent agent report
        assertThat(r.success).isTrue()
        assertThat(r.screenWidthPx).isEqualTo(1080)
        assertThat(r.screenHeightPx).isEqualTo(2340)
        assertThat(r.a11yDiagnose).contains("canPerformGestures=true")
        assertThat(r.dispatchAttempted).isTrue()
    }
}
