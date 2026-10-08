package com.match3vision.analyzer.capture

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.util.DisplayMetrics
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns [ImageReader] + [VirtualDisplay] for MediaProjection screen capture.
 *
 * Emits the latest [CaptureFrame] on [latestFrame]. Analyzer-only: no input injection.
 */
class ScreenCaptureManager(
    private val context: Context,
    private var config: CaptureConfig = CaptureConfig(),
) {
    private val _latestFrame = MutableStateFlow<CaptureFrame?>(null)
    val latestFrame: StateFlow<CaptureFrame?> = _latestFrame.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var captureThread: HandlerThread? = null
    private var captureHandler: Handler? = null

    private val running = AtomicBoolean(false)
    private var lastEmitMs: Long = 0L
    private var frameSequence: Long = 0L
    val cadence: FrameCadence = FrameCadence()
    private var widthPx: Int = 0
    private var heightPx: Int = 0
    private var densityDpi: Int = 0

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            Timber.i("MediaProjection stopped by system")
            stop()
        }
    }

    fun updateConfig(newConfig: CaptureConfig) {
        config = newConfig
    }

    /**
     * Starts capture using an already-authorized [MediaProjection].
     * Caller must have obtained the projection from a user-approved intent result.
     */
    @Synchronized
    fun start(projection: MediaProjection) {
        if (running.get()) {
            Timber.w("ScreenCaptureManager already running")
            return
        }
        mediaProjection = projection
        projection.registerCallback(projectionCallback, null)

        val size = capturePixelSize()
        densityDpi = size.densityDpi
        widthPx = size.width
        heightPx = size.height
        if (widthPx <= 0 || heightPx <= 0) {
            Timber.e("Capture display size unknown (${size.source}) — not starting")
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
            mediaProjection = null
            return
        }

        val thread = HandlerThread("Match3Capture").also { it.start() }
        captureThread = thread
        captureHandler = Handler(thread.looper)

        val reader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, /*maxImages=*/3)
        imageReader = reader
        reader.setOnImageAvailableListener({ r -> onImageAvailable(r) }, captureHandler)

        virtualDisplay = projection.createVirtualDisplay(
            VIRTUAL_DISPLAY_NAME,
            widthPx,
            heightPx,
            densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            captureHandler,
        )

        running.set(true)
        _isCapturing.value = true
        Timber.i(
            "Capture started ${widthPx}x$heightPx dpi=$densityDpi source=${size.source} @ ${config.targetFps} fps",
        )
    }

    @Synchronized
    fun stop() {
        if (!running.getAndSet(false) && mediaProjection == null) {
            _isCapturing.value = false
            return
        }
        _isCapturing.value = false
        try {
            virtualDisplay?.release()
        } catch (t: Throwable) {
            Timber.w(t, "VirtualDisplay release")
        }
        virtualDisplay = null
        try {
            imageReader?.setOnImageAvailableListener(null, null)
            imageReader?.close()
        } catch (t: Throwable) {
            Timber.w(t, "ImageReader close")
        }
        imageReader = null
        try {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        } catch (t: Throwable) {
            Timber.w(t, "MediaProjection stop")
        }
        mediaProjection = null
        captureThread?.quitSafely()
        captureThread = null
        captureHandler = null
        Timber.i("Capture stopped")
    }

    /** Stops capture and clears the latest frame reference. */
    fun release() {
        stop()
        val prev = _latestFrame.value
        _latestFrame.value = null
        frameSequence = 0L
        cadence.reset()
        if (prev != null && !prev.bitmap.isRecycled) {
            try {
                prev.bitmap.recycle()
            } catch (_: Throwable) {
            }
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        var image: Image? = null
        try {
            image = reader.acquireLatestImage() ?: return
            val now = System.currentTimeMillis()
            val elapsed = com.match3vision.analyzer.input.FrameClock.tryElapsed()
            val interval = config.frameIntervalMs
            if (now - lastEmitMs < interval) {
                return
            }
            lastEmitMs = now

            val plane = image.planes[0]
            val buffer: ByteBuffer = plane.buffer
            val pixelStride = plane.pixelStride
            val rowStride = plane.rowStride
            val argb = RgbaBufferUnpack.unpack(
                buffer = buffer,
                width = widthPx,
                height = heightPx,
                rowStride = rowStride,
                pixelStride = pixelStride,
            )
            if (argb == null) {
                Timber.e(
                    "ImageReader plane rejected width=$widthPx height=$heightPx " +
                        "rowStride=$rowStride pixelStride=$pixelStride capacity=${buffer.capacity()}",
                )
                return
            }
            val cropped = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
            cropped.setPixels(argb, 0, widthPx, 0, 0, widthPx, heightPx)

            val roi = LetterboxDetector.detect(
                cropped,
                lumaThreshold = config.letterboxLumaThreshold,
                minBarRatio = config.letterboxBarMinRatio,
            )

            val prev = _latestFrame.value
            val frame = CaptureFrame(
                width = widthPx,
                height = heightPx,
                timestampMs = now,
                bitmap = cropped,
                contentRoi = roi,
                sequence = ++frameSequence,
                elapsedRealtimeMs = elapsed,
            )
            if (elapsed > 0L) cadence.record(elapsed)
            _latestFrame.value = frame
            // Recycle previous bitmap after swap (avoid MediaProjection leak).
            if (prev != null && prev.bitmap !== cropped && !prev.bitmap.isRecycled) {
                try {
                    prev.bitmap.recycle()
                } catch (t: Throwable) {
                    Timber.w(t, "prev bitmap recycle")
                }
            }
        } catch (t: Throwable) {
            Timber.e(t, "onImageAvailable failed")
        } finally {
            try {
                image?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Virtual-display pixels. Prefer maximum window bounds (same space as the
     * touch test / dispatchGesture screen) and fall back to real metrics.
     */
    private fun capturePixelSize(): CaptureDisplaySize.Px {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
        var maxW = 0
        var maxH = 0
        if (wm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val b = wm.maximumWindowMetrics.bounds
            maxW = b.width()
            maxH = b.height()
        }
        val real = DisplayMetrics()
        if (wm != null) {
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(real)
        } else {
            real.setTo(Resources.getSystem().displayMetrics)
        }
        val density = if (real.densityDpi > 0) {
            real.densityDpi
        } else {
            Resources.getSystem().displayMetrics.densityDpi
        }
        return CaptureDisplaySize.choose(
            maximumWindowWidth = maxW,
            maximumWindowHeight = maxH,
            realWidth = real.widthPixels,
            realHeight = real.heightPixels,
            densityDpi = density,
        )
    }

    companion object {
        const val VIRTUAL_DISPLAY_NAME = "Match3VisionAnalyzer"
    }
}
