package com.match3vision.analyzer.input

import com.match3vision.analyzer.capture.FrameCadence
import com.match3vision.analyzer.capture.ScreenMeasurement
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
    val simulated: Boolean,
) {
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
        field("simulated", simulated.toString(), raw = true, last = true)
        append("}\n")
    }

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
        ): DiagnosticBundle {
            val gate = when {
                vision == null -> "NONE"
                vision.validation.isPass -> "PASS"
                else -> "HOLD"
            }
            val reason = vision?.validation?.let { v ->
                if (v.isPass) "PASS" else (v as? com.match3vision.analyzer.vision.ValidationResult.Hold)?.reason ?: "HOLD"
            } ?: "no vision result"
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
            val visionPass = vision?.validation?.isPass == true
            val failure = classify(
                captureOn = captureOn,
                hasFrame = hasFrame,
                accessibilityConnected = accessibilityConnected,
                coordinateRefused = coordinateRefused,
                visionPass = visionPass,
                dispatchFailed = dispatchStatus.equals("FAILED", ignoreCase = true),
                verificationFailed = verificationStatus.equals(VerificationPolicy.FAILED, ignoreCase = true),
            )
            val moveText = if (!visionPass) "none" else selectedMove.ifBlank { "none" }
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
                moveAnalysis = if (visionPass) moveAnalysis else "not run — vision HOLD",
                selectedMove = moveText,
                dispatchStatus = if (visionPass) dispatchStatus else "NOT STARTED",
                callbackOutcome = if (visionPass) callbackOutcome else "not dispatched",
                verificationStatus = verificationStatus.ifBlank { VerificationPolicy.PENDING },
                verificationReason = verificationReason,
                simulated = simulated,
            )
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
