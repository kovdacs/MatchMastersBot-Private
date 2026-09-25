package com.match3vision.analyzer

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import com.match3vision.analyzer.capture.CaptureService
import com.match3vision.analyzer.ui.AnalyzerScreen
import com.match3vision.analyzer.ui.AnalyzerViewModel
import com.match3vision.analyzer.ui.theme.Match3VisionTheme
import timber.log.Timber

/**
 * Hosts MediaProjection permission flow and the Compose analyzer UI.
 * Does not inject input or bind AccessibilityService.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AnalyzerViewModel by viewModels()

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            CaptureService.start(this, result.resultCode, result.data!!)
            viewModel.onCaptureServiceStarted()
            Timber.i("MediaProjection granted — CaptureService starting")
        } else {
            viewModel.onCapturePermissionDenied()
            Timber.w("MediaProjection denied or cancelled")
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Timber.d("POST_NOTIFICATIONS granted=$granted")
        // Proceed to projection request regardless; notification is best-effort on API 33+.
        launchProjectionPermission()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Match3VisionTheme {
                AnalyzerScreen(
                    viewModel = viewModel,
                    onStartCapture = { requestCapturePermission() },
                    onStopCapture = {
                        CaptureService.stop(this)
                        viewModel.onCaptureServiceStopped()
                    },
                )
            }
        }
    }

    private fun requestCapturePermission() {
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
        launchProjectionPermission()
    }

    private fun launchProjectionPermission() {
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projectionLauncher.launch(mpm.createScreenCaptureIntent())
    }
}
