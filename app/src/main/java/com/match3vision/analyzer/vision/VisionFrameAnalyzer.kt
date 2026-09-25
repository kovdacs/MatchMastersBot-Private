package com.match3vision.analyzer.vision

import com.match3vision.analyzer.capture.CaptureFrame
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.vision.parity.VisionResultExporter

/**
 * Runs the full vision pipeline on a real MediaProjection [CaptureFrame]
 * (letterbox → board → grid → cells → occlusion → color → shape → special →
 * reconcile → validation). Analyzer only — no input execution.
 */
class VisionFrameAnalyzer(
    private val pipeline: VisionPipeline = VisionPipeline(),
) {
    data class FrameAnalysis(
        val result: VisionResult,
        val imageWidth: Int,
        val imageHeight: Int,
        val letterboxRoi: ContentRoi?,
        val jsonExport: String,
        val debugSummary: DebugSummary,
    )

    data class DebugSummary(
        val roiText: String,
        val gridMethod: String,
        val gridConfidence: Float,
        val unknownCount: Int,
        val boardConfidence: Float,
        val gate: String,
        val holdReason: String?,
        val cellLabels: List<String>, // 49 short labels row-major
    )

    /**
     * Analyze pixel buffer (preferred for JVM tests and ViewModel off-main copy).
     */
    fun analyzePixels(
        pixels: IntArray,
        width: Int,
        height: Int,
        contentRoi: ContentRoi?,
    ): FrameAnalysis {
        val result = pipeline.analyze(pixels, width, height, contentRoi)
        return wrap(result, width, height, contentRoi)
    }

    /**
     * Analyze a captured frame. Uses the frame's bitmap pixels — not a fake image.
     * Caller must ensure bitmap is not recycled.
     */
    fun analyzeFrame(frame: CaptureFrame): FrameAnalysis {
        val bmp = frame.bitmap
        val w = frame.width
        val h = frame.height
        val pixels = IntArray(w * h)
        bmp.getPixels(pixels, 0, w, 0, 0, w, h)
        return analyzePixels(pixels, w, h, frame.contentRoi)
    }

    private fun wrap(
        result: VisionResult,
        width: Int,
        height: Int,
        letterbox: ContentRoi?,
    ): FrameAnalysis {
        val json = VisionResultExporter.toJson(result, width, height, letterbox)
        val gate = when (val v = result.validation) {
            is ValidationResult.Pass -> "PASS"
            is ValidationResult.Hold -> "HOLD"
        }
        val hold = (result.validation as? ValidationResult.Hold)?.reason
        val roi = result.grid.boardRoi
        val labels = buildList {
            for (r in 0 until GridGeometry.GRID_SIZE) {
                for (c in 0 until GridGeometry.GRID_SIZE) {
                    val cell = result.board.get(r, c)
                    add(
                        if (cell.isUnknown) "UNK"
                        else "${cell.color.name[0]}${cell.shape.name.take(1)}${cell.special.name.take(1)}"
                    )
                }
            }
        }
        return FrameAnalysis(
            result = result,
            imageWidth = width,
            imageHeight = height,
            letterboxRoi = letterbox,
            jsonExport = json,
            debugSummary = DebugSummary(
                roiText = "LTRB(${roi.left},${roi.top},${roi.right},${roi.bottom})",
                gridMethod = result.method.name,
                gridConfidence = result.gridConfidence,
                unknownCount = result.unknownCount,
                boardConfidence = result.boardConfidence,
                gate = gate,
                holdReason = hold,
                cellLabels = labels,
            ),
        )
    }
}
