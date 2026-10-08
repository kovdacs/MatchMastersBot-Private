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
import com.match3vision.analyzer.capture.AndroidScreenMetrics
import com.match3vision.analyzer.capture.CaptureBufferGate
import com.match3vision.analyzer.capture.CaptureFrame
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.capture.ScreenMetricsSource
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.CoordinateSelfCheck
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.BotLoopOutcome
import com.match3vision.analyzer.input.GestureFailSafe
import com.match3vision.analyzer.input.InputThresholds
import com.match3vision.analyzer.input.MatchMastersAccessibilityService
import com.match3vision.analyzer.input.DiagnosticBundle
import com.match3vision.analyzer.input.DiagnosticExport
import com.match3vision.analyzer.input.DiagnosticFrame
import com.match3vision.analyzer.input.DiagnosticHistoryStore
import com.match3vision.analyzer.input.DiagnosticLuminance
import com.match3vision.analyzer.input.DiagnosticShare
import com.match3vision.analyzer.input.VerifyTiming
import com.match3vision.analyzer.input.ProductionCycleContext
import com.match3vision.analyzer.input.ProductionLiveReaders
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
    private var diagHintView: TextView? = null
    private val hiddenWhileCapturing = ArrayList<View>()
    private var collapsedForCapture = false
    private var collapseWallMs = 0L
    private var savedGravity = Gravity.TOP or Gravity.END
    private var savedX = 0
    private var savedY = 0
    private var savedWidth = WindowManager.LayoutParams.WRAP_CONTENT
    private var savedHeight = WindowManager.LayoutParams.WRAP_CONTENT

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        installLiveReaders()
        DiagnosticHistoryStore.install(java.io.File(filesDir, "diagnostics"))
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
            ACTION_ARM_SINGLE_MOVE -> {
                armSingleMoveFromBubble()
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
        ProductionLiveReaders.reset()
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
        diagHintView = TextView(this).apply {
            text = "Diagnosztika: kisegítő maradhat KI"
            setTextColor(0xFFB0BEC5.toInt())
            textSize = 8f
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
        val oneMoveBtn = Button(this).apply {
            text = "EGY LÉPÉS"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { armSingleMoveFromBubble() }
        }
        val shareBtn = Button(this).apply {
            text = "DIAG MEGOSZT"
            textSize = 10f
            isAllCaps = false
            setOnClickListener { shareDiagnosticsFromBubble() }
        }
        val copyBtn = Button(this).apply {
            text = "DIAG MÁSOL"
            textSize = 10f
            isAllCaps = false
            setOnClickListener { copyDiagnosticsFromBubble() }
        }
        val clearDiagBtn = Button(this).apply {
            text = "DIAG TÖRLÉS"
            textSize = 10f
            isAllCaps = false
            setOnClickListener { clearDiagnosticsFromBubble() }
        }
        val stopBtn = Button(this).apply {
            text = "STOP"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { stopAllAndSelf() }
        }
        root.addView(title)
        root.addView(diagHintView)
        root.addView(statusView)
        root.addView(startBtn)
        root.addView(pauseBtn)
        root.addView(touchTestBtn)
        root.addView(oneMoveBtn)
        root.addView(shareBtn)
        root.addView(copyBtn)
        root.addView(clearDiagBtn)
        root.addView(stopBtn)
        hiddenWhileCapturing.clear()
        hiddenWhileCapturing.add(startBtn!!)
        hiddenWhileCapturing.add(pauseBtn!!)
        hiddenWhileCapturing.add(touchTestBtn!!)
        hiddenWhileCapturing.add(oneMoveBtn)
        hiddenWhileCapturing.add(shareBtn)
        hiddenWhileCapturing.add(copyBtn)
        hiddenWhileCapturing.add(clearDiagBtn)

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
            width = (200 * density).toInt()
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
        diagHintView = null
        hiddenWhileCapturing.clear()
        collapsedForCapture = false
        collapseWallMs = 0L
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
        collapseBubbleForCapture()
        refreshBubbleUi()
        // Prefer Match Masters visible: ask analyzer Activity to background itself.
        sendBroadcast(Intent(ACTION_MINIMIZE_ANALYZER).setPackage(packageName))
        ensureLoopRunning()
    }

    private fun armSingleMoveFromBubble() {
        val a11y = MatchMastersAccessibilityService.isConnected()
        val captureOk = CaptureService.managerOrNull() != null
        val overlayOk = android.provider.Settings.canDrawOverlays(this)
        val ok = AutoPlaySession.controller.armSingleMove(
            a11yConnected = a11y,
            captureReady = captureOk,
            overlayReady = overlayOk,
        )
        AutoPlaySession.publish(a11yReady = a11y, captureReady = captureOk, overlayReady = overlayOk)
        if (!ok) {
            val reason = AutoPlaySession.controller.lastReason
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            refreshBubbleUi()
            return
        }
        AutoPlaySession.syncFrameGateFromMode()
        AutoPlaySession.refreshFromController("EGY LÉPÉS ARMED")
        collapseBubbleForCapture()
        refreshBubbleUi()
        sendBroadcast(Intent(ACTION_MINIMIZE_ANALYZER).setPackage(packageName))
        ensureLoopRunning()
    }

    private fun shareDiagnosticsFromBubble() {
        try {
            val dir = java.io.File(filesDir, "diagnostics")
            DiagnosticHistoryStore.install(dir)
            DiagnosticShare.share(this, dir, DiagnosticHistoryStore.exportText())
        } catch (t: Throwable) {
            Timber.w(t, "diagnostic share failed")
            Toast.makeText(this, "Megosztás sikertelen: ${t.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyDiagnosticsFromBubble() {
        val text = DiagnosticHistoryStore.exportText()
        val ok = DiagnosticShare.copyToClipboard(this, text)
        Toast.makeText(
            this,
            if (ok) "Diagnosztika a vágólapon" else "Vágólap nem elérhető",
            Toast.LENGTH_LONG,
        ).show()
    }

    private fun clearDiagnosticsFromBubble() {
        DiagnosticHistoryStore.clear()
        DiagnosticHistoryStore.install(java.io.File(filesDir, "diagnostics"))
        Toast.makeText(this, "Első HOLD és előzmények törölve", Toast.LENGTH_LONG).show()
    }

    private fun pauseLoopFromBubble() {
        startGen++
        AutoPlaySession.controller.onBubblePause()
        AutoPlaySession.syncFrameGateFromMode()
        restoreExpandedBubble()
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
                        CoordinateSelfCheck.refuse("TESZT ÉRINTÉS threw before dispatch")
                        CoordinateSelfCheck.write(java.io.File(filesDir, "diagnostics"))
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
                    recordCoordinateSelfCheck(result)
                    Timber.i(
                        "TOUCH_TEST: result success=%s reason=%s hu=%s selfCheck=%s",
                        result.success,
                        result.reason,
                        result.huStatus,
                        CoordinateSelfCheck.statusLabel(),
                    )
                    for (line in AutoPlaySession.touchTest.logger().lines().takeLast(16)) {
                        Timber.i("TOUCH_TEST_LOG: %s", line)
                    }
                }
            }
        }, TOUCH_TEST_CLICK_DELAY_MS)
    }

    /**
     * Records the TESZT ÉRINTÉS point. A dispatched test with matching sizes and
     * no rotation/origin offset becomes RECORDED_UNPROVEN. That unlocks EGY LÉPÉS.
     * alignmentProven stays false. Insets are written into the record and not added
     * to the expected point.
     */
    private fun recordCoordinateSelfCheck(result: com.match3vision.analyzer.input.AutomaticTouchTest.Result) {
        if (!result.dispatchAttempted) {
            CoordinateSelfCheck.refuse("TESZT ÉRINTÉS did not dispatch: ${result.reason}")
            CoordinateSelfCheck.write(java.io.File(filesDir, "diagnostics"))
            return
        }
        val screen = ProductionLiveReaders.screenSource.measure()
        val frame = CaptureService.managerOrNull()?.latestFrame?.value
        var luma: Float? = null
        var frameW = 0
        var frameH = 0
        if (frame != null && frame.width > 0 && frame.height > 0 && !frame.bitmap.isRecycled) {
            frameW = frame.width
            frameH = frame.height
            val x = result.startX.toInt()
            val y = result.startY.toInt()
            if (x in 0 until frameW && y in 0 until frameH) {
                try {
                    val px = IntArray(1)
                    frame.bitmap.getPixels(px, 0, 1, x, y, 1, 1)
                    val p = px[0]
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    luma = 0.2126f * r + 0.7152f * g + 0.0722f * b
                } catch (t: Throwable) {
                    Timber.w(t, "self-check pixel read failed")
                    luma = null
                }
            }
        }
        var statusBar = 0
        var navBar = 0
        var cutoutTop = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val wm = windowManager ?: getSystemService(WINDOW_SERVICE) as? WindowManager
            val insets = wm?.currentWindowMetrics?.windowInsets
            if (insets != null) {
                statusBar = insets.getInsets(android.view.WindowInsets.Type.statusBars()).top
                navBar = insets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
                cutoutTop = insets.displayCutout?.safeInsetTop ?: 0
            }
        }
        CoordinateSelfCheck.record(
            expectedX = result.startX,
            expectedY = result.startY,
            screenWidth = result.screenWidthPx,
            screenHeight = result.screenHeightPx,
            frameWidth = frameW,
            frameHeight = frameH,
            rotation = screen.rotation,
            statusBarInsetPx = statusBar,
            navigationBarInsetPx = navBar,
            cutoutInsetPx = cutoutTop,
            observedLuma = luma,
        )
        CoordinateSelfCheck.write(java.io.File(filesDir, "diagnostics"))
    }

    /** Physical screen pixels for fixed-coordinate touch test (not Vision). */
    private fun installLiveReaders() {
        val metrics = AndroidScreenMetrics(this)
        ProductionLiveReaders.screenSource = ScreenMetricsSource { metrics.measure() }
        ProductionLiveReaders.readCaptureReady = {
            val manager = CaptureService.managerOrNull()
            manager != null && manager.isCapturing.value
        }
        ProductionLiveReaders.readStopped = {
            val mode = AutoPlaySession.controller.mode
            mode == AutoPlayController.Mode.STOPPED ||
                mode == AutoPlayController.Mode.PAUSED ||
                !AutoPlaySession.controller.isLoopActive()
        }
    }

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

    /**
     * Capture records the composed screen. Shrink to a top-end chip before
     * analysis so the panel is not a board column. [collapseWallMs] is set
     * only on the transition; frames already in hand stay ineligible until
     * [OverlayPlacement.MIN_POST_COLLAPSE_MS] has passed.
     */
    private fun collapseBubbleForCapture() {
        val v = bubbleView ?: return
        val p = layoutParams ?: return
        if (!collapsedForCapture) {
            savedGravity = p.gravity
            savedX = p.x
            savedY = p.y
            savedWidth = p.width
            savedHeight = p.height
            collapsedForCapture = true
            collapseWallMs = System.currentTimeMillis()
        }
        val density = resources.displayMetrics.density
        val d = if (density > 0f) density else 1f
        p.gravity = Gravity.TOP or Gravity.END
        p.x = (OverlayPlacement.COLLAPSED_END_MARGIN_DP * d).toInt()
        p.y = (OverlayPlacement.COLLAPSED_TOP_DP * d).toInt()
        p.width = (OverlayPlacement.COLLAPSED_WIDTH_DP * d).toInt().coerceAtLeast(1)
        p.height = (OverlayPlacement.COLLAPSED_HEIGHT_DP * d).toInt().coerceAtLeast(1)
        for (child in hiddenWhileCapturing) {
            child.visibility = View.GONE
        }
        diagHintView?.visibility = View.GONE
        try {
            windowManager?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            Timber.w(t, "collapse overlay failed")
        }
    }

    private fun restoreExpandedBubble() {
        if (!collapsedForCapture) return
        val v = bubbleView ?: return
        val p = layoutParams ?: return
        p.gravity = savedGravity
        p.x = savedX
        p.y = savedY
        p.width = savedWidth
        p.height = savedHeight
        for (child in hiddenWhileCapturing) {
            child.visibility = View.VISIBLE
        }
        diagHintView?.visibility = View.VISIBLE
        collapsedForCapture = false
        collapseWallMs = 0L
        try {
            windowManager?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            Timber.w(t, "restore overlay failed")
        }
    }

    private fun overlayRectOnScreen(): OverlayPlacement.Rect? {
        val v = bubbleView ?: return null
        if (v.width <= 0 || v.height <= 0) return null
        val loc = IntArray(2)
        return try {
            v.getLocationOnScreen(loc)
            OverlayPlacement.Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        } catch (t: Throwable) {
            Timber.w(t, "overlay location unknown")
            null
        }
    }

    /**
     * A vision PASS is still published as HOLD when the overlay covers the
     * board. Thresholds are not changed. The caller must not dispatch.
     */
    private fun holdIfOverlayBlocks(
        vision: com.match3vision.analyzer.vision.VisionResult,
        gate: OverlayBoardGate.Decision,
    ): com.match3vision.analyzer.vision.VisionResult {
        if (gate.allowAnalysis) return vision
        val prior = vision.validation
        val reason = if (prior is com.match3vision.analyzer.vision.ValidationResult.Hold) {
            "${prior.reason}; ${gate.reason}"
        } else {
            gate.reason
        }
        val diag = vision.diagnostics.toMutableMap()
        diag["overlayGate"] = gate.reason
        diag["validation"] = "HOLD"
        return vision.copy(
            validation = com.match3vision.analyzer.vision.ValidationResult.Hold(reason),
            diagnostics = diag,
        )
    }

    private fun publishOverlayBlocked(
        vision: com.match3vision.analyzer.vision.VisionResult,
        screen: com.match3vision.analyzer.capture.ScreenMeasurement,
        frame: CaptureFrame,
        pixels: IntArray?,
        pixelNote: String,
        reason: String,
        boardDet: String,
        boardRoiStr: String,
    ) {
        AutoPlaySession.updateDiagnostics(
            frameReceived = true,
            hasFrameFlag = true,
            visionText = reason,
            visionPassFlag = false,
            a11yConnected = MatchMastersAccessibilityService.isConnected(),
            phase = "TARTÁS",
            unknownCount = vision.unknownCount,
            gridConfidence = vision.gridConfidence,
            boardConfidence = vision.boardConfidence,
            boardDetection = boardDet,
            boardRoi = boardRoiStr,
            frameSequence = "seq=${frame.sequence}",
            frameAgeMs = frame.ageMs(),
            frameTimestampMs = frame.timestampMs,
            frameWidth = frame.width,
            frameHeight = frame.height,
            frameFreshness = RuntimeLabels.freshness(true, frame.ageMs()),
            captureStatus = "ON",
            heartbeatMs = System.currentTimeMillis(),
            cycleReason = reason,
            gestureStatus = "NOT CREATED",
            verifyStatus = VerificationPolicy.PENDING,
            moveText = "none",
            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
        )
        publishSafetyDiagnostics(
            vision = vision,
            screen = screen,
            frame = frame,
            pixels = pixels,
            pixelNote = pixelNote,
            moveText = "none",
            coordinateRefused = false,
            coordinateReason = "",
            dispatchStatus = "NOT STARTED",
            callbackOutcome = "not dispatched",
            verificationStatus = VerificationPolicy.PENDING,
            verificationReason = reason,
        )
        AutoPlaySession.refreshFromController(reason)
        refreshBubbleUi()
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
                    restoreExpandedBubble()
                    refreshBubbleUi()
                    delay(200L)
                    continue
                }
                collapseBubbleForCapture()
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
                    if (!OverlayPlacement.frameShowsCollapsedOverlay(
                            useFrame.timestampMs,
                            collapseWallMs,
                        )
                    ) {
                        AutoPlaySession.updateDiagnostics(
                            frameReceived = true,
                            hasFrameFlag = true,
                            visionText = "HOLD — overlay collapse not yet on screen",
                            visionPassFlag = false,
                            a11yConnected = MatchMastersAccessibilityService.isConnected(),
                            phase = "TARTÁS",
                            frameSequence = "seq=${useFrame.sequence}",
                            frameAgeMs = useFrame.ageMs(),
                            frameTimestampMs = useFrame.timestampMs,
                            frameWidth = useFrame.width,
                            frameHeight = useFrame.height,
                            captureStatus = "ON",
                            heartbeatMs = System.currentTimeMillis(),
                            cycleReason = "HOLD: waiting for collapsed overlay — no touch",
                            gestureStatus = "NOT CREATED",
                            verifyStatus = VerificationPolicy.PENDING,
                        )
                        AutoPlaySession.refreshFromController("overlay záródik")
                        refreshBubbleUi()
                        delay(150L)
                        continue
                    }
                    val analyzed = withContext(Dispatchers.Default) {
                        analyzeFrame(useFrame)
                    }
                    if (!analyzed.admitted || analyzed.vision == null) {
                        publishCaptureInvalid(useFrame, analyzed.pixelNote)
                        AutoPlaySession.updateDiagnostics(
                            frameReceived = true,
                            hasFrameFlag = false,
                            visionText = "HOLD — ${CaptureBufferGate.FAILURE_CLASS}",
                            visionPassFlag = false,
                            a11yConnected = MatchMastersAccessibilityService.isConnected(),
                            phase = "TARTÁS",
                            frameSequence = "seq=${useFrame.sequence}",
                            frameAgeMs = useFrame.ageMs(),
                            frameTimestampMs = useFrame.timestampMs,
                            frameWidth = useFrame.width,
                            frameHeight = useFrame.height,
                            captureStatus = "ON",
                            heartbeatMs = System.currentTimeMillis(),
                            cycleReason = analyzed.pixelNote,
                            gestureStatus = "NOT CREATED",
                            verifyStatus = VerificationPolicy.PENDING,
                        )
                        AutoPlaySession.refreshFromController(analyzed.pixelNote)
                        refreshBubbleUi()
                        delay(280L)
                        continue
                    }
                    val visionRaw = analyzed.vision
                    val screen = ProductionLiveReaders.screenSource.measure()
                    val (screenW, screenH) = screenSizePx()
                    val roi = visionRaw.grid.boardRoi
                    val overlayGate = OverlayBoardGate.evaluate(
                        overlay = overlayRectOnScreen(),
                        boardLeft = roi.left,
                        boardTop = roi.top,
                        boardRight = roi.right,
                        boardBottom = roi.bottom,
                        frameWidth = useFrame.width,
                        frameHeight = useFrame.height,
                        screenWidth = screenW,
                        screenHeight = screenH,
                        rotation = screen.rotation,
                    )
                    val vision = holdIfOverlayBlocks(visionRaw, overlayGate)
                    val boardRoiStr = vision.diagnostics["boardRoi"]
                        ?: "LTRB(${vision.grid.boardRoi.left},${vision.grid.boardRoi.top}," +
                        "${vision.grid.boardRoi.right},${vision.grid.boardRoi.bottom})"
                    val boardDet = vision.method.name + "/" + boardRoiStr
                    if (!overlayGate.allowAnalysis) {
                        publishOverlayBlocked(
                            vision = vision,
                            screen = screen,
                            frame = useFrame,
                            pixels = analyzed.pixels,
                            pixelNote = analyzed.pixelNote,
                            reason = overlayGate.reason,
                            boardDet = boardDet,
                            boardRoiStr = boardRoiStr,
                        )
                        delay(280L)
                        continue
                    }
                    val cycleContext = ProductionCycleContext.fromLoopObservation(
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                        captureManagerPresent = CaptureService.managerOrNull() != null &&
                            manager.isCapturing.value,
                        hasFrame = true,
                        frameAgeMs = useFrame.ageMs(),
                        frameSequenceDecision = seqDecision,
                        frameTimestampMs = useFrame.timestampMs,
                        frameWidth = useFrame.width,
                        frameHeight = useFrame.height,
                        capturedElapsedMs = useFrame.elapsedRealtimeMs,
                        screen = screen,
                        frameSequence = useFrame.sequence,
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
                    publishSafetyDiagnostics(
                        vision = vision,
                        screen = screen,
                        frame = useFrame,
                        pixels = analyzed.pixels,
                        pixelNote = analyzed.pixelNote,
                        moveText = cycle.moveLabel,
                        coordinateRefused = cycle.coordinateBlocked,
                        coordinateReason = if (cycle.coordinateBlocked) cycle.reason else "",
                        dispatchStatus = cycle.lastDispatch.name,
                        callbackOutcome = cycle.callbackOutcome,
                        verificationStatus = cycle.verifyStatus,
                        verificationReason = cycle.reason,
                    )
                    AutoPlaySession.refreshFromController()
                    refreshBubbleUi()
                    when (cycle.outcome) {
                        BotLoopOutcome.CONTINUE -> {
                            val executed = cycle.executed
                            if (executed is AutomaticInputEngine.ExecuteResult.Executed) {
                                seqGate.markGestureDispatched(frameId)
                                statusView?.text = "GESZTUS #${ctrl.moveCount} ${ctrl.singleMove.label()}"
                                // Sampled when dispatchGesture returns, before the 650 ms wait.
                                // This is receipt-side elapsedRealtime, not image content time.
                                val dispatchCompletedElapsedMs =
                                    com.match3vision.analyzer.input.FrameClock.tryElapsed()
                                val waitMs = cycle.animationWaitMs.coerceAtLeast(
                                    VerifyTiming.POST_DISPATCH_WAIT_MS,
                                )
                                check(VerifyTiming.POST_DISPATCH_WAIT_MS == InputThresholds.ANIMATION_WAIT_MS)
                                check(VerifyTiming.SWIPE_DURATION_MS == InputThresholds.SWIPE_DURATION_MS)
                                delay(waitMs)
                                var waited = 0L
                                var after = CaptureService.managerOrNull()?.latestFrame?.value
                                var afterDecision = seqGate.evaluate(after?.toSequenceId())
                                while (isActive && waited < VerifyTiming.NEW_FRAME_POLL_BUDGET_MS &&
                                    (after == null || !afterDecision.allow)
                                ) {
                                    delay(VerifyTiming.NEW_FRAME_POLL_STEP_MS)
                                    waited += VerifyTiming.NEW_FRAME_POLL_STEP_MS
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
                                    publishSafetyDiagnostics(
                                        vision = vision,
                                        screen = screen,
                                        frame = useFrame,
                                        pixels = analyzed.pixels,
                                        pixelNote = analyzed.pixelNote,
                                        moveText = executed.move.move.toString(),
                                        coordinateRefused = false,
                                        coordinateReason = "",
                                        dispatchStatus = StartupReadinessGate.LastDispatch.SUCCESS.name,
                                        callbackOutcome = cycle.callbackOutcome,
                                        verificationStatus = VerificationPolicy.FAILED,
                                        verificationReason = reason,
                                    )
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
                                val afterAnalyzed = withContext(Dispatchers.Default) {
                                    analyzeFrame(afterFrame)
                                }
                                if (!afterAnalyzed.admitted || afterAnalyzed.vision == null) {
                                    val reason = afterAnalyzed.pixelNote.ifBlank {
                                        "CAPTURE_INVALID — post-dispatch frame was not analyzed"
                                    }
                                    ctrl.onFailsafePause(reason)
                                    publishCaptureInvalid(afterFrame, reason)
                                    if (ctrl.runStyle == AutoPlayController.RunStyle.SINGLE_MOVE) {
                                        ctrl.finishSingleMoveAfterExport()
                                    }
                                    AutoPlaySession.refreshFromController()
                                    refreshBubbleUi()
                                    continue
                                }
                                val afterVision = afterAnalyzed.vision
                                AutoPlaySession.updateDiagnostics(
                                    phase = "ELLENŐRZÉS",
                                    verifyStatus = VerificationPolicy.PENDING,
                                    gestureStatus = "CREATED",
                                    lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
                                )
                                val nowElapsed =
                                    com.match3vision.analyzer.input.FrameClock.tryElapsed()
                                val observation = VerifyObservation.derive(
                                    preDispatchSequence = useFrame.sequence,
                                    afterSequence = afterFrame.sequence,
                                    afterElapsedMs = afterFrame.elapsedRealtimeMs,
                                    dispatchCompletedElapsedMs = dispatchCompletedElapsedMs,
                                    nowElapsedMs = nowElapsed,
                                    gestureEligible = executed.verificationEligible,
                                )
                                val fb = withContext(Dispatchers.Default) {
                                    ctrl.completeFeedback(
                                        beforeBoardHash = executed.beforeBoardHash,
                                        afterVision = afterVision,
                                        verify = observation,
                                        beforeVision = vision,
                                        attemptedMove = executed.move.move,
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
                                    frameFreshness = RuntimeLabels.freshness(true, afterAge),
                                    hasFrameFlag = true,
                                    visionPassFlag = afterVision.validation.isPass,
                                )
                                publishSafetyDiagnostics(
                                    vision = afterVision,
                                    screen = ProductionLiveReaders.screenSource.measure(),
                                    frame = afterFrame,
                                    pixels = afterAnalyzed.pixels,
                                    pixelNote = afterAnalyzed.pixelNote,
                                    moveText = executed.move.move.toString(),
                                    coordinateRefused = false,
                                    coordinateReason = "",
                                    dispatchStatus = StartupReadinessGate.LastDispatch.SUCCESS.name,
                                    callbackOutcome = cycle.callbackOutcome,
                                    verificationStatus = verifyLabel,
                                    verificationReason = fb?.reason ?: "",
                                )
                                if (ctrl.runStyle == AutoPlayController.RunStyle.SINGLE_MOVE) {
                                    ctrl.finishSingleMoveAfterExport()
                                }
                                AutoPlaySession.refreshFromController()
                                refreshBubbleUi()
                                if (fb?.outcome == BotLoopOutcome.STOP) {
                                    delay(300L)
                                } else {
                                    // Next iteration waits for a NEW frame via seqGate.
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
                    val reason = try {
                        "HIBA: ${t.message}"
                    } catch (_: Throwable) {
                        "HIBA"
                    }
                    try {
                        ctrl.onFailsafePause(reason)
                    } catch (pauseError: Throwable) {
                        Timber.e(pauseError, "fail-safe pause itself failed")
                    }
                    try {
                        AutoPlaySession.updateDiagnostics(phase = "HIBA", stopReason = reason)
                        AutoPlaySession.refreshFromController()
                        refreshBubbleUi()
                    } catch (uiError: Throwable) {
                        Timber.e(uiError, "fail-safe UI update failed")
                    }
                    delay(500L)
                }
            }
        }
    }

    private fun publishSafetyDiagnostics(
        vision: com.match3vision.analyzer.vision.VisionResult,
        screen: com.match3vision.analyzer.capture.ScreenMeasurement,
        frame: CaptureFrame,
        pixels: IntArray?,
        pixelNote: String,
        moveText: String,
        coordinateRefused: Boolean,
        coordinateReason: String,
        dispatchStatus: String,
        callbackOutcome: String,
        verificationStatus: String,
        verificationReason: String,
    ) {
        val luma = DiagnosticLuminance.measure(pixels)
        val roi = vision.grid.boardRoi
        val frameExport = DiagnosticFrame.render(
            pixels = pixels,
            width = frame.width,
            height = frame.height,
            roiLeft = roi.left,
            roiTop = roi.top,
            roiRight = roi.right,
            roiBottom = roi.bottom,
            xBoundaries = vision.grid.xBoundaries,
            yBoundaries = vision.grid.yBoundaries,
            refusal = if (pixels == null) pixelNote.ifBlank {
                "NOT EXPORTED — frame pixels were not available"
            } else {
                null
            },
        )
        val bundle = DiagnosticBundle.fromObservation(
            appVersion = com.match3vision.analyzer.BuildConfig.VERSION_NAME,
            versionCode = com.match3vision.analyzer.BuildConfig.VERSION_CODE,
            sourceCommit = com.match3vision.analyzer.BuildConfig.GIT_COMMIT,
            diagnosticTimestampMs = System.currentTimeMillis(),
            vision = vision,
            screen = screen,
            frameWidth = frame.width,
            frameHeight = frame.height,
            frameSequence = frame.sequence,
            captureTimestampMs = frame.timestampMs,
            frameAgeMs = frame.ageMs(),
            frameElapsedMs = frame.elapsedRealtimeMs,
            cadence = CaptureService.managerOrNull()?.cadence,
            accessibilityConnected = MatchMastersAccessibilityService.isConnected(),
            gestureCapability = MatchMastersAccessibilityService.diagnoseConnected(),
            captureOn = CaptureService.managerOrNull()?.isCapturing?.value == true,
            hasFrame = true,
            moveAnalysis = moveText,
            selectedMove = moveText,
            coordinateReason = coordinateReason.ifBlank {
                "coordinate origin alignment UNPROVEN"
            },
            coordinateRefused = coordinateRefused,
            dispatchStatus = dispatchStatus,
            callbackOutcome = callbackOutcome,
            verificationStatus = verificationStatus,
            verificationReason = verificationReason,
            simulated = false,
            meanLuminance = if (pixels == null) "not measured" else "%.2f".format(luma.mean),
            blackFrame = luma.text,
            frameExportStatus = frameExport.status,
            frameExportReason = frameExport.reason,
        )
        DiagnosticExport.publish(bundle)
        try {
            DiagnosticHistoryStore.record(bundle, frameExport)
        } catch (t: Throwable) {
            Timber.w(t, "diagnostic history write failed")
        }
    }

    private class AnalyzedFrame(
        val vision: com.match3vision.analyzer.vision.VisionResult?,
        val pixels: IntArray?,
        val pixelNote: String,
        val admitted: Boolean,
    )

    /**
     * Copy pixels off the live capture bitmap before analysis so a concurrent
     * recycle in ScreenCaptureManager cannot ANR / crash mid-getPixels.
     *
     * A failed copy is not replaced with zeros. [CaptureBufferGate] does not
     * call VisionPipeline, and the loop does not call runCycleIfActive.
     */
    private fun analyzeFrame(frame: CaptureFrame): AnalyzedFrame {
        val w = frame.width
        val h = frame.height
        val bmp: Bitmap = frame.bitmap
        var copied: IntArray? = null
        var failure = "CAPTURE_INVALID — frame pixels were not available"
        if (w <= 0 || h <= 0) {
            failure = "CAPTURE_INVALID — missing or invalid frame size ${w}x$h"
        } else if (bmp.isRecycled) {
            failure = "CAPTURE_INVALID — bitmap recycled before copy"
        } else {
            val buf = IntArray(w * h)
            try {
                bmp.getPixels(buf, 0, w, 0, 0, w, h)
                copied = buf
            } catch (t: Throwable) {
                failure = "CAPTURE_INVALID — getPixels failed: ${t.message}"
                Timber.w(t, "analyzeFrame: getPixels failed (recycled?)")
                copied = null
            }
        }
        val pixels = copied
        val admission = CaptureBufferGate.analyzeIfAdmitted(
            copySucceeded = pixels != null,
            width = w,
            height = h,
            bufferLength = pixels?.size ?: 0,
        ) {
            AutoPlaySession.frameAnalyzer.analyzePixels(
                pixels = pixels!!,
                width = w,
                height = h,
                contentRoi = frame.contentRoi,
            ).result
        }
        return if (!admission.admitted || admission.value == null) {
            AnalyzedFrame(
                vision = null,
                pixels = null,
                pixelNote = admission.reason.ifBlank { failure },
                admitted = false,
            )
        } else {
            AnalyzedFrame(
                vision = admission.value,
                pixels = pixels,
                pixelNote = "",
                admitted = true,
            )
        }
    }

    private fun publishCaptureInvalid(frame: CaptureFrame, reason: String) {
        val screen = ProductionLiveReaders.screenSource.measure()
        val note = reason.ifBlank { "CAPTURE_INVALID — pixel copy failed" }
        val frameExport = DiagnosticFrame.render(
            pixels = null,
            width = frame.width,
            height = frame.height,
            roiLeft = 0,
            roiTop = 0,
            roiRight = 0,
            roiBottom = 0,
            xBoundaries = null,
            yBoundaries = null,
            refusal = note,
        )
        val bundle = DiagnosticBundle.fromObservation(
            appVersion = com.match3vision.analyzer.BuildConfig.VERSION_NAME,
            versionCode = com.match3vision.analyzer.BuildConfig.VERSION_CODE,
            sourceCommit = com.match3vision.analyzer.BuildConfig.GIT_COMMIT,
            diagnosticTimestampMs = System.currentTimeMillis(),
            vision = null,
            screen = screen,
            frameWidth = frame.width,
            frameHeight = frame.height,
            frameSequence = frame.sequence,
            captureTimestampMs = frame.timestampMs,
            frameAgeMs = frame.ageMs(),
            frameElapsedMs = frame.elapsedRealtimeMs,
            cadence = CaptureService.managerOrNull()?.cadence,
            accessibilityConnected = MatchMastersAccessibilityService.isConnected(),
            gestureCapability = MatchMastersAccessibilityService.diagnoseConnected(),
            captureOn = true,
            hasFrame = false,
            moveAnalysis = "not run — CAPTURE_INVALID",
            selectedMove = "none",
            coordinateReason = "coordinate origin alignment UNPROVEN",
            coordinateRefused = false,
            dispatchStatus = "NOT STARTED",
            callbackOutcome = "not dispatched",
            verificationStatus = VerificationPolicy.PENDING,
            verificationReason = note,
            simulated = false,
            meanLuminance = "not measured",
            blackFrame = "not measured — CAPTURE_INVALID (failed copy is not a black frame)",
            frameExportStatus = frameExport.status,
            frameExportReason = frameExport.reason,
            captureInvalidReason = note,
        )
        DiagnosticExport.publish(bundle)
        try {
            DiagnosticHistoryStore.record(bundle, frameExport)
        } catch (t: Throwable) {
            Timber.w(t, "diagnostic history write failed")
        }
    }

    private fun refreshBubbleUi() {
        val snap = AutoPlaySession.ui.value
        val ctrl = AutoPlaySession.controller
        val diag = snap.diagnostics
        // Compact HU status — full P0 fields + FIRST BLOCK (no silent freeze).
        statusView?.text = diag.bubbleLines(compact = true) +
            "\nEGY LÉPÉS: ${ctrl.singleMove.label()}"
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
        const val ACTION_ARM_SINGLE_MOVE = "com.match3vision.analyzer.overlay.ARM_SINGLE_MOVE"
        const val ACTION_PAUSE_LOOP = "com.match3vision.analyzer.overlay.PAUSE_LOOP"

        fun requestArmSingleMove(context: Context) {
            val i = Intent(context, FloatingBubbleService::class.java)
                .setAction(ACTION_ARM_SINGLE_MOVE)
            context.startService(i)
        }
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
