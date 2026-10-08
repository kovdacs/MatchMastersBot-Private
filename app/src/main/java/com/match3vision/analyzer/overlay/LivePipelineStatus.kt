package com.match3vision.analyzer.overlay

import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.BotLoopOutcome
import com.match3vision.analyzer.input.RuntimeLabels
import com.match3vision.analyzer.input.RuntimeSnapshot
import com.match3vision.analyzer.input.StartupReadinessGate
import com.match3vision.analyzer.vision.VisionThresholds

/**
 * P0 live-pipeline debug snapshot — pure Kotlin, unit-testable.
 *
 * Exposes every mandatory status field plus the **first** blocking reason
 * (audit-chain order). Never invents PASS for live phone (Tier E).
 */
data class LivePipelineStatus(
    val mode: String,
    val phase: String,
    val frame: String,
    val boardRoi: String,
    val gridConf: Float,
    val boardConf: Float,
    val unknownCount: Int,
    val visionGate: String,
    val moveCount: Int,
    val selectedMove: String,
    val a11y: String,
    val inputReady: String,
    val lastDispatch: String,
    val verifyStatus: String,
    val frameSequence: String,
    val frameAgeMs: Long,
    val captureStatus: String,
    /** First gate that blocks progress, or null when cycle may proceed. */
    val firstBlock: String?,
    /** CREATED only after a finite in-bounds GestureSpec exists. Otherwise NOT CREATED. */
    val gestureStatus: String = "NOT CREATED",
    val frameTimestampMs: Long = -1L,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    /** FRESH / STALE / NONE — from measured age, not a constant. */
    val frameFreshness: String = "NONE",
    /** MoveAnalysis candidate count (not executed-move counter). -1 = not measured. */
    val moveCandidates: Int = -1,
    val inputBlockReason: String? = null,
    /** JVM harness only. Device snapshots stay false. */
    val simulated: Boolean = false,
    /** True only when gesture build was attempted and failed. */
    val gestureAttemptFailed: Boolean = false,
    val coordinateBlocked: Boolean = false,
) {
    fun toSnapshot(): RuntimeSnapshot {
        val hasFrame = frame.contains("received", ignoreCase = true) ||
            frame.contains("seq=", ignoreCase = true) ||
            frameFreshness == "FRESH" || frameFreshness == "STALE" ||
            frameTimestampMs >= 0L
        val fresh = when (frameFreshness.uppercase()) {
            "FRESH", "STALE", "NONE" -> frameFreshness.uppercase()
            else -> RuntimeLabels.freshness(hasFrame, frameAgeMs)
        }
        return RuntimeSnapshot(
            autoplay = RuntimeLabels.autoplay(mode),
            capture = RuntimeLabels.capture(captureStatus),
            frameTimestampMs = frameTimestampMs,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            freshness = fresh,
            frameAgeMs = frameAgeMs,
            visionGate = visionGate,
            visionReason = visionGate,
            moveCandidates = moveCandidates,
            selectedMove = selectedMove,
            accessibility = RuntimeLabels.accessibility(a11y),
            inputReadiness = RuntimeLabels.inputReadiness(inputReady),
            inputBlockReason = inputBlockReason,
            gesture = RuntimeLabels.gesture(gestureStatus),
            dispatch = RuntimeLabels.dispatch(lastDispatch),
            verification = RuntimeLabels.verify(verifyStatus),
            firstBlock = firstBlock,
            simulated = simulated,
        )
    }

    fun bubbleLines(compact: Boolean = false): String {
        val snapshot = toSnapshot().lines()
        val legacy = if (compact) {
            buildString {
                appendLine("FÁZIS: $phase  MODE: $mode")
                appendLine("GATE: $visionGate  unk=$unknownCount")
                appendLine("grid=${fmt(gridConf)} board=${fmt(boardConf)}")
                appendLine("MOVE #$moveCount: $selectedMove")
                appendLine("A11Y: $a11y  READY: $inputReady")
                append("DISPATCH: $lastDispatch  VERIFY: $verifyStatus")
            }.trimEnd()
        } else {
            buildString {
                appendLine("FÁZIS: $phase")
                appendLine("MODE: $mode")
                appendLine("FRAME: $frame")
                appendLine("BOARD ROI: $boardRoi")
                appendLine("GRID CONF: ${fmt(gridConf)}")
                appendLine("BOARD CONF: ${fmt(boardConf)}")
                appendLine("UNKNOWN: $unknownCount")
                appendLine("PASS/HOLD: $visionGate")
                appendLine("MOVE COUNT: $moveCount")
                appendLine("SELECTED MOVE: $selectedMove")
                appendLine("A11Y: $a11y")
                appendLine("INPUT READY: $inputReady")
                appendLine("LAST DISPATCH: $lastDispatch")
                appendLine("VERIFY: $verifyStatus")
                appendLine("FRAME SEQ: $frameSequence ageMs=$frameAgeMs")
                appendLine("CAPTURE: $captureStatus")
                if (!firstBlock.isNullOrBlank()) {
                    append("FIRST BLOCK: $firstBlock")
                } else {
                    append("FIRST BLOCK: —")
                }
            }.trimEnd()
        }
        return snapshot + "\n" + legacy
    }

    companion object {
        private fun fmt(v: Float): String =
            if (v < 0f) "—" else "%.3f".format(v)

        /**
         * Audit-chain first blocker. Order matches [docs/AUDIT_CHAIN_GATES.md].
         */
        fun firstBlockingReason(
            mode: AutoPlayController.Mode,
            a11yConnected: Boolean,
            captureOn: Boolean,
            hasFrame: Boolean,
            frameSequenceAllow: Boolean,
            frameSequenceReason: String?,
            frameAgeMs: Long,
            maxFrameAgeMs: Long = 3_000L,
            visionPass: Boolean,
            visionHoldReason: String?,
            gridConf: Float,
            boardConf: Float,
            unknownCount: Int,
            hasSelectedMove: Boolean,
            inputReady: Boolean,
            lastDispatch: StartupReadinessGate.LastDispatch,
            verifyStatus: String,
            outcome: BotLoopOutcome? = null,
            cycleReason: String? = null,
            coordinateBlocked: Boolean = false,
            coordinateReason: String? = null,
            gestureAttemptFailed: Boolean = false,
            inputBlockReason: String? = null,
        ): String? {
            when (mode) {
                AutoPlayController.Mode.IDLE -> {
                    // User already pressed INDÍTÁS and a concrete gate rejected it.
                    // Don't hide that behind "press start".
                    val concrete = cycleReason?.takeIf { reason ->
                        reason.isNotBlank() &&
                            !reason.equals("tétlen", ignoreCase = true) &&
                            !reason.equals("idle", ignoreCase = true) &&
                            (
                                reason.contains("CAPTURE", ignoreCase = true) ||
                                    reason.contains("ACCESSIBILITY", ignoreCase = true) ||
                                    reason.contains("OVERLAY", ignoreCase = true) ||
                                    reason.contains("DISCONNECTED", ignoreCase = true)
                                )
                    }
                    if (concrete != null) return concrete
                    return "MODE IDLE — nyomd meg az INDÍTÁS-t"
                }
                AutoPlayController.Mode.PAUSED -> return "MODE PAUSED — ${cycleReason ?: "szünet"}"
                AutoPlayController.Mode.STOPPED -> return "MODE STOPPED — ${cycleReason ?: "leállítva"}"
                AutoPlayController.Mode.RUNNING -> Unit
            }
            if (!a11yConnected) return "ACCESSIBILITY: DISCONNECTED"
            if (!captureOn) return "CAPTURE: OFF"
            if (!hasFrame) return "FRAME: no frame"
            if (!frameSequenceAllow) {
                return frameSequenceReason?.ifBlank { "FRAME SEQ blocked" } ?: "FRAME SEQ blocked"
            }
            if (frameAgeMs > maxFrameAgeMs && frameAgeMs >= 0L) {
                return "FRAME stale ageMs=$frameAgeMs > $maxFrameAgeMs"
            }
            if (!visionPass) {
                return visionHoldReason?.ifBlank { "VISION HOLD" } ?: "VISION HOLD"
            }
            if (gridConf >= 0f && gridConf < VisionThresholds.MIN_GRID_CONFIDENCE) {
                return "gridConf ${"%.3f".format(gridConf)} < ${VisionThresholds.MIN_GRID_CONFIDENCE}"
            }
            if (boardConf >= 0f && boardConf < VisionThresholds.MIN_BOARD_CONFIDENCE) {
                return "boardConf ${"%.3f".format(boardConf)} < ${VisionThresholds.MIN_BOARD_CONFIDENCE}"
            }
            if (unknownCount > VisionThresholds.MAX_UNKNOWN_COUNT) {
                return "unknownCount=$unknownCount > ${VisionThresholds.MAX_UNKNOWN_COUNT}"
            }
            if (!hasSelectedMove) return "MOVE: none"
            if (!inputReady) {
                return inputBlockReason?.let { "INPUT: BLOCKED — $it" } ?: "INPUT: BLOCKED"
            }
            if (coordinateBlocked) {
                return coordinateReason ?: "COORD: off-screen / invalid (blocked before dispatch)"
            }
            if (gestureAttemptFailed) return "GESTURE: NOT CREATED"
            if (lastDispatch == StartupReadinessGate.LastDispatch.FAILED) {
                return "DISPATCH: FAILED"
            }
            if (verifyStatus.equals("FAILED", ignoreCase = true)) {
                return "VERIFY: FAILED — ${cycleReason ?: "board unchanged / invalid"}"
            }
            if (outcome == BotLoopOutcome.HOLD) {
                return cycleReason?.ifBlank { "HOLD" } ?: "HOLD"
            }
            if (outcome == BotLoopOutcome.STOP) {
                return cycleReason?.ifBlank { "STOP" } ?: "STOP"
            }
            return null
        }

        fun fromDiagnostics(
            diag: AutoPlaySession.Diagnostics,
            moveCount: Int,
            mode: AutoPlayController.Mode,
            a11yConnected: Boolean,
            captureOn: Boolean,
            inputReadyYes: Boolean,
            frameSequenceAllow: Boolean = true,
            visionPass: Boolean = diag.vision.contains("PASS", ignoreCase = true) &&
                !diag.vision.contains("HOLD", ignoreCase = true),
            hasSelectedMove: Boolean = diag.move.isNotBlank() &&
                !diag.move.equals("none", ignoreCase = true),
            lastDispatch: StartupReadinessGate.LastDispatch = when (diag.lastDispatch) {
                "SUCCESS" -> StartupReadinessGate.LastDispatch.SUCCESS
                "FAILED" -> StartupReadinessGate.LastDispatch.FAILED
                else -> StartupReadinessGate.LastDispatch.NONE
            },
            cycleReason: String? = diag.stopReason,
            outcome: BotLoopOutcome? = null,
        ): LivePipelineStatus {
            val hasFrame = diag.frame.contains("received", ignoreCase = true) ||
                diag.frame.contains("seq=", ignoreCase = true)
            val block = firstBlockingReason(
                mode = mode,
                a11yConnected = a11yConnected,
                captureOn = captureOn,
                hasFrame = hasFrame,
                frameSequenceAllow = frameSequenceAllow,
                frameSequenceReason = if (!frameSequenceAllow) diag.frameSequence else null,
                frameAgeMs = diag.frameAgeMs,
                visionPass = visionPass,
                visionHoldReason = if (!visionPass) diag.vision else null,
                gridConf = diag.gridConfidence,
                boardConf = diag.boardConfidence,
                unknownCount = diag.unknownCount,
                hasSelectedMove = hasSelectedMove,
                inputReady = inputReadyYes,
                lastDispatch = lastDispatch,
                verifyStatus = diag.verifyStatus,
                outcome = outcome,
                cycleReason = cycleReason,
            )
            return LivePipelineStatus(
                mode = diag.mode,
                phase = diag.phase,
                frame = diag.frame,
                boardRoi = diag.boardRoi,
                gridConf = diag.gridConfidence,
                boardConf = diag.boardConfidence,
                unknownCount = diag.unknownCount,
                visionGate = diag.vision,
                moveCount = moveCount,
                selectedMove = diag.move,
                a11y = diag.accessibility,
                inputReady = diag.inputReady,
                lastDispatch = diag.lastDispatch,
                verifyStatus = diag.verifyStatus,
                frameSequence = diag.frameSequence,
                frameAgeMs = diag.frameAgeMs,
                captureStatus = diag.captureStatus,
                firstBlock = block,
            )
        }
    }
}
