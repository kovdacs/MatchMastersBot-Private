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
import com.match3vision.analyzer.input.GestureFailSafe
import com.match3vision.analyzer.input.InputThresholds
import com.match3vision.analyzer.input.MatchMastersAccessibilityService
import com.match3vision.analyzer.input.ProductionCycleContext
import com.match3vision.analyzer.input.RuntimeLabels
import com.match3vision.analyzer.input.StartupReadinessGate
import com.match3vision.analyzer.input.VerificationPolicy
import com.match3vision.analyzer.input.VerifyObservation
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
 * Main-screen INDÍTÁS can arm the loop via [ACTION_START_LOOP] when a11y is
 * runtime-connected. Bubble INDÍTÁS remains for resume after SZÜNET.
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
            textSize = 8.5f
            maxLines = 32
            setLineSpacing(0f, 1.05f)
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
            width = (168 * density).toInt()
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

    private var startGen: Int = 0

    private fun startLoopFromBubble() {
        val gen = ++startGen
        startLoopFromBubbleAttempt(attempt = 0, gen = gen)
    }

    /**
     * CaptureService is started asynchronously. A single immediate check often
     * sees a null manager and leaves the loop IDLE after the user already
     * pressed INDÍTÁS — on device that looks like "the app does nothing".
     * Retry briefly, and keep the first block on CAPTURE: OFF while waiting.
     */
    private fun startLoopFromBubbleAttempt(attempt: Int, gen: Int) {
        if (gen != startGen) return
        val a11y = MatchMastersAccessibilityService.isConnected()
        val captureOk = CaptureService.managerOrNull() != null
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        AutoPlaySession.publish(a11yReady = a11y, captureReady = captureOk, overlayReady = overlayOk)
        if (!captureOk && a11y && overlayOk && attempt < CAPTURE_START_RETRIES) {
            val waiting = "CAPTURE: OFF — CaptureService még nem él"
            AutoPlaySession.updateDiagnostics(
                frameReceived = false,
                a11yConnected = a11y,
                captureStatus = "OFF",
                phase = "RÖGZÍTÉS",
                stopReason = waiting,
                cycleReason = waiting,
                hasFrameFlag = false,
            )
            refreshBubbleUi()
            Handler(Looper.getMainLooper()).postDelayed(
                { startLoopFromBubbleAttempt(attempt + 1, gen) },
                CAPTURE_START_RETRY_MS,
            )
            return
        }
        val ok = AutoPlaySession.controller.onBubbleStart(
            a11yConnected = a11y,
            captureReady = captureOk,
            overlayReady = overlayOk,
        )
        if (!ok) {
            val reason = AutoPlaySession.controller.lastReason
            AutoPlaySession.publish(statusText = reason, a11yReady = a11y)
            AutoPlaySession.updateDiagnostics(
                a11yConnected = a11y,
                stopReason = reason,
            )
            refreshBubbleUi()
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            Timber.w("startLoop blocked: %s", reason)
            return
        }
        AutoPlaySession.syncFrameGateFromMode()
        AutoPlaySession.refreshFromController("fut")
        AutoPlaySession.updateDiagnostics(a11yConnected = a11y, clearStopReason = true)
        refreshBubbleUi()
        // Prefer Match Masters visible: ask analyzer Activity to background itself.
        sendBroadcast(Intent(ACTION_MINIMIZE_ANALYZER).setPackage(packageName))
        ensureLoopRunning()
    }

    private fun pauseLoopFromBubble() {
        startGen++
        AutoPlaySession.controller.onBubblePause()
        AutoPlaySession.syncFrameGateFromMode()
        AutoPlaySession.refreshFromController("szünet")
        refreshBubbleUi()
    }

    private fun stopAllAndSelf() {
        startGen++
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
        // Recover from cancelled/completed job so RUNNING never silent-freezes.
        if (loopJob != null && loopJob?.isActive != true) {
            Timber.w("auto-play loop job inactive — restarting (no silent freeze)")
        }
        loopJob = scope.launch {
            Timber.i("auto-play continuous cycle STARTED")
            while (isActive) {
                val ctrl = AutoPlaySession.controller
                if (ctrl.mode == AutoPlayController.Mode.STOPPED) break
                if (!ctrl.isLoopActive()) {
                    refreshBubbleUi()
                    delay(200L)
                    continue
                }
                // P1: a11y connected-only INPUT READY — mid-run disconnect → PAUSE (clear).
                val a11yLive = MatchMastersAccessibilityService.isConnected()
                if (!a11yLive) {
                    ctrl.onFailsafePause("ACCESSIBILITY: DISCONNECTED (mid-run)")
                    AutoPlaySession.publish(a11yReady = false)
                    AutoPlaySession.updateDiagnostics(
                        a11yConnected = false,
                        phase = "SZÜNET",
                        stopReason = "ACCESSIBILITY: DISCONNECTED (mid-run)",
                        heartbeatMs = System.currentTimeMillis(),
                    )
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    delay(400L)
                    continue
                }
                val manager = CaptureService.managerOrNull()
                if (manager == null) {
                    val reason = "CAPTURE: OFF (CaptureService stopped mid-run)"
                    ctrl.onCaptureLost(reason)
                    AutoPlaySession.publish(captureReady = false, a11yReady = a11yLive)
                    AutoPlaySession.updateDiagnostics(
                        frameReceived = false,
                        hasFrameFlag = false,
                        a11yConnected = a11yLive,
                        phase = "SZÜNET",
                        captureStatus = "OFF",
                        stopReason = reason,
                        cycleReason = reason,
                        heartbeatMs = System.currentTimeMillis(),
                    )
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    delay(400L)
                    continue
                }
                val frame = manager.latestFrame.value
                val capturing = manager.isCapturing.value
                val seqGate = AutoPlaySession.frameSequenceGate
                if (frame == null) {
                    val miss = seqGate.evaluate(null)
                    AutoPlaySession.publish(captureReady = capturing)
                    AutoPlaySession.updateDiagnostics(
                        frameReceived = false,
                        hasFrameFlag = false,
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                        phase = "RÖGZÍTÉS",
                        captureStatus = if (capturing) "ON (no frame)" else "OFF",
                        frameSequence = seqGate.statusText(),
                        frameFreshness = "NONE",
                        gestureStatus = "NOT CREATED",
                    )
                    if (!miss.allow) {
                        ctrl.onFailsafePause(miss.reason)
                    }
                    AutoPlaySession.refreshFromController(
                        if (capturing) "vár képkockára…" else "CAPTURE: OFF",
                    )
                    refreshBubbleUi()
                    delay(150L)
                    continue
                }
                if (!capturing) {
                    val reason = "CAPTURE: OFF (projection stopped mid-run)"
                    ctrl.onCaptureLost(reason)
                    AutoPlaySession.publish(captureReady = false)
                    AutoPlaySession.updateDiagnostics(
                        frameReceived = true,
                        hasFrameFlag = true,
                        a11yConnected = a11yLive,
                        captureStatus = "OFF",
                        phase = "SZÜNET",
                        stopReason = reason,
                        cycleReason = reason,
                        frameTimestampMs = frame.timestampMs,
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        frameAgeMs = frame.ageMs(),
                        frameFreshness = RuntimeLabels.freshness(true, frame.ageMs()),
                    )
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    delay(400L)
                    continue
                }
                val frameId = frame.toSequenceId()
                val seqDecision = seqGate.evaluate(frameId)
                if (!seqDecision.allow) {
                    AutoPlaySession.updateDiagnostics(
                        frameReceived = true,
                        hasFrameFlag = true,
                        visionText = "HOLD — ${seqDecision.reason}",
                        visionPassFlag = false,
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                        phase = "TARTÁS",
                        frameSequence = "${seqDecision.verdict}",
                        frameAgeMs = frame.ageMs(),
                        frameTimestampMs = frame.timestampMs,
                        frameWidth = frame.width,
                        frameHeight = frame.height,
                        frameFreshness = RuntimeLabels.freshness(true, frame.ageMs()),
                        captureStatus = "ON",
                        frameSequenceAllow = false,
                        heartbeatMs = System.currentTimeMillis(),
                        cycleReason = seqDecision.reason,
                        gestureStatus = "NOT CREATED",
                        verifyStatus = VerificationPolicy.PENDING,
                    )
                    AutoPlaySession.refreshFromController(seqDecision.reason)
                    refreshBubbleUi()
                    delay(200L)
                    continue
                }
                val useFrame = frame
                try {
                    val vision = withContext(Dispatchers.Default) {
                        analyzeFrame(useFrame)
                    }
                    val boardRoiStr = vision.diagnostics["boardRoi"]
                        ?: "LTRB(${vision.grid.boardRoi.left},${vision.grid.boardRoi.top}," +
                        "${vision.grid.boardRoi.right},${vision.grid.boardRoi.bottom})"
                    val boardDet = vision.method.name + "/" + boardRoiStr
                    val cycleContext = ProductionCycleContext.fromLoopObservation(
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                        captureManagerPresent = CaptureService.managerOrNull() != null,
                        hasFrame = true,
                        frameAgeMs = useFrame.ageMs(),
                        frameSequenceDecision = seqDecision,
                        frameTimestampMs = useFrame.timestampMs,
                        frameWidth = useFrame.width,
                        frameHeight = useFrame.height,
                        capturedElapsedMs = useFrame.elapsedRealtimeMs,
                    )
                    // Overlay must not cancel the injected gesture (same fix as TESZT ÉRINTÉS).
                    setBubbleTouchable(false)
                    val cycle = try {
                        withContext(Dispatchers.Default) {
                            ctrl.runCycleIfActive(vision, cycleContext)
                        }
                    } finally {
                        setBubbleTouchable(true)
                    }
                    if (cycle == null) {
                        AutoPlaySession.updateDiagnostics(
                            frameReceived = true,
                            a11yConnected = MatchMastersAccessibilityService.isConnected(),
                            unknownCount = vision.unknownCount,
                            gridConfidence = vision.gridConfidence,
                            boardConfidence = vision.boardConfidence,
                            boardDetection = boardDet,
                            frameSequence = "seq=${frame.sequence}",
                            frameAgeMs = frame.ageMs(),
                            captureStatus = "ON",
                        )
                        refreshBubbleUi()
                        delay(120L)
                        continue
                    }
                    val phase = com.match3vision.analyzer.input.LoopPhaseLabels.fromCycle(
                        mode = ctrl.mode,
                        visionGate = cycle.visionGate,
                        outcome = cycle.outcome,
                        inputReady = cycle.inputReady,
                        dispatched = cycle.executed is AutomaticInputEngine.ExecuteResult.Executed,
                    ).labelHu
                    AutoPlaySession.updateDiagnostics(
                        frameReceived = true,
                        visionText = cycle.visionGate,
                        moveText = cycle.moveLabel,
                        lastDispatch = cycle.lastDispatch,
                        stopReason = if (cycle.outcome == BotLoopOutcome.STOP) cycle.reason else null,
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                        phase = phase,
                        unknownCount = vision.unknownCount,
                        gridConfidence = vision.gridConfidence,
                        boardConfidence = vision.boardConfidence,
                        boardDetection = boardDet,
                        boardRoi = boardRoiStr,
                        frameSequence = "seq=${frame.sequence}/${seqDecision.verdict}",
                        frameAgeMs = frame.ageMs(),
                        captureStatus = "ON",
                        frameSequenceAllow = seqDecision.allow,
                        heartbeatMs = System.currentTimeMillis(),
                        cycleReason = cycle.reason,
                        gestureStatus = cycle.gestureStatus,
                        gestureAttemptFailed = cycle.gestureAttemptFailed,
                        coordinateBlocked = cycle.coordinateBlocked,
                        frameTimestampMs = useFrame.timestampMs,
                        frameWidth = useFrame.width,
                        frameHeight = useFrame.height,
                        frameFreshness = RuntimeLabels.freshness(true, useFrame.ageMs()),
                        moveCandidates = cycle.moveCandidates,
                        inputBlockReason = cycle.inputBlockReason ?: "",
                        verifyStatus = cycle.verifyStatus,
                        visionPassFlag = cycle.visionGate.contains("PASS", ignoreCase = true) &&
                            !cycle.visionGate.contains("HOLD", ignoreCase = true),
                        hasFrameFlag = true,
                        simulated = false,
                    )
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    when (cycle.outcome) {
                        BotLoopOutcome.CONTINUE -> {
                            val executed = cycle.executed
                            if (executed is AutomaticInputEngine.ExecuteResult.Executed) {
                                seqGate.markGestureDispatched(frameId)
                                val dispatchCompletedAtMs = System.currentTimeMillis()
                                val dispatchCompletedElapsedMs =
                                    com.match3vision.analyzer.input.FrameClock.tryElapsed()
                                statusView?.text = "GESZTUS #${ctrl.moveCount}"
                                val waitMs = cycle.animationWaitMs.coerceAtLeast(
                                    InputThresholds.ANIMATION_WAIT_MS,
                                )
                                delay(waitMs)
                                var waited = 0L
                                var after = CaptureService.managerOrNull()?.latestFrame?.value
                                var afterDecision = seqGate.evaluate(after?.toSequenceId())
                                while (isActive && waited < 2_500L &&
                                    (after == null || !afterDecision.allow)
                                ) {
                                    delay(100L)
                                    waited += 100L
                                    after = CaptureService.managerOrNull()?.latestFrame?.value
                                    afterDecision = seqGate.evaluate(after?.toSequenceId())
                                }
                                val afterFrame = after
                                val afterAge = afterFrame?.ageMs() ?: -1L
                                val afterFresh = afterFrame != null &&
                                    afterAge <= GestureFailSafe.MAX_FRAME_AGE_MS
                                if (afterFrame == null || !afterDecision.allow || !afterFresh) {
                                    val reason = when {
                                        afterFrame == null ->
                                            afterDecision.reason.ifBlank { "nincs húzás utáni ÚJ képkocka" }
                                        !afterDecision.allow -> afterDecision.reason
                                        else ->
                                            "VERIFY FAILED — stale frame age=${afterAge}ms (not used)"
                                    }
                                    ctrl.onFailsafePause(reason)
                                    AutoPlaySession.updateDiagnostics(
                                        phase = "ELLENŐRZÉS",
                                        frameSequence = afterDecision.verdict.name,
                                        captureStatus = if (afterFrame == null) "MISSING" else "ON",
                                        verifyStatus = VerificationPolicy.FAILED,
                                        lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                                        gestureStatus = "CREATED",
                                        stopReason = reason,
                                        cycleReason = reason,
                                        frameAgeMs = afterAge,
                                        frameFreshness = if (afterFrame == null) "NONE" else "STALE",
                                        frameTimestampMs = afterFrame?.timestampMs ?: -1L,
                                        frameWidth = afterFrame?.width ?: 0,
                                        frameHeight = afterFrame?.height ?: 0,
                                    )
                                    AutoPlaySession.refreshFromController()
                                    refreshBubbleUi()
                                    continue
                                }
                                val afterVision = withContext(Dispatchers.Default) {
                                    analyzeFrame(afterFrame)
                                }
                                AutoPlaySession.updateDiagnostics(
                                    phase = "ELLENŐRZÉS",
                                    verifyStatus = VerificationPolicy.PENDING,
                                    gestureStatus = "CREATED",
                                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                                )
                                val fb = withContext(Dispatchers.Default) {
                                    ctrl.completeFeedback(
                                        executed.beforeBoardHash,
                                        afterVision,
                                        VerifyObservation(
                                            newFrameAccepted = true,
                                            frameFresh = true,
                                            frameTimestampMs = afterFrame.timestampMs,
                                            dispatchCompletedAtMs = dispatchCompletedAtMs,
                                            frameElapsedMs = afterFrame.elapsedRealtimeMs,
                                            dispatchCompletedElapsedMs = dispatchCompletedElapsedMs,
                                        ),
                                    )
                                }
                                // Policy label only — CONTINUE is not itself VERIFY SUCCESS.
                                val verifyLabel = fb?.verifyStatus ?: VerificationPolicy.FAILED
                                AutoPlaySession.updateDiagnostics(
                                    phase = if (verifyLabel == VerificationPolicy.SUCCESS) {
                                        "LÁTÁS OK"
                                    } else {
                                        "ELLENŐRZÉS"
                                    },
                                    verifyStatus = verifyLabel,
                                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                                    gestureStatus = "CREATED",
                                    stopReason = if (verifyLabel == VerificationPolicy.FAILED) {
                                        fb?.reason
                                    } else {
                                        null
                                    },
                                    heartbeatMs = System.currentTimeMillis(),
                                    cycleReason = fb?.reason,
                                    unknownCount = afterVision.unknownCount,
                                    gridConfidence = afterVision.gridConfidence,
                                    boardConfidence = afterVision.boardConfidence,
                                    boardRoi = afterVision.diagnostics["boardRoi"] ?: boardRoiStr,
                                    frameTimestampMs = afterFrame.timestampMs,
                                    frameWidth = afterFrame.width,
                                    frameHeight = afterFrame.height,
                                    frameAgeMs = afterAge,
                                    frameFreshness = "FRESH",
                                    hasFrameFlag = true,
                                    visionPassFlag = afterVision.validation.isPass,
                                )
                                AutoPlaySession.refreshFromController()
                                refreshBubbleUi()
                                if (fb?.outcome == BotLoopOutcome.STOP) {
                                    delay(300L)
                                } else {
                                    // SUCCESS → next iteration waits for NEW frame via seqGate.
                                    delay(80L)
                                }
                            } else {
                                delay(120L)
                            }
                        }
                        BotLoopOutcome.HOLD -> {
                            // Soft HOLD (gates / board not ready): retry next frame.
                            // Do not imply useful play — phase already TARTÁS.
                            delay(280L)
                        }
                        BotLoopOutcome.STOP -> {
                            delay(400L)
                        }
                    }
                } catch (t: Throwable) {
                    Timber.e(t, "auto-play loop error")
                    ctrl.onFailsafePause("HIBA: ${t.message}")
                    AutoPlaySession.updateDiagnostics(phase = "HIBA")
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    delay(500L)
                }
            }
        }
    }

    /**
     * Copy pixels off the live capture bitmap before analysis so a concurrent
     * recycle in ScreenCaptureManager cannot ANR / crash mid-getPixels.
     */
    private fun analyzeFrame(frame: CaptureFrame): com.match3vision.analyzer.vision.VisionResult {
        val w = frame.width
        val h = frame.height
        val buf = IntArray(w * h)
        val bmp: Bitmap = frame.bitmap
        if (!bmp.isRecycled) {
            try {
                bmp.getPixels(buf, 0, w, 0, 0, w, h)
            } catch (t: Throwable) {
                Timber.w(t, "analyzeFrame: getPixels failed (recycled?)")
            }
        }
        return AutoPlaySession.frameAnalyzer.analyzePixels(
            pixels = buf,
            width = w,
            height = h,
            contentRoi = frame.contentRoi,
        ).result
    }

    private fun refreshBubbleUi() {
        val snap = AutoPlaySession.ui.value
        val ctrl = AutoPlaySession.controller
        val diag = snap.diagnostics
        // Compact HU status — full P0 fields + FIRST BLOCK (no silent freeze).
        statusView?.text = diag.bubbleLines(compact = true)
        startBtn?.isEnabled = ctrl.mode != AutoPlayController.Mode.RUNNING &&
            ctrl.mode != AutoPlayController.Mode.STOPPED
        pauseBtn?.isEnabled = ctrl.mode == AutoPlayController.Mode.RUNNING
        AutoPlaySession.publish(bubbleVisible = bubbleView != null)
    }

    companion object {
        /** Wait after TESZT ÉRINTÉS click so overlay MotionEvent ends before dispatchGesture. */
        const val TOUCH_TEST_CLICK_DELAY_MS = 400L

        /** How long INDÍTÁS waits for CaptureService.onCreate before reporting CAPTURE OFF. */
        const val CAPTURE_START_RETRIES = 8
        const val CAPTURE_START_RETRY_MS = 250L

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

        /** Main-screen INDÍTÁS → arm RUNNING + ensureLoopRunning (no second bubble tap). */
        fun requestStartLoop(context: Context) {
            val i = Intent(context, FloatingBubbleService::class.java).setAction(ACTION_START_LOOP)
            context.startService(i)
        }

        fun stop(context: Context) {
            if (instance == null) return
            val i = Intent(context, FloatingBubbleService::class.java).setAction(ACTION_STOP_ALL)
            context.startService(i)
        }
    }
}
