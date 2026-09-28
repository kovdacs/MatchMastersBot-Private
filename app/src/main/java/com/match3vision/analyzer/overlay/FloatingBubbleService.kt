package com.match3vision.analyzer.overlay

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.match3vision.analyzer.capture.CaptureFrame
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.BotLoopOutcome
import com.match3vision.analyzer.input.InputThresholds
import com.match3vision.analyzer.input.MatchMastersAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Small movable SYSTEM_ALERT_WINDOW bubble:
 * INDÍTÁS → continuous Vision→Move→swipe→re-analyze
 * SZÜNET → pause loop
 * TESZT ÉRINTÉS → isolated fixed-coordinate touch (no Vision / no play loop)
 * STOP → remove bubble + stop capture/input
 *
 * Does **not** auto-start the loop; user must tap INDÍTÁS on the bubble.
 * Default position: top-end corner so the playfield stays clear.
 */
class FloatingBubbleService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loopJob: Job? = null
    private var windowManager: WindowManager? = null
    private var bubbleView: View? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private var statusView: TextView? = null
    private var startBtn: Button? = null
    private var pauseBtn: Button? = null
    private var touchTestBtn: Button? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        showBubble()
        AutoPlaySession.beginNewSession()
        AutoPlaySession.publish(bubbleVisible = true)
        refreshBubbleUi()
        Timber.i("FloatingBubbleService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_ALL -> {
                stopAllAndSelf()
                return START_NOT_STICKY
            }
            ACTION_START_LOOP -> {
                startLoopFromBubble()
            }
            ACTION_PAUSE_LOOP -> {
                pauseLoopFromBubble()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        loopJob?.cancel()
        loopJob = null
        removeBubble()
        if (instance === this) instance = null
        scope.cancel()
        Timber.i("FloatingBubbleService destroyed")
        super.onDestroy()
    }

    private fun showBubble() {
        if (bubbleView != null) return
        val density = resources.displayMetrics.density
        val pad = (8 * density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(0xCC1B1B1B.toInt())
            elevation = 8 * density
        }
        val title = TextView(this).apply {
            text = "Match3 Auto"
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11f
        }
        statusView = TextView(this).apply {
            text = "várakozik"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 10f
            maxLines = 2
        }
        startBtn = Button(this).apply {
            text = "INDÍTÁS"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { startLoopFromBubble() }
        }
        pauseBtn = Button(this).apply {
            text = "SZÜNET"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { pauseLoopFromBubble() }
        }
        touchTestBtn = Button(this).apply {
            text = "TESZT ÉRINTÉS"
            textSize = 10f
            isAllCaps = false
            setOnClickListener { runTouchTestFromBubble() }
        }
        val stopBtn = Button(this).apply {
            text = "STOP"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { stopAllAndSelf() }
        }
        root.addView(title)
        root.addView(statusView)
        root.addView(startBtn)
        root.addView(pauseBtn)
        root.addView(touchTestBtn)
        root.addView(stopBtn)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (8 * density).toInt()
            y = (120 * density).toInt()
            width = (132 * density).toInt()
        }
        // Drag only from title so INDÍTÁS / SZÜNET / TESZT ÉRINTÉS / STOP still receive clicks.
        attachDrag(title, root, params)
        layoutParams = params
        bubbleView = root
        windowManager?.addView(root, params)
    }

    private fun attachDrag(
        handle: View,
        windowView: View,
        params: WindowManager.LayoutParams,
    ) {
        var lastX = 0
        var lastY = 0
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = params.x
                    lastY = params.y
                    startX = event.rawX.toInt()
                    startY = event.rawY.toInt()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX.toInt() - startX
                    val dy = event.rawY.toInt() - startY
                    // TOP|END: x grows toward start (left)
                    params.x = (lastX - dx).coerceAtLeast(0)
                    params.y = (lastY + dy).coerceAtLeast(0)
                    windowManager?.updateViewLayout(windowView, params)
                    true
                }
                else -> false
            }
        }
    }

    private fun removeBubble() {
        val v = bubbleView ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Throwable) {
        }
        bubbleView = null
        layoutParams = null
        statusView = null
        startBtn = null
        pauseBtn = null
        touchTestBtn = null
    }

    private fun startLoopFromBubble() {
        val ok = AutoPlaySession.controller.onBubbleStart()
        if (!ok) {
            AutoPlaySession.refreshFromController()
            refreshBubbleUi()
            return
        }
        AutoPlaySession.syncFrameGateFromMode()
        AutoPlaySession.refreshFromController("fut")
        refreshBubbleUi()
        // Prefer Match Masters visible: ask analyzer Activity to background itself.
        // Large Compose debug panels must not cover MediaProjection during FUT.
        sendBroadcast(Intent(ACTION_MINIMIZE_ANALYZER).setPackage(packageName))
        ensureLoopRunning()
    }

    private fun pauseLoopFromBubble() {
        AutoPlaySession.controller.onBubblePause()
        AutoPlaySession.syncFrameGateFromMode()
        AutoPlaySession.refreshFromController("szünet")
        refreshBubbleUi()
    }

    private fun stopAllAndSelf() {
        loopJob?.cancel()
        loopJob = null
        AutoPlaySession.controller.onBubbleStop("bubble STOP")
        AutoPlaySession.syncFrameGateFromMode()
        AutoPlaySession.endSession()
        CaptureService.stop(this)
        removeBubble()
        stopSelf()
    }


    /**
     * Isolated AUTOMATIC TOUCH TEST — one fixed-coordinate gesture, no Vision / play loop.
     *
     * Critical Android fixes vs immediate onClick dispatch:
     * 1) Delay after button MotionEvent so overlay touch does not cancel the injected gesture.
     * 2) FLAG_NOT_TOUCHABLE on the bubble while the gesture runs (overlay must not steal it).
     * 3) Run await off the main thread so GestureResultCallback can complete (no deadlock).
     * 4) Surface Hungarian Toast/status: a11y igen/nem, coords, dispatch ok/fail reason.
     */
    private fun runTouchTestFromBubble() {
        // Keep Match Masters visible so the user can see the touch.
        sendBroadcast(Intent(ACTION_MINIMIZE_ANALYZER).setPackage(packageName))
        val (w, h) = screenSizePx()
        val a11yNow = MatchMastersAccessibilityService.isConnected()
        val diagnose = MatchMastersAccessibilityService.diagnoseConnected()
        Timber.i(
            "TOUCH_TEST: bubble TESZT ÉRINTÉS pressed screen=%dx%d a11y=%s diagnose=%s",
            w, h, a11yNow, diagnose,
        )
        val preHu = if (a11yNow) {
            "a11y=IGEN képernyő=${w}x${h} — indítás ${TOUCH_TEST_CLICK_DELAY_MS}ms…"
        } else {
            "a11y=NEM képernyő=${w}x${h} — kapcsold be a Kisegítő lehetőségeket"
        }
        statusView?.text = preHu
        Toast.makeText(this, preHu, Toast.LENGTH_SHORT).show()
        AutoPlaySession.publish(statusText = preHu, a11yReady = a11yNow)

        // Avoid concurrent auto-play gestures cancelling the isolated test swipe.
        if (AutoPlaySession.controller.isLoopActive()) {
            AutoPlaySession.controller.onBubblePause()
            AutoPlaySession.syncFrameGateFromMode()
            Timber.i("TOUCH_TEST: auto-play paused for isolated touch test")
        }

        // Let the overlay button MotionEvent finish; otherwise dispatchGesture is often cancelled.
        setBubbleTouchable(false)
        Handler(Looper.getMainLooper()).postDelayed({
            scope.launch(Dispatchers.Default) {
                val result = try {
                    AutoPlaySession.touchTest.runOnce(w, h)
                } catch (t: Throwable) {
                    Timber.e(t, "TOUCH_TEST: runOnce threw")
                    null
                }
                withContext(Dispatchers.Main) {
                    setBubbleTouchable(true)
                    if (result == null) {
                        val fail = "a11y=? FAIL: exception — lásd logcat TOUCH_TEST"
                        statusView?.text = fail
                        Toast.makeText(this@FloatingBubbleService, fail, Toast.LENGTH_LONG).show()
                        AutoPlaySession.publish(statusText = fail)
                        return@withContext
                    }
                    val label = result.huStatus
                    statusView?.text = label
                    Toast.makeText(this@FloatingBubbleService, label, Toast.LENGTH_LONG).show()
                    AutoPlaySession.publish(
                        statusText = label,
                        a11yReady = result.a11yEnabled,
                    )
                    Timber.i(
                        "TOUCH_TEST: result success=%s reason=%s hu=%s",
                        result.success,
                        result.reason,
                        result.huStatus,
                    )
                    for (line in AutoPlaySession.touchTest.logger().lines().takeLast(16)) {
                        Timber.i("TOUCH_TEST_LOG: %s", line)
                    }
                }
            }
        }, TOUCH_TEST_CLICK_DELAY_MS)
    }

    /** Physical screen pixels for fixed-coordinate touch test (not Vision). */
    private fun screenSizePx(): Pair<Int, Int> {
        val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as WindowManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val dm = resources.displayMetrics
            dm.widthPixels to dm.heightPixels
        }
    }

    /**
     * While false, bubble overlay ignores touches so injected a11y gestures reach the game.
     */
    private fun setBubbleTouchable(touchable: Boolean) {
        val v = bubbleView ?: return
        val p = layoutParams ?: return
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (touchable) {
            p.flags and flag.inv()
        } else {
            p.flags or flag
        }
        try {
            windowManager?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            Timber.w(t, "TOUCH_TEST: setBubbleTouchable(%s) failed", touchable)
        }
    }

    private fun ensureLoopRunning() {
        if (loopJob?.isActive == true) return
        loopJob = scope.launch {
            while (isActive) {
                val ctrl = AutoPlaySession.controller
                if (ctrl.mode == AutoPlayController.Mode.STOPPED) break
                if (!ctrl.isLoopActive()) {
                    refreshBubbleUi()
                    delay(200L)
                    continue
                }
                val manager = CaptureService.managerOrNull()
                val frame = manager?.latestFrame?.value
                if (frame == null) {
                    AutoPlaySession.refreshFromController("vár képkockára…")
                    refreshBubbleUi()
                    delay(150L)
                    continue
                }
                // Prefer a fresh frame when possible; still allow first / same if only one.
                val useFrame = frame
                try {
                    val vision = withContext(Dispatchers.Default) {
                        analyzeFrame(useFrame)
                    }
                    val cycle = withContext(Dispatchers.Default) {
                        ctrl.runCycleIfActive(vision)
                    }
                    if (cycle == null) {
                        delay(120L)
                        continue
                    }
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    when (cycle.outcome) {
                        BotLoopOutcome.CONTINUE -> {
                            val executed = cycle.executed
                            if (executed is AutomaticInputEngine.ExecuteResult.Executed) {
                                statusView?.text = "húzás #${ctrl.moveCount}"
                                val waitMs = cycle.animationWaitMs.coerceAtLeast(
                                    InputThresholds.ANIMATION_WAIT_MS,
                                )
                                delay(waitMs)
                                // Wait for a newer capture frame when available.
                                val startId = System.identityHashCode(useFrame)
                                var waited = 0L
                                var after = CaptureService.managerOrNull()?.latestFrame?.value
                                while (isActive && waited < 2_500L &&
                                    after != null &&
                                    System.identityHashCode(after) == startId
                                ) {
                                    delay(100L)
                                    waited += 100L
                                    after = CaptureService.managerOrNull()?.latestFrame?.value
                                }
                                val afterFrame = after ?: CaptureService.managerOrNull()?.latestFrame?.value
                                if (afterFrame == null) {
                                    ctrl.onFailsafePause("nincs húzás utáni képkocka")
                                    AutoPlaySession.refreshFromController()
                                    refreshBubbleUi()
                                    continue
                                }
                                val afterVision = withContext(Dispatchers.Default) {
                                    analyzeFrame(afterFrame)
                                }
                                val fb = withContext(Dispatchers.Default) {
                                    ctrl.completeFeedback(executed.beforeBoardHash, afterVision)
                                }
                                AutoPlaySession.refreshFromController()
                                refreshBubbleUi()
                                if (fb?.outcome == BotLoopOutcome.STOP) {
                                    delay(300L)
                                } else {
                                    delay(80L)
                                }
                            } else {
                                // CONTINUE without execute (shouldn't happen often) — brief pause.
                                delay(120L)
                            }
                        }
                        BotLoopOutcome.HOLD -> {
                            // Soft HOLD (gates / board not ready): retry next frame.
                            delay(280L)
                        }
                        BotLoopOutcome.STOP -> {
                            delay(400L)
                        }
                    }
                } catch (t: Throwable) {
                    Timber.e(t, "auto-play loop error")
                    ctrl.onFailsafePause("hiba: ${t.message}")
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    delay(500L)
                }
            }
        }
    }

    private fun analyzeFrame(frame: CaptureFrame) =
        AutoPlaySession.frameAnalyzer.analyzePixels(
            pixels = IntArray(frame.width * frame.height).also { buf ->
                val bmp: Bitmap = frame.bitmap
                if (!bmp.isRecycled) {
                    bmp.getPixels(buf, 0, frame.width, 0, 0, frame.width, frame.height)
                }
            },
            width = frame.width,
            height = frame.height,
            contentRoi = frame.contentRoi,
        ).result

    private fun refreshBubbleUi() {
        val snap = AutoPlaySession.ui.value
        val ctrl = AutoPlaySession.controller
        val modeLabel = when (ctrl.mode) {
            AutoPlayController.Mode.IDLE -> "vár"
            AutoPlayController.Mode.RUNNING -> "fut"
            AutoPlayController.Mode.PAUSED -> "szünet"
            AutoPlayController.Mode.STOPPED -> "stop"
        }
        statusView?.text = "$modeLabel · ${ctrl.moveCount} húzás"
        startBtn?.isEnabled = ctrl.mode != AutoPlayController.Mode.RUNNING &&
            ctrl.mode != AutoPlayController.Mode.STOPPED
        pauseBtn?.isEnabled = ctrl.mode == AutoPlayController.Mode.RUNNING
        // Keep status text in session for analyzer UI.
        AutoPlaySession.publish(
            statusText = "${snap.statusText} ($modeLabel)",
            bubbleVisible = bubbleView != null,
        )
    }

    companion object {
        /** Wait after TESZT ÉRINTÉS click so overlay MotionEvent ends before dispatchGesture. */
        const val TOUCH_TEST_CLICK_DELAY_MS = 400L

        const val ACTION_STOP_ALL = "com.match3vision.analyzer.overlay.STOP_ALL"
        const val ACTION_START_LOOP = "com.match3vision.analyzer.overlay.START_LOOP"
        const val ACTION_PAUSE_LOOP = "com.match3vision.analyzer.overlay.PAUSE_LOOP"
        /** Bubble FUT → analyzer Activity should moveTaskToBack / compact UI. */
        const val ACTION_MINIMIZE_ANALYZER = "com.match3vision.analyzer.overlay.MINIMIZE_ANALYZER"

        @Volatile
        private var instance: FloatingBubbleService? = null

        fun isRunning(): Boolean = instance != null

        fun start(context: Context) {
            context.startService(Intent(context, FloatingBubbleService::class.java))
        }

        fun stop(context: Context) {
            if (instance == null) return
            val i = Intent(context, FloatingBubbleService::class.java).setAction(ACTION_STOP_ALL)
            context.startService(i)
        }
    }
}
