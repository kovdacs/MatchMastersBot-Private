package com.match3vision.analyzer

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.match3vision.analyzer.capture.CaptureConsent
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.input.MatchMastersAccessibilityService
import com.match3vision.analyzer.overlay.AutoPlaySession
import com.match3vision.analyzer.overlay.FloatingBubbleService
import com.match3vision.analyzer.ui.AnalyzerScreen
import com.match3vision.analyzer.ui.AnalyzerViewModel
import com.match3vision.analyzer.ui.NotificationPermissionGate
import com.match3vision.analyzer.ui.theme.Match3VisionTheme
import timber.log.Timber

/**
 * Hosts MediaProjection + overlay + accessibility prompts and the Compose UI.
 *
 * Flow: one main INDÍTÁS → permissions → CaptureService → Floating Bubble.
 * Runtime accessibility plus a recorded coordinate self-check arms play.
 * Accessibility off still starts diagnostic capture, analysis, and export.
 * That path does not enable input and does not dispatch.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AnalyzerViewModel by viewModels()
    private val notificationGate = NotificationPermissionGate()

    private var pendingAfterOverlay = false
    private var pendingAfterA11y = false
    /** True while a main-screen INDÍTÁS setup is in progress (auto-start loop when ready). */
    private var pendingAutoStartLoop = false

    private val minimizeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == FloatingBubbleService.ACTION_MINIMIZE_ANALYZER) {
                moveTaskToBack(true)
            }
        }
    }

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(this, result.resultCode, result.data!!)
            viewModel.onCaptureServiceStarted()
            AutoPlaySession.publish(captureReady = CaptureService.managerOrNull() != null)
            FloatingBubbleService.start(this)
            AutoPlaySession.publish(bubbleVisible = true)
            Timber.i("MediaProjection granted — CaptureService + bubble starting")
            // Defer slightly so FloatingBubbleService.onCreate / beginNewSession finish.
            finishStartChainAfterCaptureAndBubble()
        } else {
            pendingAutoStartLoop = false
            viewModel.onCapturePermissionDenied()
            AutoPlaySession.publish(captureReady = false, statusText = "Képernyőrögzítés elutasítva")
            Timber.w("MediaProjection denied or cancelled")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val status = notificationGate.onResult(granted)
        viewModel.setNotificationStatus(status)
        Timber.d("POST_NOTIFICATIONS granted=$granted")
        continueStartFlow()
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val ok = Settings.canDrawOverlays(this)
        AutoPlaySession.publish(overlayReady = ok)
        if (ok) {
            continueStartFlow()
        } else {
            pendingAutoStartLoop = false
            Toast.makeText(this, getString(R.string.overlay_permission_rationale), Toast.LENGTH_LONG).show()
            viewModel.onCapturePermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(FloatingBubbleService.ACTION_MINIMIZE_ANALYZER)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(minimizeReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(minimizeReceiver, filter)
        }
        refreshPermissionFlags()
        setContent {
            Match3VisionTheme {
                AnalyzerScreen(
                    viewModel = viewModel,
                    onStartCapture = { beginAutoPlaySetup() },
                    onStopCapture = { stopEverything() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                    onRetryNotification = { retryNotificationPermission() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAnalyzerUiForeground(true)
        refreshPermissionFlags()
        if (pendingAfterA11y) {
            pendingAfterA11y = false
            if (MatchMastersAccessibilityService.isConnected() || isAccessibilityEnabledInSettings()) {
                continueStartFlow()
            } else {
                pendingAutoStartLoop = false
                Toast.makeText(this, getString(R.string.a11y_permission_rationale), Toast.LENGTH_LONG).show()
                AutoPlaySession.publish(
                    a11yReady = false,
                    statusText = "ACCESSIBILITY: DISCONNECTED",
                )
                AutoPlaySession.updateDiagnostics(
                    a11yConnected = false,
                    stopReason = "ACCESSIBILITY: DISCONNECTED",
                )
            }
        }
        if (pendingAfterOverlay) {
            pendingAfterOverlay = false
            if (Settings.canDrawOverlays(this)) {
                continueStartFlow()
            }
        }
    }

    override fun onPause() {
        viewModel.onAnalyzerUiForeground(false)
        super.onPause()
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(minimizeReceiver)
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    /** Big INDÍTÁS — request only missing permissions, then capture + bubble + auto loop. */
    private fun beginAutoPlaySetup() {
        pendingAutoStartLoop = true
        viewModel.onStartRequested()
        AutoPlaySession.publish(statusText = "Engedélyek…")
        continueStartFlow()
    }

    private fun continueStartFlow() {
        refreshPermissionFlags()
        // 1) Notifications (API 33+). Denial is remembered: capture continues
        // without asking again until the owner taps the retry button.
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = notificationsGranted()
            if (notificationGate.shouldRequest(permissionRequired = true, granted = granted)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
            if (!granted) {
                viewModel.setNotificationStatus(NotificationPermissionGate.DENIED)
            }
        }
        // 2) Overlay for floating bubble
        if (!Settings.canDrawOverlays(this)) {
            pendingAfterOverlay = true
            openOverlaySettings()
            return
        }
        AutoPlaySession.publish(overlayReady = true)
        // Accessibility is required for play, not for diagnostic capture.
        val connected = MatchMastersAccessibilityService.isConnected()
        val settingsOn = isAccessibilityEnabledInSettings()
        AutoPlaySession.publish(
            a11yReady = connected,
            a11ySettingsEnabled = settingsOn,
        )
        // 4) MediaProjection
        if (CaptureService.managerOrNull() != null) {
            if (!FloatingBubbleService.isRunning()) {
                FloatingBubbleService.start(this)
            }
            viewModel.onCaptureServiceStarted()
            AutoPlaySession.publish(captureReady = true, bubbleVisible = true)
            finishStartChainAfterCaptureAndBubble()
            return
        }
        launchProjectionPermission()
    }

    /**
     * After CaptureService + bubble are up, start the loop.
     * The bubble tries play first. If accessibility or the self-check
     * refuses play, it falls back to diagnostic analysis and export.
     */
    private fun finishStartChainAfterCaptureAndBubble() {
        val wantStart = pendingAutoStartLoop
        pendingAutoStartLoop = false
        val connected = MatchMastersAccessibilityService.isConnected()
        val settingsOn = isAccessibilityEnabledInSettings()
        AutoPlaySession.publish(
            captureReady = CaptureService.managerOrNull() != null,
            bubbleVisible = true,
            a11yReady = connected,
            a11ySettingsEnabled = settingsOn,
            overlayReady = Settings.canDrawOverlays(this),
        )
        AutoPlaySession.updateDiagnostics(a11yConnected = connected)

        if (!wantStart) {
            moveTaskToBack(true)
            return
        }

        val startNote = if (connected) {
            "Auto indul — nyisd meg a Match Masters-t (buborék kontroll / SZÜNET / STOP)"
        } else {
            "diagnosztika — kisegítő KI, elemzés és export, nincs érintés"
        }
        if (!connected) {
            AutoPlaySession.publish(statusText = startNote, a11yReady = false)
            AutoPlaySession.updateDiagnostics(
                a11yConnected = false,
                gestureStatus = "NOT CREATED",
                inputBlockReason = "diagnostic analysis only",
            )
            Timber.i("Main INDÍTÁS diagnostic: a11y off (settingsOn=%s)", settingsOn)
        }

        Handler(Looper.getMainLooper()).postDelayed({
            FloatingBubbleService.requestStartLoop(this)
            Toast.makeText(this, startNote, Toast.LENGTH_LONG).show()
            Timber.i("Main INDÍTÁS → ACTION_START_LOOP a11y=%s", connected)
            moveTaskToBack(true)
        }, 350L)
    }

    private fun stopEverything() {
        pendingAutoStartLoop = false
        FloatingBubbleService.stop(this)
        CaptureService.stop(this)
        viewModel.onCaptureServiceStopped()
        AutoPlaySession.endSession()
        AutoPlaySession.publish(captureReady = false, bubbleVisible = false, statusText = "Leállítva")
    }

    private fun launchProjectionPermission() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (
            CaptureConsent.modeForSdk(Build.VERSION.SDK_INT) ==
            CaptureConsent.Mode.ENTIRE_DEFAULT_DISPLAY
        ) {
            // API 34+ default chooser can capture only this app. After the
            // analyzer is backgrounded that is not the game, so Vision HOLDs
            // and the loop never dispatches.
            val config = MediaProjectionConfig.createConfigForDefaultDisplay()
            mpm.createScreenCaptureIntent(config)
        } else {
            mpm.createScreenCaptureIntent()
        }
        projectionLauncher.launch(intent)
    }

    private fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        )
        overlayPermissionLauncher.launch(intent)
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun notificationsGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    /** One more POST_NOTIFICATIONS prompt. Does not restart the start loop. */
    private fun retryNotificationPermission() {
        notificationGate.allowRetry()
        if (Build.VERSION.SDK_INT < 33) return
        if (notificationsGranted()) {
            viewModel.setNotificationStatus(NotificationPermissionGate.GRANTED)
            return
        }
        if (notificationGate.shouldRequest(permissionRequired = true, granted = false)) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refreshPermissionFlags() {
        val connected = MatchMastersAccessibilityService.isConnected()
        val settingsOn = isAccessibilityEnabledInSettings()
        AutoPlaySession.publish(
            overlayReady = Settings.canDrawOverlays(this),
            a11yReady = connected,
            a11ySettingsEnabled = settingsOn,
            captureReady = CaptureService.managerOrNull() != null,
        )
        AutoPlaySession.updateDiagnostics(a11yConnected = connected)
    }

    /** Settings listing only — does NOT mean the service is connected. */
    private fun isAccessibilityEnabledInSettings(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = "$packageName/${MatchMastersAccessibilityService::class.java.canonicalName}"
            enabled.split(':').any {
                it.equals(expected, ignoreCase = true) ||
                    it.contains("MatchMastersAccessibilityService", ignoreCase = true)
            }
        } catch (_: Throwable) {
            false
        }
    }
}
