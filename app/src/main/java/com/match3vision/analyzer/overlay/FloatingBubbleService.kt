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
import android.view.InputDevice
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
import com.match3vision.analyzer.board.Board
import com.match3vision.analyzer.hud.BoosterControl
import com.match3vision.analyzer.hud.HudObservation
import com.match3vision.analyzer.hud.HudPulse
import com.match3vision.analyzer.hud.HudReader
import com.match3vision.analyzer.hud.HudText
import com.match3vision.analyzer.hud.SoloBooster
import com.match3vision.analyzer.moves.PlayMoveRanker
import com.match3vision.analyzer.input.AutoCalibration
import com.match3vision.analyzer.input.AccessibilityGestureExecutor
import com.match3vision.analyzer.input.GestureSpec
import com.match3vision.analyzer.input.InputDispatchResult
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.AutoPlayTrace
import com.match3vision.analyzer.input.BoardStability
import com.match3vision.analyzer.input.DispatchPermit
import com.match3vision.analyzer.input.DispatchRecheck
import com.match3vision.analyzer.input.FiveMoveSession
import com.match3vision.analyzer.input.FrameClock
import com.match3vision.analyzer.input.FreshFrameDispatch
import com.match3vision.analyzer.input.OverlayOutsideTouch
import com.match3vision.analyzer.input.PlayGate
import com.match3vision.analyzer.input.CalibrationTarget
import com.match3vision.analyzer.input.CalibrationTouch
import com.match3vision.analyzer.input.CalibrationWindowPlan
import com.match3vision.analyzer.input.FiveMoveArm
import com.match3vision.analyzer.input.FiveMoveSeek
import com.match3vision.analyzer.input.FiveMoveStart
import com.match3vision.analyzer.input.LiveFrameFilter
import com.match3vision.analyzer.input.CaptureOverlayTrace
import com.match3vision.analyzer.input.DiagnosticAnalysisGate
import com.match3vision.analyzer.input.CalibrationLibrary
import com.match3vision.analyzer.input.CalibrationReuse
import com.match3vision.analyzer.input.CoordinateSelfCheck
import com.match3vision.analyzer.input.GameMoveLog
import com.match3vision.analyzer.input.SavedCalibration
import com.match3vision.analyzer.input.ScreenKey
import com.match3vision.analyzer.input.AutomaticInputEngine
import com.match3vision.analyzer.input.BotLoopOutcome
import com.match3vision.analyzer.input.GestureFailSafe
import com.match3vision.analyzer.input.InputThresholds
import com.match3vision.analyzer.input.MatchMastersAccessibilityService
import com.match3vision.analyzer.input.DiagnosticBundle
import com.match3vision.analyzer.input.DiagnosticExport
import com.match3vision.analyzer.input.DiagnosticFrame
import com.match3vision.analyzer.input.PngEncoder
import com.match3vision.analyzer.input.DiagnosticHistory
import com.match3vision.analyzer.input.DiagnosticHistoryStore
import com.match3vision.analyzer.input.DiagnosticLuminance
import com.match3vision.analyzer.input.LoopFailure
import com.match3vision.analyzer.input.DiagnosticShare
import com.match3vision.analyzer.input.VerifyTiming
import com.match3vision.analyzer.input.ProductionCycleContext
import com.match3vision.analyzer.input.RuntimeCycleContext
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
 * TESZT ÉRINTÉS → full-screen calibration overlay that records a raw finger point.
 * It does not inject a gesture.
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
    private var titleView: TextView? = null
    private var statusView: TextView? = null
    private var calibrationView: CalibrationOverlayView? = null
    private var calibrationOverlayVisible: Boolean = false
    private var calibrationDismissWallMs: Long = 0L
    private var chipNotice: String = ""
    private var fiveSeek: FiveMoveSeek? = null
    private var startBtn: Button? = null
    private var fiveMoveBtn: Button? = null
    private var boosterBtn: Button? = null
    private var serviceWallMs: Long = 0L
    private var calibrationLibrary = CalibrationLibrary()
    private var activeSaved: SavedCalibration? = null
    private var captureStartWallMs: Long = 0L
    private var pauseBtn: Button? = null
    private var touchTestBtn: Button? = null
    private var diagHintView: TextView? = null
    private val hiddenWhileCapturing = ArrayList<View>()
    private var collapsedForCapture = false
    private var fiveSettleInProgress = false
    private val hudPulse = HudPulse()
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
        serviceWallMs = System.currentTimeMillis()
        DiagnosticHistoryStore.install(
            java.io.File(filesDir, "diagnostics"),
            com.match3vision.analyzer.BuildConfig.VERSION_CODE,
        )
        showBubble()
        loadSavedCalibration()
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
        titleView = title
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
        val startAlso = Button(this).apply {
            text = "START"
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
        touchTestBtn = compactBubbleButton("TESZT ÉRINTÉS") { runTouchTestFromBubble() }
        val oneMoveBtn = Button(this).apply {
            text = "EGY LÉPÉS"
            textSize = 11f
            isAllCaps = false
            setOnClickListener { armSingleMoveFromBubble() }
        }
        val fiveBtn = compactBubbleButton("10 LÉPÉS TESZT") { startFiveMoveFromBubble() }
        fiveMoveBtn = fiveBtn
        val boosterToggle = compactBubbleButton(BoosterControl.label()) { toggleBoosterFromBubble() }
        boosterBtn = boosterToggle
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
        val stopBtn = compactBubbleButton("STOP") { stopAllAndSelf() }
        root.addView(title)
        root.addView(diagHintView)
        root.addView(statusView)
        root.addView(startBtn)
        root.addView(startAlso)
        root.addView(pauseBtn)
        root.addView(touchTestBtn)
        root.addView(fiveBtn)
        root.addView(boosterToggle)
        root.addView(oneMoveBtn)
        root.addView(shareBtn)
        root.addView(copyBtn)
        root.addView(clearDiagBtn)
        root.addView(stopBtn)
        hiddenWhileCapturing.clear()
        hiddenWhileCapturing.add(startBtn!!)
        hiddenWhileCapturing.add(startAlso)
        hiddenWhileCapturing.add(pauseBtn!!)
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
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
        root.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                onOutsideTouch(event)
                true
            } else {
                false
            }
        }
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
        dismissCalibrationOverlay()
        val v = bubbleView ?: return
        try {
            windowManager?.removeView(v)
        } catch (_: Throwable) {
        }
        bubbleView = null
        layoutParams = null
        titleView = null
        statusView = null
        startBtn = null
        fiveMoveBtn = null
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
        if (!captureOk && overlayOk && attempt < CAPTURE_START_RETRIES) {
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
        if (a11y && captureOk && overlayOk) {
            val ready = AutoPlaySession.controller.prepareScoredSession(
                a11yConnected = a11y,
                captureReady = captureOk,
                overlayReady = overlayOk,
            )
            if (!ready) {
                val reason = AutoPlaySession.controller.lastReason
                AutoPlaySession.publish(statusText = reason, a11yReady = a11y)
                AutoPlaySession.updateDiagnostics(a11yConnected = a11y, stopReason = reason)
                refreshBubbleUi()
                Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
                return
            }
            AutoPlaySession.syncFrameGateFromMode()
            AutoPlaySession.refreshFromController("fut")
            AutoPlaySession.updateDiagnostics(a11yConnected = a11y, clearStopReason = true)
            collapseBubbleForCapture()
            refreshBubbleUi()
            sendBroadcast(Intent(ACTION_MINIMIZE_ANALYZER).setPackage(packageName))
            ensureLoopRunning()
            startFiveSeekAfterLoopReady()
            return
        }
        val reason = when {
            !a11y -> FiveMoveArm.NEED_A11Y
            !captureOk -> "CAPTURE: OFF"
            else -> "HOLD — overlay is not collapsed"
        }
        showChipNotice(reason)
        Timber.w("startLoop blocked: %s", reason)
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
        val job = loopJob
        loopJob = null
        val session = AutoPlaySession.controller.fiveMove
        if (session.isActive) {
            session.abort("STOP pressed", System.currentTimeMillis())
        }
        fiveSeek?.let { seek ->
            val expired = seek.expireOverdue(Long.MAX_VALUE)
            if (expired != null) {
                try {
                    DiagnosticHistoryStore.setFiveMoveReport(expired.report.export)
                } catch (_: Throwable) {
                }
            }
            fiveSeek = null
        }
        flushFiveMoveReport(session)
        CoordinateSelfCheck.clear()
        AutoPlaySession.controller.onBubbleStop("bubble STOP")
        AutoPlaySession.syncFrameGateFromMode()
        job?.cancel()
        AutoPlaySession.publishStoppedCapture()
        AutoPlaySession.endSession()
        CaptureService.stop(this)
        removeBubble()
        stopSelf()
    }


    /**
     * TESZT ÉRINTÉS shows our own full-screen calibration overlay.
     * The finger is consumed by that window. This does not call
     * [com.match3vision.analyzer.input.AutomaticTouchTest.runOnce] and it
     * does not pause the loop for an injected gesture.
     */
    private fun runTouchTestFromBubble() {
        if (!MatchMastersAccessibilityService.isConnected()) {
            showChipNotice(FiveMoveArm.NEED_A11Y)
            return
        }
        showCalibrationOverlay()
    }

    private fun showCalibrationOverlay() {
        dismissCalibrationOverlay()
        val (w, h) = screenSizePx()
        val plan = CalibrationWindowPlan.plan(w, h)
        Timber.i(
            "TOUCH_TEST: calibration overlay screen=%dx%d origin=(%d,%d) fitInsets=%d — no dispatchGesture",
            w,
            h,
            plan.x,
            plan.y,
            plan.fitInsetsTypes,
        )
        val view = CalibrationOverlayView(
            this,
            w,
            h,
            onRawUp = { rawX, rawY -> recordCalibrationTouch(rawX, rawY, w, h) },
            onClose = { dismissCalibrationOverlay() },
        )
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            plan.width,
            plan.height,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = plan.x
            y = plan.y
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = plan.fitInsetsTypes
                fitInsetsSides = plan.fitInsetsSides
                setFitInsetsIgnoringVisibility(true)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        calibrationView = view
        try {
            windowManager?.addView(view, params)
        } catch (t: Throwable) {
            calibrationView = null
            calibrationOverlayVisible = false
            Timber.e(t, "TOUCH_TEST: calibration overlay add failed")
            showChipNotice("kalibráló réteg nem nyílt meg")
            return
        }
        calibrationOverlayVisible = true
        showChipNotice(CalibrationTarget.INSTRUCTION)
    }

    private fun compactBubbleButton(label: String, onClick: () -> Unit): Button {
        val density = resources.displayMetrics.density
        val d = if (density > 0f) density else 1f
        return Button(this).apply {
            text = label
            textSize = 10f
            isAllCaps = false
            minimumHeight = 0
            minHeight = (26 * d).toInt()
            setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
            setOnClickListener { onClick() }
        }
    }

    private fun showChipNotice(text: String) {
        val shown = OwnerStatus.hu(text)
        chipNotice = shown
        statusView?.text = shown
        Toast.makeText(this, shown, Toast.LENGTH_LONG).show()
        AutoPlaySession.publish(statusText = shown)
    }

    private fun dismissCalibrationOverlay() {
        val v = calibrationView
        calibrationOverlayVisible = false
        if (v != null) {
            calibrationDismissWallMs = System.currentTimeMillis()
        }
        calibrationView = null
        if (v == null) return
        try {
            windowManager?.removeView(v)
        } catch (_: Throwable) {
        }
    }

    /**
     * Records getRawX/getRawY against [CalibrationTouch.expectedPoint].
     * Insets are written and not added to the expected point.
     * alignmentProven stays false.
     */
    private fun recordCalibrationTouch(rawX: Float, rawY: Float, screenW: Int, screenH: Int) {
        val screen = ProductionLiveReaders.screenSource.measure()
        val frame = CaptureService.managerOrNull()?.latestFrame?.value
        val frameW = if (frame != null && frame.width > 0) frame.width else 0
        val frameH = if (frame != null && frame.height > 0) frame.height else 0
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
        val view = calibrationView
        val nominal = CalibrationTouch.expectedPoint(screenW, screenH)
        val (targetX, targetY) = if (view != null) {
            val loc = IntArray(2)
            val located = try {
                view.getLocationOnScreen(loc)
                true
            } catch (_: Throwable) {
                false
            }
            val (drawX, drawY) = view.drawPoint()
            if (located) {
                CalibrationTarget.onScreenPoint(loc[0], loc[1], drawX, drawY)
            } else {
                nominal
            }
        } else {
            nominal
        }
        val rec = CalibrationTouch.recordRawTouch(
            rawX = rawX,
            rawY = rawY,
            screenWidth = screenW,
            screenHeight = screenH,
            frameWidth = frameW,
            frameHeight = frameH,
            rotation = screen.rotation,
            statusBarInsetPx = statusBar,
            navigationBarInsetPx = navBar,
            cutoutInsetPx = cutoutTop,
            targetX = targetX,
            targetY = targetY,
        )
        CoordinateSelfCheck.write(java.io.File(filesDir, "diagnostics"))
        val distance = CalibrationTarget.distance(rawX, rawY, targetX, targetY)
        val toast = when (rec.status) {
            CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE ->
                CalibrationTarget.resultLine(within = true, distancePx = distance)
            CoordinateSelfCheck.STATUS_OBSERVED_MISMATCH ->
                CalibrationTarget.resultLine(within = false, distancePx = distance)
            else -> rec.reason
        }
        view?.showResult(toast)
        if (rec.status == CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE) {
            rememberCalibration(rec)
            view?.scheduleAutoDismiss { dismissCalibrationOverlay() }
            showChipNotice("KALIBRÁCIÓ OK")
        } else {
            showChipNotice(toast)
        }
        Timber.i(
            "TOUCH_TEST: raw=(%s,%s) nominal=(%s,%s) onScreen=(%s,%s) distance=%s status=%s alignmentProven=%s",
            rawX,
            rawY,
            nominal.first,
            nominal.second,
            targetX,
            targetY,
            distance,
            rec.status,
            rec.alignmentProven,
        )
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
        p.width = (COLLAPSED_CONTROL_WIDTH_DP * d).toInt().coerceAtLeast(1)
        p.height = (COLLAPSED_CONTROL_HEIGHT_DP * d).toInt().coerceAtLeast(1)
        for (child in hiddenWhileCapturing) {
            child.visibility = View.GONE
        }
        diagHintView?.visibility = View.GONE
        val tight = (2 * d).toInt()
        (v as? LinearLayout)?.setPadding(tight, tight, tight, tight)
        try {
            windowManager?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            Timber.w(t, "collapse overlay failed")
        }
        refreshBubbleUi()
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
        val pad = (8 * resources.displayMetrics.density).toInt()
        (v as? LinearLayout)?.setPadding(pad, pad, pad, pad)
        collapsedForCapture = false
        collapseWallMs = 0L
        try {
            windowManager?.updateViewLayout(v, p)
        } catch (t: Throwable) {
            Timber.w(t, "restore overlay failed")
        }
        refreshBubbleUi()
    }

    private fun overlayRectText(): String {
        val rect = overlayRectOnScreen() ?: return "unknown"
        return "LTRB(${rect.left},${rect.top},${rect.right},${rect.bottom})"
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
                if (ctrl.mode == AutoPlayController.Mode.STOPPED) {
                    restoreExpandedBubble()
                    break
                }
                if (!ctrl.isLoopActive()) {
                    restoreExpandedBubble()
                    refreshBubbleUi()
                    delay(200L)
                    continue
                }
                if (captureStartWallMs == 0L && CaptureService.managerOrNull()?.isCapturing?.value == true) {
                    captureStartWallMs = System.currentTimeMillis()
                }
                fiveSeek?.expireOverdue(System.currentTimeMillis())?.let { expired ->
                    finishFiveSeek(expired, armed = false)
                }
                if (ctrl.fiveMove.isActive) {
                    val safety = ctrl.fiveMove.pollSafety(
                        nowMs = System.currentTimeMillis(),
                        ownUi = AutoPlaySession.frameGate.analyzerUiForeground,
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                    )
                    if (safety != null) {
                        val capturing = CaptureService.managerOrNull()?.isCapturing?.value == true
                        DiagnosticHistoryStore.noteRuntime(
                            captureState = if (capturing) "ON" else "OFF",
                            lastStopReason = safety.reason,
                        )
                        // Stop play, but do not expand the bubble or skip this
                        // iteration. Expanding clears the collapse clock, and
                        // skipping drops the frame before it can enter the ring.
                        flushFiveMoveReport(ctrl.fiveMove)
                        ctrl.finishFiveMoveKeepCapture(safety.reason)
                    }
                }
                collapseBubbleForCapture()
                // Accessibility off stays in the loop for capture, analysis, and
                // export. Play dispatch is a separate gate and is not started.
                val a11yLive = MatchMastersAccessibilityService.isConnected()
                if (!a11yLive && !ctrl.analysisOnly) {
                    ctrl.enterAnalysisOnly(
                        "diagnosztika — kisegítő KI, elemzés és export, nincs érintés",
                        becauseA11yOff = true,
                    )
                    AutoPlaySession.publish(a11yReady = false)
                    AutoPlaySession.updateDiagnostics(
                        a11yConnected = false,
                        phase = "DIAGNOSZTIKA",
                        gestureStatus = "NOT CREATED",
                        inputBlockReason = "diagnostic analysis only",
                        heartbeatMs = System.currentTimeMillis(),
                    )
                } else {
                    ctrl.clearAnalysisOnlyWhenAccessibilityConnects(a11yLive)
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
                    DiagnosticHistoryStore.noteRuntime(
                        captureState = if (capturing) "ON (no frame)" else "OFF",
                        lastStopReason = if (!miss.allow) miss.reason else null,
                    )
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
                        CaptureOverlayTrace.noteSkip()
                        CaptureOverlayTrace.collapsed = collapsedForCapture
                        CaptureOverlayTrace.collapseWallMs = collapseWallMs
                        CaptureOverlayTrace.overlayRect = overlayRectText()
                        CaptureOverlayTrace.analyzedFrameTimestampMs = useFrame.timestampMs
                        CaptureOverlayTrace.gestureStatus = "NOT CREATED"
                        CaptureOverlayTrace.dispatchState = "NOT STARTED"
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
                    CaptureOverlayTrace.collapsed = collapsedForCapture
                    CaptureOverlayTrace.collapseWallMs = collapseWallMs
                    CaptureOverlayTrace.overlayRect = overlayRectText()
                    CaptureOverlayTrace.gateResult = overlayGate.reason
                    CaptureOverlayTrace.analyzedFrameTimestampMs = useFrame.timestampMs
                    offerFiveSeek(useFrame, visionRaw, overlayGate)
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
                    val analysisGate = DiagnosticAnalysisGate.decide(
                        captureOn = true,
                        hasFrame = true,
                        analysisOnly = ctrl.analysisOnly,
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                        inputEnabled = ctrl.enableSwitch().isEnabled(),
                    )
                    if (!analysisGate.callRunCycle) {
                        CaptureOverlayTrace.gestureStatus = "NOT CREATED"
                        CaptureOverlayTrace.dispatchState = "NOT STARTED"
                        AutoPlaySession.updateDiagnostics(
                            frameReceived = true,
                            visionText = if (vision.validation.isPass) "PASS" else "HOLD",
                            visionPassFlag = vision.validation.isPass,
                            a11yConnected = MatchMastersAccessibilityService.isConnected(),
                            phase = "DIAGNOSZTIKA",
                            unknownCount = vision.unknownCount,
                            gridConfidence = vision.gridConfidence,
                            boardConfidence = vision.boardConfidence,
                            boardDetection = boardDet,
                            boardRoi = boardRoiStr,
                            frameSequence = "seq=${useFrame.sequence}",
                            frameAgeMs = useFrame.ageMs(),
                            frameTimestampMs = useFrame.timestampMs,
                            frameWidth = useFrame.width,
                            frameHeight = useFrame.height,
                            frameFreshness = RuntimeLabels.freshness(true, useFrame.ageMs()),
                            captureStatus = "ON",
                            heartbeatMs = System.currentTimeMillis(),
                            cycleReason = analysisGate.reason,
                            gestureStatus = "NOT CREATED",
                            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
                            inputBlockReason = "diagnostic analysis only",
                            verifyStatus = VerificationPolicy.PENDING,
                            hasFrameFlag = true,
                            simulated = false,
                        )
                        publishSafetyDiagnostics(
                            vision = vision,
                            screen = screen,
                            frame = useFrame,
                            pixels = analyzed.pixels,
                            pixelNote = analyzed.pixelNote,
                            moveText = "none",
                            coordinateRefused = false,
                            coordinateReason = "coordinate origin alignment UNPROVEN",
                            dispatchStatus = "NOT STARTED",
                            callbackOutcome = "not dispatched",
                            verificationStatus = VerificationPolicy.PENDING,
                            verificationReason = analysisGate.reason,
                            gestureStatus = "NOT CREATED",
                            analysisOnly = ctrl.analysisOnly,
                        )
                        AutoPlaySession.refreshFromController(analysisGate.reason)
                        refreshBubbleUi()
                        if (ctrl.fiveMove.isActive) {
                            val done = runFiveMoveTick(
                                ctrl = ctrl,
                                vision = vision,
                                frame = useFrame,
                                pixels = analyzed.pixels,
                                overlayAllows = true,
                                cycleContext = cycleContext.copy(maxFrameAgeMs = settleAgeLimit()),
                            )
                            if (done) {
                                endFiveMoveInLoop(ctrl, ctrl.fiveMove.stopReason)
                                continue
                            }
                        }
                        delay(280L)
                        continue
                    }
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
                        gestureStatus = cycle.gestureStatus,
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
                                        gestureStatus = "CREATED",
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
                                        frameWidth = afterFrame?.width?.takeIf { it > 0 },
                                        frameHeight = afterFrame?.height?.takeIf { it > 0 },
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
                                    gestureStatus = cycle.gestureStatus,
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
                    val reason = LoopFailure.reasonOrNull(t) ?: throw t
                    Timber.e(t, "auto-play loop error")
                    if (ctrl.fiveMove.isActive) {
                        ctrl.fiveMove.abort("STOP — exception: ${t.message}", System.currentTimeMillis())
                        endFiveMoveInLoop(ctrl, ctrl.fiveMove.stopReason)
                        delay(200L)
                        continue
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

    private fun startFiveMoveFromBubble() {
        val ctrl = AutoPlaySession.controller
        val now = System.currentTimeMillis()
        if (ctrl.mode == AutoPlayController.Mode.STOPPED) {
            val a11y = MatchMastersAccessibilityService.isConnected()
            val captureOk = CaptureService.managerOrNull() != null
            val overlayOk = android.provider.Settings.canDrawOverlays(this)
            val block = ctrl.explicitFiveMoveRestart(a11y, captureOk, overlayOk)
            if (block != null) {
                showChipNotice(block)
                try {
                    DiagnosticHistoryStore.setFiveMoveReport(FiveMoveStart.immediate(now, block))
                } catch (t: Throwable) {
                    Timber.w(t, "five-move restart refusal export failed")
                }
                refreshBubbleUi()
                return
            }
            val started = ctrl.onDiagnosticStart(
                captureReady = captureOk,
                overlayReady = overlayOk,
                a11yConnected = a11y,
            )
            if (!started) {
                val reason = ctrl.lastReason.ifBlank { FiveMoveArm.PRESS_START }
                showChipNotice(reason)
                refreshBubbleUi()
                return
            }
            AutoPlaySession.syncFrameGateFromMode()
            collapseBubbleForCapture()
            ensureLoopRunning()
        }
        startFiveSeekAfterLoopReady()
    }

    /** Arms the 10-move seek once the loop is already RUNNING with input off. */
    private fun startFiveSeekAfterLoopReady() {
        val ctrl = AutoPlaySession.controller
        val now = System.currentTimeMillis()
        val calibrated = selfCheckThisSession()
        val refusal = ctrl.fiveMoveRefusal(
            selfCheckThisSession = calibrated,
            a11yConnected = MatchMastersAccessibilityService.isConnected(),
            autoProbe = !calibrated,
        )
        if (refusal != null) {
            showChipNotice(refusal)
            try {
                DiagnosticHistoryStore.setFiveMoveReport(FiveMoveStart.immediate(now, refusal))
            } catch (t: Throwable) {
                Timber.w(t, "five-move refusal export failed")
            }
            refreshBubbleUi()
            return
        }
        if (fiveSeek?.isActive == true) {
            showChipNotice(FiveMoveArm.ALREADY)
            return
        }
        fiveSeek = FiveMoveSeek().also { it.begin(now) }
        chipNotice = "10 LÉPÉS: ne érintsd a képernyőt. Várok egy PASS képkockát."
        refreshBubbleUi()
    }

    /**
     * One frame of the 5 s start wait. A failing frame does not arm and does not
     * abort. The first frame that passes the existing preconditions arms the
     * session with the button-press clock, so the wait is inside the 60 s budget.
     */
    private fun offerFiveSeek(
        frame: CaptureFrame,
        vision: com.match3vision.analyzer.vision.VisionResult,
        overlayGate: OverlayBoardGate.Decision,
    ) {
        val seek = fiveSeek ?: return
        if (!seek.isActive) return
        val held = vision.validation as? com.match3vision.analyzer.vision.ValidationResult.Hold
        val roiDetail = when {
            held?.reason?.contains("ROI IMPLAUSIBLE") == true -> "ROI IMPLAUSIBLE"
            vision.diagnostics["roiPlausible"] == "no" -> "ROI IMPLAUSIBLE"
            roiLooksPlausible(vision) -> "yes"
            else -> "ROI IMPLAUSIBLE"
        }
        val calibration = calibrationOverlayVisible ||
            (calibrationDismissWallMs > 0L &&
                frame.timestampMs < calibrationDismissWallMs + DiagnosticHistory.TRANSITION_SKIP_MS)
        val median = CaptureService.managerOrNull()?.cadence?.medianIntervalMs() ?: 0L
        val sample = FiveMoveStart.Sample(
            nowMs = System.currentTimeMillis(),
            frameSequence = frame.sequence,
            frameAgeMs = frame.ageMs(),
            cadenceMedianMs = median,
            a11yConnected = MatchMastersAccessibilityService.isConnected(),
            selfCheckMeasured = selfCheckThisSession(),
            overlayCollapsed = collapsedForCapture,
            overlayOutsideRoi = overlayGate.allowAnalysis,
            visionPass = vision.validation.isPass,
            unknownCount = vision.unknownCount,
            roiPlausible = roiLooksPlausible(vision),
            roiDetail = roiDetail,
            ownUi = AutoPlaySession.frameGate.analyzerUiForeground,
            calibration = calibration,
            msSinceCollapse = if (collapseWallMs > 0L) {
                frame.timestampMs - collapseWallMs
            } else {
                -1L
            },
        )
        when (val offer = seek.offer(System.currentTimeMillis(), sample)) {
            is FiveMoveStart.Offer.Waiting -> {
                chipNotice = offer.report.chip
                try {
                    DiagnosticHistoryStore.setFiveMoveReport(offer.report.export)
                } catch (t: Throwable) {
                    Timber.w(t, "five-move seek export failed")
                }
                refreshBubbleUi()
            }
            is FiveMoveStart.Offer.Ready -> {
                val ctrl = AutoPlaySession.controller
                val calibrated = selfCheckThisSession()
                val armed = ctrl.armFiveMoveTest(
                    nowMs = System.currentTimeMillis(),
                    selfCheckThisSession = calibrated,
                    a11yConnected = MatchMastersAccessibilityService.isConnected(),
                    clockStartMs = seek.startedAtMs,
                    autoProbe = !calibrated,
                )
                if (!armed) {
                    fiveSeek = null
                    showChipNotice(ctrl.lastReason)
                    try {
                        DiagnosticHistoryStore.setFiveMoveReport(
                            FiveMoveStart.immediate(seek.startedAtMs, ctrl.lastReason),
                        )
                    } catch (t: Throwable) {
                        Timber.w(t, "five-move arm export failed")
                    }
                    return
                }
                hudPulse.clear()
                ctrl.fiveMove.noteStartExport(offer.report.export)
                val savedLine = savedCalibrationLine()
                if (savedLine.isNotBlank()) ctrl.fiveMove.noteCalibration(savedLine)
                fiveSeek = null
                chipNotice = ""
                refreshBubbleUi()
            }
            is FiveMoveStart.Offer.Expired -> finishFiveSeek(offer, armed = false)
        }
    }

    private fun finishFiveSeek(expired: FiveMoveStart.Offer.Expired, armed: Boolean) {
        fiveSeek = null
        if (armed) return
        chipNotice = expired.report.chip
        try {
            DiagnosticHistoryStore.setFiveMoveReport(expired.report.export)
        } catch (t: Throwable) {
            Timber.w(t, "five-move seek export failed")
        }
        Toast.makeText(this, expired.report.chip, Toast.LENGTH_LONG).show()
        AutoPlaySession.publish(statusText = expired.report.chip)
        refreshBubbleUi()
    }

    private fun calibrationFile() = java.io.File(filesDir, "calibration-library.txt")

    private fun currentScreenKey(): ScreenKey {
        val (width, height) = screenSizePx()
        val screen = ProductionLiveReaders.screenSource.measure()
        val dpi = if (screen.densityDpi > 0) screen.densityDpi else resources.displayMetrics.densityDpi
        return ScreenKey(
            screenWidth = width,
            screenHeight = height,
            rotation = screen.rotation,
            densityDpi = dpi,
            versionCode = com.match3vision.analyzer.BuildConfig.VERSION_CODE,
        )
    }

    private fun loadSavedCalibration() {
        try {
            calibrationLibrary = CalibrationLibrary.read(calibrationFile())
            val saved = calibrationLibrary.find(currentScreenKey()) ?: return
            CoordinateSelfCheck.restore(saved.record)
            activeSaved = saved
            if (chipNotice.isBlank()) chipNotice = saved.chip()
        } catch (t: Throwable) {
            Timber.w(t, "saved calibration was not restored")
        }
    }

    private fun rememberCalibration(record: CoordinateSelfCheck.Record) {
        if (record.status != CoordinateSelfCheck.STATUS_MEASURED_WITHIN_TOLERANCE) return
        try {
            val saved = SavedCalibration(currentScreenKey(), record)
            calibrationLibrary.put(saved)
            calibrationLibrary.write(calibrationFile())
            activeSaved = saved
        } catch (t: Throwable) {
            Timber.w(t, "calibration was not saved")
        }
    }

    /** Mentett line only when this process reused an older hit. */
    private fun savedCalibrationLine(): String {
        if (!selfCheckThisSession()) return ""
        val saved = activeSaved ?: return ""
        val record = CoordinateSelfCheck.current() ?: return ""
        if (serviceWallMs > 0L && record.recordedAtMs >= serviceWallMs) return ""
        return saved.chip()
    }

    private fun calibrationNotice(): String {
        val saved = savedCalibrationLine()
        return when {
            chipNotice.isBlank() -> saved
            saved.isBlank() || chipNotice.contains(saved) -> chipNotice
            else -> chipNotice + "\n" + saved
        }
    }

    private fun closeGameLog(
        session: FiveMoveSession,
        after: GameMoveLog.BoardView?,
        verification: String,
        settleMs: Long = 0L,
        elapsedMs: Long = 0L,
        userInterference: Boolean = false,
    ) {
        val reason = if (session.phase == FiveMoveSession.Phase.STOPPED) session.stopReason else ""
        session.finishGameLog(
            after = after,
            verification = verification.ifBlank { session.stopReason },
            settleMs = settleMs,
            elapsedMs = elapsedMs,
            stopReason = reason,
            userInterference = userInterference,
        )
    }

    private fun selfCheckThisSession(): Boolean = CalibrationReuse.accepts(
        record = CoordinateSelfCheck.current(),
        serviceWallMs = serviceWallMs,
        saved = activeSaved,
        now = currentScreenKey(),
    )

    private fun endFiveMoveInLoop(ctrl: AutoPlayController, reason: String) {
        flushFiveMoveReport(ctrl.fiveMove)
        restoreExpandedBubble()
        ctrl.finishFiveMoveKeepCapture(reason)
        refreshBubbleUi()
    }

    private fun flushFiveMoveReport(session: FiveMoveSession) {
        if (session.phase == FiveMoveSession.Phase.IDLE && session.startedAtMs == 0L) return
        try {
            DiagnosticHistoryStore.setFiveMoveReport(session.report())
            val log = session.gameLogText()
            val dir = DiagnosticHistoryStore.directory()
            if (log.isNotBlank() && dir != null) {
                java.io.File(dir, "game-log.jsonl").writeText(log)
            }
        } catch (t: Throwable) {
            Timber.w(t, "five-move report write failed")
        }
    }

    /**
     * The only outside-touch producer is this overlay listener. It fires for
     * [MotionEvent.ACTION_OUTSIDE] because the bubble sets
     * [WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH]. Accessibility
     * window and content events never get here, and a board or special
     * animation does not synthesize a MotionEvent. A counted touch is a
     * touchscreen finger. Anything else is logged and dropped.
     */
    private fun onOutsideTouch(event: MotionEvent) {
        val ctrl = AutoPlaySession.controller ?: return
        val session = ctrl.fiveMove
        if (!session.isActive) return
        val inputSource = if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            OverlayOutsideTouch.TOUCHSCREEN
        } else {
            "source-${event.source}"
        }
        val toolType = if (
            event.pointerCount > 0 && event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        ) {
            OverlayOutsideTouch.FINGER
        } else {
            "tool-${if (event.pointerCount > 0) event.getToolType(0) else -1}"
        }
        when (val decision = session.noteOutsideTouch(
            System.currentTimeMillis(),
            event.rawX,
            event.rawY,
            MotionEvent.actionToString(event.action),
            OverlayOutsideTouch.WINDOW,
            inputSource,
            toolType,
        )) {
            null -> return
            is FiveMoveSession.Decision.Hold -> {
                Timber.i("fiveMoveSession: pause %s", decision.reason)
                refreshBubbleUi()
                return
            }
            is FiveMoveSession.Decision.Stop -> Unit
            is FiveMoveSession.Decision.Go -> return
        }
        Timber.i("fiveMoveSession: user interference %s", session.stopReason)
        if (fiveSettleInProgress) return
        closeGameLog(
            session = session,
            after = null,
            verification = session.stopReason,
            userInterference = true,
        )
        flushFiveMoveReport(session)
        ctrl.finishFiveMoveKeepCapture(session.stopReason)
        refreshBubbleUi()
    }

    private fun roiLooksPlausible(vision: com.match3vision.analyzer.vision.VisionResult): Boolean {
        if (vision.diagnostics["roiPlausible"] == "no") return false
        val held = vision.validation as? com.match3vision.analyzer.vision.ValidationResult.Hold
        return held?.reason?.contains("ROI IMPLAUSIBLE") != true
    }

    private fun toggleBoosterFromBubble() {
        BoosterControl.enabled = !BoosterControl.enabled
        boosterBtn?.text = BoosterControl.label()
        refreshBubbleUi()
    }

    private fun readHud(pixels: IntArray?, frame: CaptureFrame): HudObservation {
        if (pixels == null || frame.width <= 0 || frame.height <= 0) return hudPulse.apply(HudObservation.UNKNOWN)
        val raw = try {
            HudReader.read(pixels, frame.width, frame.height)
        } catch (t: Throwable) {
            Timber.w(t, "hud read failed")
            HudObservation.UNKNOWN
        }
        return hudPulse.apply(raw)
    }

    /**
     * One left-card ACTIVATE tap when the word is read, the toggle is on, the
     * turn is ours, a swipe has already verified, and no extra-move swap is
     * available. ACTIVATE is read again on the latest frame before the tap.
     * A booster that needs a target stops the session. A miss latches and
     * does not tap the board. Returns true when a tap was sent, so the caller
     * must not also swipe on this stale frame.
     */
    private suspend fun trySoloBooster(
        session: FiveMoveSession,
        hud: HudObservation,
        frame: CaptureFrame,
        vision: com.match3vision.analyzer.vision.VisionResult,
        extraMoveAvailable: Boolean,
        boosterDecision: String,
    ): Boolean {
        if (session.boosterLatched || session.phase != FiveMoveSession.Phase.RUNNING) return false
        val tap = SoloBooster.plan(
            hud,
            frame.width,
            frame.height,
            BoosterControl.enabled,
            extraMoveAvailable,
            swipesVerified = session.swipesVerified,
            selfCheckMeasured = selfCheckThisSession(),
        ) ?: return false
        val beforeHash = Board.fromVision(vision.board).contentHash()
        val executor = AccessibilityGestureExecutor()
        if (!executor.isReady()) return false
        val started = System.currentTimeMillis()
        val gesture = GestureSpec.tap(tap.x, tap.y, durationMs = 80L)
        val latest = CaptureService.managerOrNull()?.latestFrame?.value
        var frameNow = if (latest != null && latest.sequence >= frame.sequence) latest else frame
        var visionNow = vision
        var hudNow = hud
        if (frameNow.sequence != frame.sequence || frameNow !== frame) {
            val again = withContext(Dispatchers.Default) { analyzeFrame(frameNow) }
            val read = again.vision
            if (read != null) {
                visionNow = read
                hudNow = readHud(again.pixels, frameNow)
            }
        }
        var dispatched: InputDispatchResult? = null
        while (dispatched == null) {
            val elapsed = System.currentTimeMillis() - started
            val ageLimit = settleAgeLimit()
            val liveHash = Board.fromVision(visionNow.board).contentHash()
            val gates = visionNow.validation.isPass &&
                roiLooksPlausible(visionNow) &&
                SoloBooster.stillArmed(hud, hudNow, beforeHash, liveHash)
            val recheck = DispatchRecheck.evaluate(
                boosterPermit(gesture, frameNow, visionNow, ageLimit),
                FrameClock.tryElapsed(),
            )
            when (
                FreshFrameDispatch.booster(
                    elapsedMs = elapsed,
                    ageMs = frameNow.ageMs(),
                    ageLimitMs = ageLimit,
                    gatesPass = gates,
                    showsActivateOrYourTurn = FreshFrameDispatch.showsActivate(hudNow),
                    recheckAllow = recheck.allow,
                )
            ) {
                FreshFrameDispatch.Booster.GIVE_UP -> {
                    session.giveUpBooster()
                    return false
                }
                FreshFrameDispatch.Booster.WAIT -> {
                    delay(FiveMoveSession.POLL_STEP_MS)
                    val next = CaptureService.managerOrNull()?.latestFrame?.value ?: continue
                    if (next.sequence < frameNow.sequence) continue
                    val analyzed = withContext(Dispatchers.Default) { analyzeFrame(next) }
                    val nextVision = analyzed.vision ?: continue
                    frameNow = next
                    visionNow = nextVision
                    hudNow = readHud(analyzed.pixels, next)
                }
                FreshFrameDispatch.Booster.SEND -> {
                    val tapAt = System.currentTimeMillis()
                    session.beginBoosterWindow(tapAt)
                    session.beginOwnGesture(tap.x, tap.y, tap.x, tap.y, tapAt)
                    dispatched = try {
                        withContext(Dispatchers.Default) {
                            executor.dispatchRecognizedTap(gesture)
                        }
                    } finally {
                        session.finishOwnGesture(System.currentTimeMillis())
                    }
                }
            }
        }
        val sent = dispatched
        val callback = sent is InputDispatchResult.Dispatched && sent.callbackCompleted
        var changed = false
        var countChange = true
        var lastHash: Long? = null
        val deadline = started + BOOSTER_SETTLE_MS
        while (System.currentTimeMillis() < deadline && session.phase == FiveMoveSession.Phase.RUNNING) {
            delay(FiveMoveSession.POLL_STEP_MS)
            val next = CaptureService.managerOrNull()?.latestFrame?.value ?: continue
            if (next.sequence <= frame.sequence) continue
            val analyzed = withContext(Dispatchers.Default) { analyzeFrame(next) }
            val nextVision = analyzed.vision ?: continue
            if (!nextVision.validation.isPass) continue
            val hudNow = readHud(analyzed.pixels, next)
            if (!com.match3vision.analyzer.hud.TurnGate.allowsVerification(hudNow)) countChange = false
            val hash = Board.fromVision(nextVision.board).contentHash()
            if (hash != beforeHash) changed = true
            if (lastHash != null && hash == lastHash && hash != beforeHash) break
            lastHash = hash
        }
        if (!callback) {
            session.endBoosterWindow(System.currentTimeMillis())
            session.recordBooster(
                changed = changed,
                stable = false,
                callbackCompleted = false,
                x = tap.x,
                y = tap.y,
                nowMs = System.currentTimeMillis(),
                playExport = moveTrace(hud, boosterDecision, hud.log()),
                countChange = countChange,
                needsTarget = false,
            )
            return true
        }
        val beforeBoard = Board.fromVision(vision.board)
        session.armBoosterSettle(
            x = tap.x,
            y = tap.y,
            nowMs = started,
            playExport = moveTrace(hud, boosterDecision, hud.log()),
            swipeSequence = frame.sequence,
            beforeHash = beforeHash,
            beforeLabel = beforeBoard.labelHash(),
            beforeCircles = if (hud.circlesClassifiable) hud.circlesBright else null,
        )
        return true
    }

    private fun boosterPermit(
        gesture: GestureSpec,
        frame: CaptureFrame,
        vision: com.match3vision.analyzer.vision.VisionResult,
        ageLimitMs: Long,
    ): DispatchPermit {
        val (screenW, screenH) = screenSizePx()
        val pass = vision.validation.isPass &&
            vision.gridConfidence >= com.match3vision.analyzer.vision.VisionThresholds.MIN_GRID_CONFIDENCE &&
            vision.boardConfidence >= com.match3vision.analyzer.vision.VisionThresholds.MIN_BOARD_CONFIDENCE &&
            vision.unknownCount <= com.match3vision.analyzer.vision.VisionThresholds.MAX_UNKNOWN_COUNT
        return DispatchPermit(
            a11yConnected = MatchMastersAccessibilityService.isConnected(),
            captureOn = true,
            hasFrame = true,
            frameAgeMs = frame.ageMs(),
            visionPass = pass,
            inputEnabled = true,
            screenWidth = screenW,
            screenHeight = screenH,
            frameWidth = frame.width,
            frameHeight = frame.height,
            gesture = gesture,
            simulated = false,
            sequenceAllowed = true,
            capturedElapsedMs = frame.elapsedRealtimeMs,
            maxFrameAgeMs = ageLimitMs,
        )
    }

    private fun moveTrace(hud: HudObservation, boosterDecision: String, body: String): String {
        val bright = hud.circlesBright?.toString() ?: "none"
        val classifiable = if (hud.circlesClassifiable) "yes" else "no"
        return "circlesBright=$bright circlesClassifiable=$classifiable boosterDecision=$boosterDecision\n$body"
    }

    private suspend fun runFiveMoveTick(
        ctrl: AutoPlayController,
        vision: com.match3vision.analyzer.vision.VisionResult,
        frame: CaptureFrame,
        pixels: IntArray?,
        overlayAllows: Boolean,
        cycleContext: RuntimeCycleContext,
    ): Boolean {
        val session = ctrl.fiveMove
        val now = System.currentTimeMillis()
        val gates = FiveMoveSession.Gates(
            nowMs = now,
            a11yConnected = MatchMastersAccessibilityService.isConnected(),
            selfCheckMeasured = selfCheckThisSession(),
            overlayCollapsed = collapsedForCapture,
            overlayOutsideRoi = overlayAllows,
            visionPass = vision.validation.isPass,
            frameFresh = frame.ageMs() <= settleAgeLimit(),
            ownUi = AutoPlaySession.frameGate.analyzerUiForeground,
            msSinceCollapse = if (collapseWallMs > 0L) frame.timestampMs - collapseWallMs else -1L,
            roiPlausible = roiLooksPlausible(vision),
            frameSequence = frame.sequence,
        )
        if (session.phase == FiveMoveSession.Phase.RUNNING && !vision.validation.isPass) {
            val early = readHud(pixels, frame)
            val dimmed = dimmedFrame(pixels, frame, vision)
            val kind = PlayGate.kind(early, visionPass = false, dimmed = dimmed)
            when (val safety = session.notePlayHud(kind, now)) {
                is FiveMoveSession.Decision.Stop -> {
                    showChipNotice(session.stopReason)
                    flushFiveMoveReport(session)
                    return true
                }
                is FiveMoveSession.Decision.Hold -> {
                    AutoPlaySession.updateDiagnostics(
                        phase = "TARTÁS",
                        cycleReason = safety.reason,
                        gestureStatus = "NOT CREATED",
                        inputBlockReason = safety.reason,
                    )
                    refreshBubbleUi()
                    return false
                }
                else -> Unit
            }
        }
        when (val decision = session.requestDispatch(gates)) {
            is FiveMoveSession.Decision.Hold -> {
                AutoPlaySession.updateDiagnostics(
                    phase = "TARTÁS",
                    cycleReason = decision.reason,
                    gestureStatus = "NOT CREATED",
                    inputBlockReason = decision.reason,
                )
                refreshBubbleUi()
                return false
            }
            is FiveMoveSession.Decision.Stop -> {
                flushFiveMoveReport(session)
                return true
            }
            is FiveMoveSession.Decision.Go -> {
                val hud = readHud(pixels, frame)
                session.noteHud(hud.log())
                val dimmed = dimmedFrame(pixels, frame, vision)
                val hudKind = PlayGate.kind(hud, visionPass = true, dimmed = dimmed)
                when (val hudDecision = session.notePlayHud(hudKind, System.currentTimeMillis())) {
                    is FiveMoveSession.Decision.Stop -> {
                        session.releaseUnusedPermit()
                        showChipNotice(session.stopReason)
                        flushFiveMoveReport(session)
                        return true
                    }
                    is FiveMoveSession.Decision.Hold -> {
                        session.releaseUnusedPermit()
                        AutoPlaySession.updateDiagnostics(
                            phase = "TARTÁS",
                            cycleReason = hudDecision.reason,
                            gestureStatus = "NOT CREATED",
                        )
                        refreshBubbleUi()
                        return false
                    }
                    else -> Unit
                }
                val spent = session.noteCircles(
                    classifiable = hud.circlesClassifiable,
                    bright = hud.circlesBright,
                    playable = true,
                    nowMs = System.currentTimeMillis(),
                    frameSequence = frame.sequence,
                )
                if (spent != null) {
                    session.releaseUnusedPermit()
                    showChipNotice(session.stopReason)
                    flushFiveMoveReport(session)
                    return true
                }
                val turnRefusal = com.match3vision.analyzer.hud.TurnGate.refusal(hud)
                if (turnRefusal != null) {
                    session.abort(turnRefusal, System.currentTimeMillis())
                    showChipNotice(turnRefusal)
                    flushFiveMoveReport(session)
                    return true
                }
                if (session.needsGeometryCheck()) {
                    val (screenW, screenH) = screenSizePx()
                    val rotation = ProductionLiveReaders.screenSource.measure().rotation
                    if (!AutoCalibration.provenGeometry(
                            frame.width,
                            frame.height,
                            screenW,
                            screenH,
                            rotation,
                        )
                    ) {
                        session.abort(AutoCalibration.STOP_GEOMETRY, System.currentTimeMillis())
                        showChipNotice(AutoCalibration.STOP_GEOMETRY)
                        flushFiveMoveReport(session)
                        return true
                    }
                }
                val extraMove = PlayMoveRanker().rank(Board.fromVision(vision.board)).ordered.any { it.extraMove }
                val boosterDecision = SoloBooster.decision(
                    hud,
                    BoosterControl.enabled,
                    extraMove,
                    session.boosterLatched,
                    swipesVerified = session.swipesVerified,
                    selfCheckMeasured = gates.selfCheckMeasured,
                )
                session.noteHud(moveTrace(hud, boosterDecision, hud.log()))
                if (trySoloBooster(session, hud, frame, vision, extraMove, boosterDecision)) {
                    session.releaseUnusedPermit()
                    if (session.phase == FiveMoveSession.Phase.STOPPED) {
                        flushFiveMoveReport(session)
                        return true
                    }
                    if (session.phase == FiveMoveSession.Phase.SETTLING) {
                        return settleFiveMove(
                            session,
                            frame,
                            vision,
                            Board.fromVision(vision.board).contentHash(),
                        )
                    }
                    refreshBubbleUi()
                    return false
                }
                val started = System.currentTimeMillis()
                writeMoveFrame(decision.permit.moveNumber, "before", pixels, frame, vision)
                val activateCrop = if (hud.activateBrightFraction > 0.5) {
                    writeActivateCrop(decision.permit.moveNumber, pixels, frame)
                } else {
                    null
                }
                session.beginOwnGesture(started)
                setBubbleTouchable(false)
                val cycle = try {
                    withContext(Dispatchers.Default) {
                        ctrl.dispatchFiveMoveOnce(vision, cycleContext, decision.permit, hud)
                    }
                } catch (t: Throwable) {
                    session.abort("STOP — exception: ${t.message}", System.currentTimeMillis())
                    null
                } finally {
                    setBubbleTouchable(true)
                }
                val dispatchedGesture = (cycle?.executed as? AutomaticInputEngine.ExecuteResult.Executed)?.gesture
                if (dispatchedGesture != null) {
                    session.rememberOwnPath(
                        dispatchedGesture.startX,
                        dispatchedGesture.startY,
                        dispatchedGesture.endX,
                        dispatchedGesture.endY,
                    )
                }
                session.finishOwnGesture(System.currentTimeMillis())
                if (session.phase == FiveMoveSession.Phase.STOPPED) {
                    flushFiveMoveReport(session)
                    return true
                }
                val executed = cycle?.executed
                if (executed !is AutomaticInputEngine.ExecuteResult.Executed) {
                    if (session.isActive) session.releaseUnusedPermit()
                    val reason = when (executed) {
                        is AutomaticInputEngine.ExecuteResult.Stopped -> executed.reason
                        is AutomaticInputEngine.ExecuteResult.Held -> executed.reason
                        else -> cycle?.reason ?: "HOLD — move was not dispatched"
                    }
                    AutoPlaySession.updateDiagnostics(
                        phase = "TARTÁS",
                        cycleReason = reason,
                        gestureStatus = "NOT CREATED",
                    )
                    refreshBubbleUi()
                    return false
                }
                val move = executed.move.move
                val ranking = ctrl.inputLoop().lastPlayRanking
                val chosen = ranking?.ordered?.drop(session.playSkip)?.firstOrNull()
                session.beginGameLog(
                    GameMoveLog.Pending(
                        moveNumber = decision.permit.moveNumber,
                        sessionStartedAtMs = session.startedAtMs,
                        before = GameMoveLog.view(
                            board = Board.fromVision(vision.board),
                            timestampMs = started,
                            gridConfidence = vision.gridConfidence,
                            boardConfidence = vision.boardConfidence,
                            unknownCount = vision.unknownCount,
                        ),
                        candidates = ranking?.ordered ?: emptyList(),
                        selected = move.toString(),
                        decisionMs = ranking?.decisionMs ?: 0L,
                        lookahead = ranking?.lookahead ?: "greedy",
                        greedyMove = ranking?.greedyMove ?: "",
                        timer = ranking?.timer ?: HudObservation.NOT_DETECTABLE,
                        hud = ranking?.hud ?: hud.log(),
                    ),
                )
                val swiped = Board.fromVision(vision.board)
                val touchesSpecial = swiped.get(move.r1, move.c1).special != com.match3vision.analyzer.vision.SpecialType.NONE ||
                    swiped.get(move.r2, move.c2).special != com.match3vision.analyzer.vision.SpecialType.NONE
                session.noteDispatchedHud(hudKind, System.currentTimeMillis())
                val noted = session.noteGesture(
                    FiveMoveSession.GestureFact(
                        startedAtMs = started,
                        nowMs = System.currentTimeMillis(),
                        callbackCompleted = executed.verificationEligible,
                        cancelled = !executed.verificationEligible,
                        cells = move.toString(),
                        fromX = executed.gesture.startX,
                        fromY = executed.gesture.startY,
                        toX = executed.gesture.endX,
                        toY = executed.gesture.endY,
                        beforeHash = executed.beforeBoardHash,
                        beforeUnknown = vision.unknownCount,
                        playExport = moveTrace(hud, boosterDecision, ranking?.export().orEmpty()) +
                            (activateCrop?.let { "\nactivateCrop=$it" } ?: ""),
                        matchLen = chosen?.matchLen ?: 0,
                        extraMove = chosen?.extraMove == true,
                        blueCleared = chosen?.blueCleared ?: 0,
                        totalCleared = chosen?.totalCleared ?: 0,
                        playUncertain = chosen?.uncertain == true,
                        swipeSequence = frame.sequence,
                        beforeLabel = swiped.labelHash(),
                        beforeCircles = if (hud.circlesClassifiable) hud.circlesBright else null,
                        longSettle = (chosen?.matchLen ?: 0) >= 4 || chosen?.extraMove == true || touchesSpecial,
                    ),
                )
                refreshBubbleUi()
                if (noted is FiveMoveSession.Decision.Stop) {
                    closeGameLog(session, after = null, verification = session.stopReason)
                    flushFiveMoveReport(session)
                    return true
                }
                return settleFiveMove(session, frame, vision, executed.beforeBoardHash)
            }
        }
    }

    private suspend fun settleFiveMove(
        session: FiveMoveSession,
        beforeFrame: CaptureFrame,
        beforeVision: com.match3vision.analyzer.vision.VisionResult,
        beforeHash: Long,
    ): Boolean {
        var previous: IntArray? = null
        var afterPixels: IntArray? = null
        var afterFrame: CaptureFrame? = null
        var afterVision: com.match3vision.analyzer.vision.VisionResult? = null
        fiveSettleInProgress = true
        try {
        while (session.phase == FiveMoveSession.Phase.SETTLING) {
            val now = System.currentTimeMillis()
            val safety = session.pollSafety(
                nowMs = now,
                ownUi = AutoPlaySession.frameGate.analyzerUiForeground,
                a11yConnected = MatchMastersAccessibilityService.isConnected(),
            )
            if (safety != null) break
            if (now >= session.activeDeadlineMs()) {
                session.onSettle(
                    FiveMoveSession.SettleSample(
                        nowMs = now,
                        boardHash = beforeHash,
                        diffFraction = null,
                        frameFresh = false,
                        roiPlausible = true,
                        visionPass = false,
                        unknownCount = -1,
                        ownUi = false,
                        a11yConnected = true,
                    ),
                )
                break
            }
            delay(FiveMoveSession.POLL_STEP_MS)
            if (session.phase != FiveMoveSession.Phase.SETTLING) break
            val next = CaptureService.managerOrNull()?.latestFrame?.value ?: continue
            if (next.sequence <= beforeFrame.sequence) continue
            val analyzed = withContext(Dispatchers.Default) { analyzeFrame(next) }
            if (session.phase != FiveMoveSession.Phase.SETTLING) break
            val nextVision = analyzed.vision
            if (!analyzed.admitted || nextVision == null) {
                val skipped = session.onSettle(
                    FiveMoveSession.SettleSample(
                        nowMs = System.currentTimeMillis(),
                        boardHash = beforeHash,
                        diffFraction = null,
                        frameFresh = next.ageMs() <= GestureFailSafe.MAX_FRAME_AGE_MS,
                        roiPlausible = false,
                        visionPass = false,
                        unknownCount = -1,
                        ownUi = AutoPlaySession.frameGate.analyzerUiForeground,
                        a11yConnected = MatchMastersAccessibilityService.isConnected(),
                        overlayOutside = false,
                        capturedAfterGesture = true,
                        frameSequence = next.sequence,
                    ),
                )
                if (skipped is FiveMoveSession.Decision.Hold) {
                    AutoPlaySession.updateDiagnostics(
                        phase = "TARTÁS",
                        cycleReason = skipped.reason,
                        gestureStatus = "SETTLING",
                    )
                    refreshBubbleUi()
                }
                if (skipped is FiveMoveSession.Decision.Stop ||
                    session.phase != FiveMoveSession.Phase.SETTLING
                ) {
                    break
                }
                continue
            }
            val roi = nextVision.grid.boardRoi
            val (screenW, screenH) = screenSizePx()
            val screen = ProductionLiveReaders.screenSource.measure()
            val overlayOutside = OverlayBoardGate.evaluate(
                overlay = overlayRectOnScreen(),
                boardLeft = roi.left,
                boardTop = roi.top,
                boardRight = roi.right,
                boardBottom = roi.bottom,
                frameWidth = next.width,
                frameHeight = next.height,
                screenWidth = screenW,
                screenHeight = screenH,
                rotation = screen.rotation,
            ).allowAnalysis
            val signature = analyzed.pixels?.let {
                BoardStability.signature(
                    pixels = it,
                    width = next.width,
                    height = next.height,
                    left = roi.left,
                    top = roi.top,
                    right = roi.right,
                    bottom = roi.bottom,
                )
            }
            val prior = previous
            val fraction = if (signature != null && prior != null) {
                BoardStability.changedFraction(prior, signature)
            } else {
                null
            }
            if (signature != null) previous = signature
            afterPixels = analyzed.pixels
            afterFrame = next
            afterVision = nextVision
            val hudNow = readHud(analyzed.pixels, next)
            val seenBoard = Board.fromVision(nextVision.board)
            val settled = session.onSettle(
                FiveMoveSession.SettleSample(
                    nowMs = System.currentTimeMillis(),
                    boardHash = seenBoard.contentHash(),
                    diffFraction = fraction,
                    frameFresh = next.ageMs() <= settleAgeLimit(),
                    labelHash = seenBoard.labelHash(),
                    labelKeys = seenBoard.labelKeys(),
                    frameAgeMs = next.ageMs(),
                    cadenceMedianMs = CaptureService.managerOrNull()?.cadence?.medianIntervalMs() ?: 0L,
                    circlesBright = hudNow.circlesBright,
                    circlesClassifiable = hudNow.circlesClassifiable,
                    dimmed = dimmedFrame(analyzed.pixels, next, nextVision),
                    roiPlausible = roiLooksPlausible(nextVision),
                    visionPass = nextVision.validation.isPass,
                    unknownCount = nextVision.unknownCount,
                    ownUi = AutoPlaySession.frameGate.analyzerUiForeground,
                    a11yConnected = MatchMastersAccessibilityService.isConnected(),
                    overlayOutside = overlayOutside,
                    capturedAfterGesture = true,
                    frameSequence = next.sequence,
                    countBoardChange = com.match3vision.analyzer.hud.TurnGate.allowsVerification(hudNow),
                    swapOverlaps = swapOverlapsProbe(session, beforeVision, nextVision),
                ),
            )
            if (settled is FiveMoveSession.Decision.Hold) {
                AutoPlaySession.updateDiagnostics(
                    phase = "TARTÁS",
                    cycleReason = settled.reason,
                    gestureStatus = "SETTLING",
                )
                refreshBubbleUi()
            }
            if (settled is FiveMoveSession.Decision.Stop ||
                session.phase != FiveMoveSession.Phase.SETTLING
            ) {
                break
            }
        }
        } finally {
            fiveSettleInProgress = false
        }
        val number = session.gesturesDispatched.coerceAtLeast(1)
        val shot = afterFrame
        val seen = afterVision
        val record = session.movesSnapshot().lastOrNull()
        val afterView = if (seen != null) {
            GameMoveLog.view(
                board = Board.fromVision(seen.board),
                timestampMs = shot?.timestampMs ?: System.currentTimeMillis(),
                gridConfidence = seen.gridConfidence,
                boardConfidence = seen.boardConfidence,
                unknownCount = seen.unknownCount,
            )
        } else {
            null
        }
        closeGameLog(
            session = session,
            after = afterView,
            verification = record?.verification ?: session.stopReason,
            settleMs = record?.durationMs ?: 0L,
            elapsedMs = record?.let { (it.finishedAtMs - session.startedAtMs).coerceAtLeast(0L) } ?: 0L,
            userInterference = record?.userInterference == true,
        )
        if (shot != null) {
            writeMoveFrame(number, "after", afterPixels, shot, seen)
        }
        if (session.takeAutoSave()) {
            val saved = AutoCalibration.record(System.currentTimeMillis(), currentScreenKey().screenWidth, currentScreenKey().screenHeight)
            CoordinateSelfCheck.restore(saved)
            rememberCalibration(saved)
            session.noteCalibration(AutoCalibration.NOTE)
            showChipNotice(AutoCalibration.NOTE)
        }
        flushFiveMoveReport(session)
        refreshBubbleUi()
        return session.phase == FiveMoveSession.Phase.STOPPED
    }

    private fun settleAgeLimit(): Long {
        val cadence = CaptureService.managerOrNull()?.cadence?.medianIntervalMs() ?: 0L
        return maxOf(FiveMoveSession.SETTLE_FRAME_AGE_MS, cadence * 3L)
    }

    private fun dimmedFrame(
        pixels: IntArray?,
        frame: CaptureFrame,
        vision: com.match3vision.analyzer.vision.VisionResult?,
    ): Boolean {
        val roi = vision?.grid?.boardRoi
        return PlayGate.dimmed(
            pixels,
            frame.width,
            frame.height,
            roi?.left ?: 0,
            roi?.top ?: 0,
            roi?.right ?: frame.width,
            roi?.bottom ?: frame.height,
        )
    }

    private fun swapOverlapsProbe(
        session: FiveMoveSession,
        beforeVision: com.match3vision.analyzer.vision.VisionResult,
        afterVision: com.match3vision.analyzer.vision.VisionResult,
    ): Boolean {
        if (!session.autoProbe) return true
        val parsed = AutoCalibration.parseMove(session.openCells() ?: return false) ?: return false
        val changed = AutoCalibration.changedCells(
            Board.fromVision(beforeVision.board),
            Board.fromVision(afterVision.board),
        )
        return AutoCalibration.overlaps(changed, parsed[0], parsed[1], parsed[2], parsed[3])
    }

    private fun writeMoveFrame(
        number: Int,
        which: String,
        pixels: IntArray?,
        frame: CaptureFrame,
        vision: com.match3vision.analyzer.vision.VisionResult?,
    ) {
        val root = DiagnosticHistoryStore.directory() ?: return
        val folder = java.io.File(root, "five-move")
        folder.mkdirs()
        val roi = vision?.grid?.boardRoi
        val export = DiagnosticFrame.render(
            pixels = pixels,
            width = frame.width,
            height = frame.height,
            roiLeft = roi?.left ?: 0,
            roiTop = roi?.top ?: 0,
            roiRight = roi?.right ?: 0,
            roiBottom = roi?.bottom ?: 0,
            xBoundaries = vision?.grid?.xBoundaries,
            yBoundaries = vision?.grid?.yBoundaries,
        )
        val png = export.png ?: return
        java.io.File(folder, "move-%02d-%s.png".format(number, which)).writeBytes(png)
    }

    /** Left booster card, saved when the card is bright enough to tune ACTIVATE. */
    private fun writeActivateCrop(number: Int, pixels: IntArray?, frame: CaptureFrame): String? {
        if (pixels == null || frame.width <= 0 || frame.height <= 0) return null
        if (pixels.size < frame.width * frame.height) return null
        val left = (HudText.CARD_LEFT * frame.width / 1080).coerceIn(0, frame.width - 1)
        val right = (HudText.CARD_RIGHT * frame.width / 1080).coerceIn(left + 1, frame.width)
        val top = (HudText.CARD_TOP * frame.height / 2400).coerceIn(0, frame.height - 1)
        val bottom = (HudText.CARD_BOTTOM * frame.height / 2400).coerceIn(top + 1, frame.height)
        val w = right - left
        val h = bottom - top
        val crop = IntArray(w * h)
        for (y in 0 until h) {
            val src = (top + y) * frame.width + left
            for (x in 0 until w) crop[y * w + x] = pixels[src + x]
        }
        val root = DiagnosticHistoryStore.directory() ?: return null
        val folder = java.io.File(root, "five-move")
        folder.mkdirs()
        val name = "move-%02d-activate.png".format(number)
        java.io.File(folder, name).writeBytes(PngEncoder.encode(crop, w, h))
        return "five-move/$name"
    }

    private fun rememberDiagnostic(
        bundle: DiagnosticBundle,
        frameExport: DiagnosticFrame.Export,
        frame: CaptureFrame,
        pixels: IntArray?,
        roiLeft: Int,
        roiTop: Int,
        roiRight: Int,
        roiBottom: Int,
        xBoundaries: FloatArray?,
        yBoundaries: FloatArray?,
        plausibleRoi: Boolean,
    ) {
        val ownUi = AutoPlaySession.frameGate.analyzerUiForeground
        val calibrationBlocked = LiveFrameFilter.blocked(
            ownUi = false,
            calibrationVisible = calibrationOverlayVisible,
            calibrationDismissWallMs = calibrationDismissWallMs,
            frameTimestampMs = frame.timestampMs,
        )
        val exclude = LiveFrameFilter.blocked(
            ownUi = ownUi,
            calibrationVisible = calibrationOverlayVisible,
            calibrationDismissWallMs = calibrationDismissWallMs,
            frameTimestampMs = frame.timestampMs,
        )
        val tagged = bundle.copy(
            frameSource = LiveFrameFilter.frameSource(ownUi, calibrationBlocked),
        )
        val pastCollapse = collapseWallMs > 0L &&
            frame.timestampMs >= collapseWallMs + DiagnosticHistory.TRANSITION_SKIP_MS
        val pastCapture = captureStartWallMs > 0L &&
            frame.timestampMs >= captureStartWallMs + DiagnosticHistory.TRANSITION_SKIP_MS
        val startupFrame = DiagnosticHistoryStore.ringCount() == 0
        DiagnosticHistoryStore.noteRuntime(
            captureState = tagged.captureState,
            lastStopReason = AutoPlayTrace.lastStopReason,
        )
        val effect = DiagnosticHistoryStore.admitLive(
            bundle = tagged,
            frame = frameExport,
            ownUi = exclude,
            pastTransition = pastCollapse && pastCapture,
            plausibleRoi = plausibleRoi && !exclude,
            force = startupFrame,
        )
        if (!effect.becameBest || pixels == null || exclude) return
        val full = DiagnosticFrame.render(
            pixels = pixels,
            width = frame.width,
            height = frame.height,
            roiLeft = roiLeft,
            roiTop = roiTop,
            roiRight = roiRight,
            roiBottom = roiBottom,
            xBoundaries = xBoundaries,
            yBoundaries = yBoundaries,
            maxEdge = maxOf(frame.width, frame.height),
        )
        if (full.png != null) DiagnosticHistoryStore.replaceBestFrame(full)
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
        gestureStatus: String = "NOT CREATED",
        analysisOnly: Boolean = AutoPlaySession.controller.analysisOnly,
    ) {
        val exportGesture = if (analysisOnly) "NOT CREATED" else gestureStatus
        val exportDispatch = if (analysisOnly) "NOT STARTED" else dispatchStatus
        CaptureOverlayTrace.gestureStatus = exportGesture
        CaptureOverlayTrace.dispatchState = exportDispatch
        CaptureOverlayTrace.collapsed = collapsedForCapture
        CaptureOverlayTrace.collapseWallMs = collapseWallMs
        CaptureOverlayTrace.overlayRect = overlayRectText()
        CaptureOverlayTrace.analyzedFrameTimestampMs = frame.timestampMs
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
            dispatchStatus = exportDispatch,
            callbackOutcome = callbackOutcome,
            gestureStatus = exportGesture,
            analysisOnly = analysisOnly,
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
            val boardRoi = vision.grid.boardRoi
            rememberDiagnostic(
                bundle = bundle,
                frameExport = frameExport,
                frame = frame,
                pixels = pixels,
                roiLeft = boardRoi.left,
                roiTop = boardRoi.top,
                roiRight = boardRoi.right,
                roiBottom = boardRoi.bottom,
                xBoundaries = vision.grid.xBoundaries,
                yBoundaries = vision.grid.yBoundaries,
                plausibleRoi = roiLooksPlausible(vision),
            )
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
            rememberDiagnostic(
                bundle = bundle,
                frameExport = frameExport,
                frame = frame,
                pixels = null,
                roiLeft = 0,
                roiTop = 0,
                roiRight = 0,
                roiBottom = 0,
                xBoundaries = null,
                yBoundaries = null,
                plausibleRoi = false,
            )
        } catch (t: Throwable) {
            Timber.w(t, "diagnostic history write failed")
        }
    }

    private fun refreshBubbleUi() {
        val ctrl = AutoPlaySession.controller
        val five = ctrl.fiveMove
        fiveMoveBtn?.text = if (five.isActive || five.phase == FiveMoveSession.Phase.STOPPED) {
            five.label()
        } else {
            "10 LÉPÉS TESZT"
        }
        val owner = OwnerStatus.hu(
            when {
                five.phase == FiveMoveSession.Phase.STOPPED && five.stopReason.isNotBlank() -> five.stopReason
                chipNotice.isNotBlank() -> chipNotice
                fiveSeek?.isActive == true -> "HOLD — vision gates are not PASS"
                else -> ""
            },
        )
        val modeTitle = if (fiveSeek?.isActive == true) {
            "10 LÉPÉS …"
        } else {
            BubbleModeCaption.title(
                fiveActive = five.isActive,
                fiveLabel = "10 LÉPÉS ${five.verifiedCount}",
                selfCheckOk = selfCheckThisSession(),
            )
        }
        fun withOwner(base: String): String =
            if (owner.isBlank() || base.contains(owner)) base else base + "\n" + owner
        if (collapsedForCapture) {
            titleView?.text = modeTitle
            titleView?.textSize = 13f
            statusView?.maxLines = 3
            statusView?.textSize = 11f
            statusView?.text = withOwner(
                BubbleModeCaption.collapsedStatus(
                    fiveActive = five.isActive,
                    fiveLabel = five.label(),
                    chipNotice = calibrationNotice(),
                    fallback = modeTitle,
                ),
            )
        } else {
            titleView?.text = "Match3 Auto"
            titleView?.textSize = 11f
            statusView?.maxLines = 32
            statusView?.textSize = 8.5f
            val snap = AutoPlaySession.ui.value
            val warning = if (five.isActive) FiveMoveArm.DO_NOT_TOUCH + ".\n" else ""
            val noticeText = calibrationNotice()
            val notice = if (noticeText.isBlank()) "" else noticeText + "\n"
            statusView?.text = withOwner(
                warning + notice + snap.diagnostics.bubbleLines(compact = true) +
                    "\nEGY LÉPÉS: ${ctrl.singleMove.label()}",
            )
        }
        startBtn?.isEnabled = ctrl.mode != AutoPlayController.Mode.RUNNING &&
            ctrl.mode != AutoPlayController.Mode.STOPPED
        pauseBtn?.isEnabled = ctrl.mode == AutoPlayController.Mode.RUNNING
        AutoPlaySession.publish(bubbleVisible = bubbleView != null)
    }

    companion object {
        private const val BOOSTER_SETTLE_MS = 10_000L
        private const val COLLAPSED_CONTROL_WIDTH_DP = 220f
        private const val COLLAPSED_CONTROL_HEIGHT_DP = 128f

        /**
         * Kept so older logs can name the delay. TESZT ÉRINTÉS no longer waits
         * and no longer calls dispatchGesture.
         */
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
