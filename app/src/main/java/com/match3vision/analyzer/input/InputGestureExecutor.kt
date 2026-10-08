package com.match3vision.analyzer.input

/**
 * Abstraction over the real Android input path.
 *
 * Production: [AccessibilityGestureExecutor] backed by AccessibilityService
 * (preferred) or [ShellInputGestureExecutor] (instrumentation / `input swipe`).
 * Unit tests: [RecordingInputGestureExecutor].
 */
interface InputGestureExecutor {
    /** True when the underlying channel is ready (service connected, shell available, …). */
    fun isReady(): Boolean

    fun dispatch(gesture: GestureSpec): InputDispatchResult
}

/**
 * Fail-closed placeholder when no executor was installed.
 *
 * Not a test recorder and not the production accessibility channel.
 * [isReady] is always false, so a forgotten install cannot dispatch.
 */
class UninstalledGestureExecutor : InputGestureExecutor {
    override fun isReady(): Boolean = false

    override fun dispatch(gesture: GestureSpec): InputDispatchResult =
        InputDispatchResult.Failed(
            "executor not installed (production must use AccessibilityGestureExecutor)",
        )
}

/**
 * Test double that records gestures and never touches the OS.
 * JVM harness only. The production install rejects this type.
 */
class RecordingInputGestureExecutor(
    private var ready: Boolean = true,
    private var failNext: Boolean = false,
) : InputGestureExecutor {
    val dispatched: MutableList<GestureSpec> = mutableListOf()

    fun setReady(value: Boolean) {
        ready = value
    }

    fun failNextDispatch(value: Boolean = true) {
        failNext = value
    }

    override fun isReady(): Boolean = ready

    override fun dispatch(gesture: GestureSpec): InputDispatchResult {
        if (!ready) return InputDispatchResult.Failed("input channel not ready")
        if (failNext) {
            failNext = false
            return InputDispatchResult.Failed("simulated dispatch failure")
        }
        dispatched += gesture
        return InputDispatchResult.Dispatched(gesture)
    }
}

/**
 * Documented shell / Instrumentation path: `adb shell input swipe x1 y1 x2 y2 duration`.
 * Used when AccessibilityService is unavailable; still requires [InputEnableSwitch].
 * Does not run on-device unless a [ShellCommandRunner] is injected.
 */
fun interface ShellCommandRunner {
    fun run(command: List<String>): Int
}

class ShellInputGestureExecutor(
    private val runner: ShellCommandRunner,
) : InputGestureExecutor {
    @Volatile private var ready: Boolean = true

    fun setReady(value: Boolean) {
        ready = value
    }

    override fun isReady(): Boolean = ready

    override fun dispatch(gesture: GestureSpec): InputDispatchResult {
        if (!ready) return InputDispatchResult.Failed("shell input not ready")
        val cmd = listOf(
            "input", "swipe",
            gesture.startX.toInt().toString(),
            gesture.startY.toInt().toString(),
            gesture.endX.toInt().toString(),
            gesture.endY.toInt().toString(),
            gesture.durationMs.toString(),
        )
        return try {
            val code = runner.run(cmd)
            if (code == 0) InputDispatchResult.Dispatched(gesture)
            else InputDispatchResult.Failed("shell input exit=$code")
        } catch (t: Throwable) {
            InputDispatchResult.Failed("shell input error: ${t.message}")
        }
    }
}
