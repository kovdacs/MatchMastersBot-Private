package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.FrameCadence
import com.match3vision.analyzer.capture.ScreenMeasurement
import com.match3vision.analyzer.vision.SpecialCropAudit
import com.match3vision.analyzer.vision.VisionResult
import java.io.File

/**
 * Exportable snapshot of one loop observation.
 *
 * Built from values the caller already measured. It does not dispatch a
 * gesture and it does not invent a PASS or a successful move.
 */
data class DiagnosticBundle(
    val appVersion: String,
    val versionCode: Int,
    val sourceCommit: String,
    val diagnosticTimestampMs: Long,
    val failureClass: String,
    val visionGate: String,
    val visionReason: String,
    val gridConfidence: Float,
    val boardConfidence: Float,
    val unknownCount: Int,
    val cellLabels: List<String>,
    val boardRoi: String,
    val frameWidth: Int,
    val frameHeight: Int,
    val screenWidth: Int,
    val screenHeight: Int,
    val screenSource: String,
    val screenRotation: Int,
    val coordinateAlignmentProven: Boolean,
    val coordinateReason: String,
    val frameSequence: Long,
    val captureTimestampMs: Long,
    val frameAgeMs: Long,
    val frameElapsedMs: Long,
    val cadence: String,
    val accessibilityConnected: Boolean,
    val gestureCapability: String,
    val captureState: String,
    val moveAnalysis: String,
    val selectedMove: String,
    val dispatchStatus: String,
    val callbackOutcome: String,
    val verificationStatus: String,
    val verificationReason: String,
    val gridMethod: String,
    val relVarX: String,
    val relVarY: String,
    val projectionPeakCountX: String,
    val projectionPeakCountY: String,
    val meanLuminance: String,
    val blackFrame: String,
    val visionDecisions: String,
    val dispatchDecision: String,
    val finalSafetyDecision: String,
    val frameExportStatus: String,
    val frameExportReason: String,
    val gridBoundaries: String,
    val frameTimestampMeaning: String = VerifyTiming.FRAME_TIMESTAMP_MEANING,
    /** Always false. Exporting this bundle does not grant a vision or verify PASS. */
    val diagnosticInfluencesGate: Boolean = false,
    /** Latest TESZT ÉRINTÉS self-check. Recording it does not prove alignment. */
    val coordinateSelfCheck: String = "not recorded",
    val overlayCollapsed: Boolean = false,
    val overlayRect: String = "unknown",
    val overlayGateResult: String = "not evaluated",
    val skippedFrameCount: Int = 0,
    val collapseWallMs: Long = 0L,
    val analyzedFrameTimestampMs: Long = 0L,
    val latticeScore: String = "not measured",
    val latticeStd: String = "not measured",
    val playfieldSnap: String = "not measured",
    val latticeRoiUsed: String = "not measured",
    val latticeCandidate: String = "not measured",
    val roiAspect: String = "not measured",
    val roiTopFraction: String = "not measured",
    val roiBottomMarginPx: String = "not measured",
    val pitchX: String = "not measured",
    val pitchY: String = "not measured",
    val specialCropSizes: String = "none",
    val specialRejectedCropCount: Int = 0,
    val specialRejectedCropOrigin: String = "none",
    val gestureStatus: String = "NOT CREATED",
    /** Diagnostic capture. True means this cycle did not dispatch. */
    val analysisOnly: Boolean = false,
    /** OverlayColumnMask.describe() for this frame. */
    val overlayMaskColumns: String = "not measured",
    /** True when a 0-size update kept the previous positive frame size. */
    val frameSizeRetained: Boolean = false,
    /** max(column pitch) / min(column pitch). */
    val columnPitchMaxMinRatio: String = "not measured",
    /** max(row pitch) / min(row pitch). */
    val rowPitchMaxMinRatio: String = "not measured",
    val simulated: Boolean,
    /** `in_game` or `own_ui`. Own-app frames stay out of the ring. */
    val frameSource: String = SOURCE_IN_GAME,
) {
    /** One ring row. This is the checkable per-cycle record. */
    fun cycleLine(): String = listOf(
        "seq=$frameSequence",
        "ts=$captureTimestampMs",
        "roi=$boardRoi",
        "grid=${"%.4f".format(gridConfidence)}",
        "unk=$unknownCount",
        "latticeScore=$latticeScore",
        "latticeRoiUsed=$latticeRoiUsed",
        "overlayGate=$overlayGateResult",
        "frameAge=$frameAgeMs",
        "frame=${frameWidth}x$frameHeight",
        "frameExport=$frameExportStatus",
    ).joinToString(" ")

    fun toJson(): String = buildString {
        append("{\n")
        field("appVersion", appVersion)
        field("versionCode", versionCode.toString(), numeric = true)
        field("sourceCommit", sourceCommit)
        field("diagnosticTimestampMs", diagnosticTimestampMs.toString(), numeric = true)
        field("failureClass", failureClass)
        field("visionGate", visionGate)
        field("visionReason", visionReason)
        field("gridConfidence", gridConfidence.toString(), numeric = true)
        field("boardConfidence", boardConfidence.toString(), numeric = true)
        field("unknownCount", unknownCount.toString(), numeric = true)
        field("cellLabels", cellLabels.joinToString(prefix = "[", postfix = "]") { jsonString(it) }, raw = true)
        field("boardRoi", boardRoi)
        field("frameWidth", frameWidth.toString(), numeric = true)
        field("frameHeight", frameHeight.toString(), numeric = true)
        field("screenWidth", screenWidth.toString(), numeric = true)
        field("screenHeight", screenHeight.toString(), numeric = true)
        field("screenSource", screenSource)
        field("screenRotation", screenRotation.toString(), numeric = true)
        field("coordinateAlignmentProven", coordinateAlignmentProven.toString(), raw = true)
        field("coordinateReason", coordinateReason)
        field("frameSequence", frameSequence.toString(), numeric = true)
        field("captureTimestampMs", captureTimestampMs.toString(), numeric = true)
        field("frameAgeMs", frameAgeMs.toString(), numeric = true)
        field("frameElapsedMs", frameElapsedMs.toString(), numeric = true)
        field("cadence", cadence)
        field("accessibilityConnected", accessibilityConnected.toString(), raw = true)
        field("gestureCapability", gestureCapability)
        field("captureState", captureState)
        field("moveAnalysis", moveAnalysis)
        field("selectedMove", selectedMove)
        field("dispatchStatus", dispatchStatus)
        field("callbackOutcome", callbackOutcome)
        field("verificationStatus", verificationStatus)
        field("verificationReason", verificationReason)
        field("gridMethod", gridMethod)
        field("relVarX", relVarX)
        field("relVarY", relVarY)
        field("projectionPeakCountX", projectionPeakCountX)
        field("projectionPeakCountY", projectionPeakCountY)
        field("meanLuminance", meanLuminance)
        field("blackFrame", blackFrame)
        field("visionDecisions", visionDecisions)
        field("dispatchDecision", dispatchDecision)
        field("finalSafetyDecision", finalSafetyDecision)
        field("frameExportStatus", frameExportStatus)
        field("frameExportReason", frameExportReason)
        field("gridBoundaries", gridBoundaries)
        field("frameTimestampMeaning", frameTimestampMeaning)
        field("diagnosticInfluencesGate", diagnosticInfluencesGate.toString(), raw = true)
        field("coordinateSelfCheck", coordinateSelfCheck)
        field("overlayCollapsed", overlayCollapsed.toString(), raw = true)
        field("overlayRect", overlayRect)
        field("overlayGateResult", overlayGateResult)
        field("skippedFrameCount", skippedFrameCount.toString(), numeric = true)
        field("collapseWallMs", collapseWallMs.toString(), numeric = true)
        field("analyzedFrameTimestampMs", analyzedFrameTimestampMs.toString(), numeric = true)
        field("latticeScore", latticeScore)
        field("latticeStd", latticeStd)
        field("playfieldSnap", playfieldSnap)
        field("latticeRoiUsed", latticeRoiUsed)
        field("latticeCandidate", latticeCandidate)
        field("roiAspect", roiAspect)
        field("roiTopFraction", roiTopFraction)
        field("roiBottomMarginPx", roiBottomMarginPx)
        field("pitchX", pitchX)
        field("pitchY", pitchY)
        field("specialCropSizes", specialCropSizes)
        field("specialRejectedCropCount", specialRejectedCropCount.toString(), numeric = true)
        field("specialRejectedCropOrigin", specialRejectedCropOrigin)
        field("gestureStatus", gestureStatus)
        field("analysisOnly", analysisOnly.toString(), raw = true)
        field("overlayMaskColumns", overlayMaskColumns)
        field("frameSizeRetained", frameSizeRetained.toString(), raw = true)
        field("columnPitchMaxMinRatio", columnPitchMaxMinRatio)
        field("rowPitchMaxMinRatio", rowPitchMaxMinRatio)
        field("callbackOutcomeIsDispatchCopy", (callbackOutcome == dispatchStatus).toString(), raw = true)
        field("simulated", simulated.toString(), raw = true)
        field("frameSource", frameSource, last = true)
        append("}\n")
    }

    fun bestLine(): String = listOf(
        "seq=$frameSequence",
        "ts=$captureTimestampMs",
        "roi=$boardRoi",
        "latticeScore=$latticeScore",
        "latticeRoiUsed=$latticeRoiUsed",
        "playfieldSnap=$playfieldSnap",
        "unk=$unknownCount",
        "frame=${frameWidth}x$frameHeight",
        "frameSource=$frameSource",
    ).joinToString(" ")

    private fun StringBuilder.field(
        name: String,
        value: String,
        numeric: Boolean = false,
        raw: Boolean = false,
        last: Boolean = false,
    ) {
        append("  \"").append(name).append("\": ")
        when {
            raw || numeric -> append(value)
            else -> append(jsonString(value))
        }
        if (!last) append(",")
        append("\n")
    }

    companion object {
        const val CLASS_VISION = "VISION"
        const val CLASS_CAPTURE = "CAPTURE"
        const val CLASS_ACCESSIBILITY = "ACCESSIBILITY"
        const val CLASS_COORDINATE = "COORDINATE"
        const val CLASS_DISPATCH = "DISPATCH"
        const val CLASS_VERIFICATION = "VERIFICATION"
        const val CLASS_NONE = "NONE"
        const val CLASS_CAPTURE_INVALID = "CAPTURE_INVALID"
        const val SOURCE_IN_GAME = "in_game"
        const val SOURCE_OWN_UI = "own_ui"
        /** Dimmed calibration layer. Excluded from the ring the same way as [SOURCE_OWN_UI]. */
        const val SOURCE_CALIBRATION = "calibration_overlay"

        fun classify(
            captureOn: Boolean,
            hasFrame: Boolean,
            accessibilityConnected: Boolean,
            coordinateRefused: Boolean,
            visionPass: Boolean,
            dispatchFailed: Boolean,
            verificationFailed: Boolean,
        ): String = when {
            !captureOn || !hasFrame -> CLASS_CAPTURE
            !accessibilityConnected -> CLASS_ACCESSIBILITY
            coordinateRefused -> CLASS_COORDINATE
            !visionPass -> CLASS_VISION
            dispatchFailed -> CLASS_DISPATCH
            verificationFailed -> CLASS_VERIFICATION
            else -> CLASS_NONE
        }

        fun fromObservation(
            appVersion: String,
            versionCode: Int,
            sourceCommit: String,
            diagnosticTimestampMs: Long,
            vision: VisionResult?,
            screen: ScreenMeasurement?,
            frameWidth: Int,
            frameHeight: Int,
            frameSequence: Long,
            captureTimestampMs: Long,
            frameAgeMs: Long,
            frameElapsedMs: Long,
            cadence: FrameCadence?,
            accessibilityConnected: Boolean,
            gestureCapability: String,
            captureOn: Boolean,
            hasFrame: Boolean,
            moveAnalysis: String,
            selectedMove: String,
            coordinateReason: String,
            coordinateRefused: Boolean,
            dispatchStatus: String,
            callbackOutcome: String,
            verificationStatus: String,
            verificationReason: String,
            simulated: Boolean,
            meanLuminance: String = "not measured",
            blackFrame: String = "not measured — frame pixels were not supplied",
            frameExportStatus: String = DiagnosticFrame.STATUS_NOT_EXPORTED,
            frameExportReason: String = "frame pixels were not supplied",
            captureInvalidReason: String? = null,
            gestureStatus: String = CaptureOverlayTrace.gestureStatus,
            analysisOnly: Boolean = false,
        ): DiagnosticBundle {
            val captureInvalid = !captureInvalidReason.isNullOrBlank()
            val gate = when {
                captureInvalid -> "HOLD"
                vision == null -> "NONE"
                vision.validation.isPass -> "PASS"
                else -> "HOLD"
            }
            val reason = when {
                captureInvalid -> captureInvalidReason!!
                vision != null -> vision.validation.let { v ->
                    if (v.isPass) "PASS" else (v as? com.match3vision.analyzer.vision.ValidationResult.Hold)?.reason ?: "HOLD"
                }
                else -> "no vision result"
            }
            val labels = if (vision == null) {
                emptyList()
            } else {
                buildList {
                    for (r in 0 until com.match3vision.analyzer.vision.GridGeometry.GRID_SIZE) {
                        for (c in 0 until com.match3vision.analyzer.vision.GridGeometry.GRID_SIZE) {
                            val cell = vision.board.get(r, c)
                            add(if (cell.isUnknown) "UNK" else cell.color.name)
                        }
                    }
                }
            }
            val roi = vision?.grid?.boardRoi?.let {
                "LTRB(${it.left},${it.top},${it.right},${it.bottom})"
            } ?: "—"
            val visionPass = !captureInvalid && vision?.validation?.isPass == true
            val failure = if (captureInvalid) {
                CLASS_CAPTURE_INVALID
            } else classify(
                captureOn = captureOn,
                hasFrame = hasFrame,
                accessibilityConnected = accessibilityConnected,
                coordinateRefused = coordinateRefused,
                visionPass = visionPass,
                dispatchFailed = dispatchStatus.equals("FAILED", ignoreCase = true),
                verificationFailed = verificationStatus.equals(VerificationPolicy.FAILED, ignoreCase = true),
            )
            val moveText = if (!visionPass) "none" else selectedMove.ifBlank { "none" }
            val diag = vision?.diagnostics ?: emptyMap()
            val gridMethod = vision?.method?.name ?: "not measured"
            val relVarX = diag["projRelVarX"] ?: "not measured"
            val relVarY = diag["projRelVarY"] ?: "not measured"
            val peakX = diag["projPeakCountX"] ?: "not measured"
            val peakY = diag["projPeakCountY"] ?: "not measured"
            val boundaries = vision?.grid?.let { grid ->
                "x=" + grid.xBoundaries.joinToString(",") { "%.1f".format(it) } +
                    ";y=" + grid.yBoundaries.joinToString(",") { "%.1f".format(it) }
            } ?: "not measured"
            val decisions = if (captureInvalid) {
                "not run — CAPTURE_INVALID (VisionPipeline was not called)"
            } else listOf(
                "method=$gridMethod",
                "validation=$reason",
                "gridRecover=${diag["gridRecover"] ?: "not measured"}",
                "fallback=${diag["fallback"] ?: "none"}",
                "projectionRejected=${diag["projectionRejected"] ?: "none"}",
            ).joinToString("; ")
            val recordedVerify = if (verificationStatus.equals(VerificationPolicy.SUCCESS, ignoreCase = true)) {
                "REFUSED — diagnostic does not record VERIFY SUCCESS"
            } else {
                verificationStatus.ifBlank { VerificationPolicy.PENDING }
            }
            val recordedDispatch = when {
                analysisOnly -> "NOT STARTED"
                visionPass -> dispatchStatus
                else -> "NOT STARTED"
            }
            val recordedGesture = if (analysisOnly) "NOT CREATED" else gestureStatus
            val grid = vision?.grid
            val roiWidth = grid?.boardRoi?.width() ?: 0
            val roiHeight = grid?.boardRoi?.height() ?: 0
            val roiAspect = if (grid == null || roiWidth <= 0) {
                "not measured"
            } else {
                "%.4f".format(roiHeight.toFloat() / roiWidth)
            }
            val roiTopFraction = if (grid == null || frameHeight <= 0) {
                "not measured"
            } else {
                "%.4f".format(grid.boardRoi.top.toFloat() / frameHeight)
            }
            val roiBottomMargin = if (grid == null || frameHeight <= 0) {
                "not measured"
            } else {
                (frameHeight - grid.boardRoi.bottom).toString()
            }
            val pitchX = boundaryPitch(grid?.xBoundaries)
            val pitchY = boundaryPitch(grid?.yBoundaries)
            val columnPitchMaxMinRatio = pitchMaxMinRatio(grid?.xBoundaries)
            val rowPitchMaxMinRatio = pitchMaxMinRatio(grid?.yBoundaries)
            val dispatchDecision = recordedDispatch
            val finalSafety = when {
                !visionPass -> "HOLD $failure: $reason"
                recordedVerify == VerificationPolicy.BOARD_CHANGED_UNCONFIRMED ->
                    VerificationPolicy.BOARD_CHANGED_UNCONFIRMED
                failure != CLASS_NONE -> "HOLD $failure"
                else -> "vision PASS — diagnostic does not grant a move"
            }
            return DiagnosticBundle(
                appVersion = appVersion,
                versionCode = versionCode,
                sourceCommit = sourceCommit,
                diagnosticTimestampMs = diagnosticTimestampMs,
                failureClass = failure,
                visionGate = gate,
                visionReason = reason,
                gridConfidence = vision?.gridConfidence ?: -1f,
                boardConfidence = vision?.boardConfidence ?: -1f,
                unknownCount = vision?.unknownCount ?: -1,
                cellLabels = labels,
                boardRoi = roi,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                screenWidth = screen?.widthPx ?: 0,
                screenHeight = screen?.heightPx ?: 0,
                screenSource = screen?.source ?: ScreenMeasurement.SOURCE_UNAVAILABLE,
                screenRotation = screen?.rotation ?: ScreenMeasurement.ROTATION_UNKNOWN,
                coordinateAlignmentProven = false,
                coordinateReason = coordinateReason.ifBlank {
                    "coordinate origin alignment UNPROVEN"
                },
                frameSequence = frameSequence,
                captureTimestampMs = captureTimestampMs,
                frameAgeMs = frameAgeMs,
                frameElapsedMs = frameElapsedMs,
                cadence = cadence?.summary() ?: "not measured",
                accessibilityConnected = accessibilityConnected,
                gestureCapability = gestureCapability,
                captureState = if (captureOn && hasFrame) "ON" else if (captureOn) "ON (no frame)" else "OFF",
                moveAnalysis = when {
                    captureInvalid -> "not run — CAPTURE_INVALID"
                    visionPass -> moveAnalysis
                    else -> "not run — vision HOLD"
                },
                selectedMove = moveText,
                dispatchStatus = recordedDispatch,
                callbackOutcome = if (analysisOnly || !visionPass) "not dispatched" else callbackOutcome,
                verificationStatus = recordedVerify,
                verificationReason = verificationReason,
                gridMethod = gridMethod,
                relVarX = relVarX,
                relVarY = relVarY,
                projectionPeakCountX = peakX,
                projectionPeakCountY = peakY,
                meanLuminance = if (captureInvalid) "not measured" else meanLuminance,
                blackFrame = if (captureInvalid) {
                    "not measured — CAPTURE_INVALID (failed copy is not a black frame)"
                } else {
                    blackFrame
                },
                visionDecisions = decisions,
                dispatchDecision = dispatchDecision,
                finalSafetyDecision = finalSafety,
                frameExportStatus = frameExportStatus,
                frameExportReason = frameExportReason,
                gridBoundaries = boundaries,
                diagnosticInfluencesGate = false,
                coordinateSelfCheck = CoordinateSelfCheck.current()?.reason ?: "not recorded",
                overlayCollapsed = CaptureOverlayTrace.collapsed,
                overlayRect = CaptureOverlayTrace.overlayRect,
                overlayGateResult = CaptureOverlayTrace.gateResult,
                skippedFrameCount = CaptureOverlayTrace.skippedAfterCollapse,
                collapseWallMs = CaptureOverlayTrace.collapseWallMs,
                analyzedFrameTimestampMs = CaptureOverlayTrace.analyzedFrameTimestampMs,
                latticeScore = diag["latticeScore"] ?: "not measured",
                latticeStd = diag["latticeStd"] ?: "not measured",
                playfieldSnap = diag["playfieldSnap"] ?: "not measured",
                latticeRoiUsed = diag["latticeRoiUsed"] ?: "not measured",
                latticeCandidate = diag["latticeCandidate"] ?: "not measured",
                roiAspect = roiAspect,
                roiTopFraction = roiTopFraction,
                roiBottomMarginPx = roiBottomMargin,
                pitchX = pitchX,
                pitchY = pitchY,
                specialCropSizes = SpecialCropAudit.sizesText(),
                specialRejectedCropCount = SpecialCropAudit.rejectedCount(),
                specialRejectedCropOrigin = SpecialCropAudit.originText(),
                gestureStatus = recordedGesture,
                analysisOnly = analysisOnly,
                overlayMaskColumns = diag["overlayColumns"] ?: "not measured",
                frameSizeRetained = CaptureOverlayTrace.frameSizeRetained,
                columnPitchMaxMinRatio = columnPitchMaxMinRatio,
                rowPitchMaxMinRatio = rowPitchMaxMinRatio,
                simulated = simulated,
            )
        }

        private fun boundaryPitch(bounds: FloatArray?): String {
            if (bounds == null || bounds.size < 2) return "not measured"
            return (1 until bounds.size).joinToString(",") { i ->
                "%.1f".format(bounds[i] - bounds[i - 1])
            }
        }

        private fun pitchMaxMinRatio(bounds: FloatArray?): String {
            if (bounds == null || bounds.size < 2) return "not measured"
            val pitches = (1 until bounds.size).map { bounds[it] - bounds[it - 1] }.filter { it > 0f }
            if (pitches.isEmpty()) return "not measured"
            val min = pitches.minOrNull() ?: return "not measured"
            val max = pitches.maxOrNull() ?: return "not measured"
            if (min <= 0f) return "not measured"
            return "%.4f".format(max / min)
        }

        fun write(file: File, bundle: DiagnosticBundle) {
            file.parentFile?.mkdirs()
            file.writeText(bundle.toJson())
        }

        private fun jsonString(raw: String): String {
            val escaped = buildString(raw.length + 8) {
                for (ch in raw) {
                    when (ch) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> append(ch)
                    }
                }
            }
            return "\"$escaped\""
        }
    }
}

/** Last bundle the bubble published. Tests and a later device pull can read [latestJson]. */
object DiagnosticExport {
    @Volatile
    var latestJson: String = ""
        private set

    fun publish(bundle: DiagnosticBundle) {
        latestJson = bundle.toJson()
    }

    fun clear() {
        latestJson = ""
    }
}
