package com.match3vision.analyzer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.input.MatchMastersAccessibilityService
import com.match3vision.analyzer.overlay.AutoPlaySession
import com.match3vision.analyzer.overlay.FloatingBubbleService
import com.match3vision.analyzer.ui.AnalyzerScreen
import com.match3vision.analyzer.ui.AnalyzerViewModel
import com.match3vision.analyzer.ui.theme.Match3VisionTheme
import timber.log.Timber

/**
 * Hosts MediaProjection + overlay + accessibility prompts and the Compose UI.
 * Flow: INDÍTÁS → minimal permission prompts → CaptureService + floating bubble.
 * Auto-play loop starts only when the user taps INDÍTÁS on the bubble.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AnalyzerViewModel by viewModels()

    private var pendingAfterOverlay = false
    private var pendingAfterA11y = false

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(this, result.resultCode, result.data!!)
            viewModel.onCaptureServiceStarted()
            AutoPlaySession.publish(captureReady = true)
            FloatingBubbleService.start(this)
            AutoPlaySession.publish(bubbleVisible = true)
            Toast.makeText(
                this,
                "Buborék kész — nyisd meg a Match Masters-t, majd buborék INDÍTÁS",
                Toast.LENGTH_LONG,
            ).show()
            Timber.i("MediaProjection granted — CaptureService + bubble starting")
        } else {
            viewModel.onCapturePermissionDenied()
            AutoPlaySession.publish(captureReady = false, statusText = "Képernyőrögzítés elutasítva")
            Timber.w("MediaProjection denied or cancelled")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
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
            Toast.makeText(this, getString(R.string.overlay_permission_rationale), Toast.LENGTH_LONG).show()
            viewModel.onCapturePermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshPermissionFlags()
        setContent {
            Match3VisionTheme {
                AnalyzerScreen(
                    viewModel = viewModel,
                    onStartCapture = { beginAutoPlaySetup() },
                    onStopCapture = { stopEverything() },
                    onOpenAccessibilitySettings = { openAccessibilitySettings() },
                    onOpenOverlaySettings = { openOverlaySettings() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onAnalyzerUiForeground(true)
        refreshPermissionFlags()
        // If user returned from a11y settings during setup, continue.
        if (pendingAfterA11y) {
            pendingAfterA11y = false
            if (MatchMastersAccessibilityService.isConnected() || isAccessibilityEnabledInSettings()) {
                continueStartFlow()
            } else {
                Toast.makeText(this, getString(R.string.a11y_permission_rationale), Toast.LENGTH_LONG).show()
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

    /** Big INDÍTÁS — request only missing permissions, then capture + bubble. */
    private fun beginAutoPlaySetup() {
        viewModel.onStartRequested()
        AutoPlaySession.publish(statusText = "Engedélyek…")
        continueStartFlow()
    }

    private fun continueStartFlow() {
        refreshPermissionFlags()
        // 1) Notifications (API 33+)
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        // 2) Overlay for floating bubble
        if (!Settings.canDrawOverlays(this)) {
            pendingAfterOverlay = true
            openOverlaySettings()
            return
        }
        AutoPlaySession.publish(overlayReady = true)
        // 3) Accessibility for gestures
        if (!MatchMastersAccessibilityService.isConnected() && !isAccessibilityEnabledInSettings()) {
            pendingAfterA11y = true
            Toast.makeText(this, getString(R.string.a11y_permission_rationale), Toast.LENGTH_LONG).show()
            openAccessibilitySettings()
            return
        }
        AutoPlaySession.publish(a11yReady = true)
        // 4) MediaProjection
        if (CaptureService.managerOrNull() != null) {
            // Already capturing — just ensure bubble.
            if (!FloatingBubbleService.isRunning()) {
                FloatingBubbleService.start(this)
            }
            viewModel.onCaptureServiceStarted()
            AutoPlaySession.publish(captureReady = true, bubbleVisible = true)
            Toast.makeText(
                this,
                "Buborék kész — nyisd meg a Match Masters-t, majd buborék INDÍTÁS",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        launchProjectionPermission()
    }

    private fun stopEverything() {
        FloatingBubbleService.stop(this)
        CaptureService.stop(this)
        viewModel.onCaptureServiceStopped()
        AutoPlaySession.endSession()
        AutoPlaySession.publish(captureReady = false, bubbleVisible = false, statusText = "Leállítva")
    }

    private fun launchProjectionPermission() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
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

    private fun refreshPermissionFlags() {
        AutoPlaySession.publish(
            overlayReady = Settings.canDrawOverlays(this),
            a11yReady = MatchMastersAccessibilityService.isConnected() ||
                isAccessibilityEnabledInSettings(),
            captureReady = CaptureService.managerOrNull() != null,
        )
    }

    /** Settings may show enabled before onServiceConnected; treat listed service as ready enough to proceed. */
    private fun isAccessibilityEnabledInSettings(): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val expected = "$packageName/${MatchMastersAccessibilityService::class.java.canonicalName}"
            enabled.split(':').any { it.equals(expected, ignoreCase = true) ||
                it.contains("MatchMastersAccessibilityService", ignoreCase = true) }
        } catch (_: Throwable) {
            false
        }
    }
}
