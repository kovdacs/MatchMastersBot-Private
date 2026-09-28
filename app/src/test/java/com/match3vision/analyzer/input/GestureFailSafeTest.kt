package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.capture.ContentRoi
import com.match3vision.analyzer.capture.FrameSequenceGate
import com.match3vision.analyzer.evaluation.MoveEvaluation
import com.match3vision.analyzer.moves.Move
import com.match3vision.analyzer.vision.CellVision
import com.match3vision.analyzer.vision.GridGeometry
import com.match3vision.analyzer.vision.GridMethod
import com.match3vision.analyzer.vision.SpecialType
import com.match3vision.analyzer.vision.TileColor
import com.match3vision.analyzer.vision.TileShape
import com.match3vision.analyzer.vision.ValidationResult
import com.match3vision.analyzer.vision.VisionBoard
import com.match3vision.analyzer.vision.VisionResult
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

/**
 * Assert NONE of the listed conditions can allow a gesture (fail-closed).
 * PASS/HOLD thresholds are never loosened.
 */
class GestureFailSafeTest {

    private fun cell(color: TileColor) = CellVision(
        color = color,
        shape = when (color) {
            TileColor.R -> TileShape.CIRCLE
            TileColor.B -> TileShape.STAR
            TileColor.Y -> TileShape.TRIANGLE
            TileColor.G -> TileShape.DIAMOND
            TileColor.P -> TileShape.SQUARE
            TileColor.O -> TileShape.HEX
            TileColor.UNKNOWN -> TileShape.UNKNOWN
        },
        special = SpecialType.NONE,
        occluded = false,
        confidence = 1f,
        isUnknown = color == TileColor.UNKNOWN,
    )

    private fun baseColors() = Array(7) { r ->
        Array(7) { c ->
            listOf(TileColor.B, TileColor.Y, TileColor.G, TileColor.P, TileColor.O, TileColor.R)[(r * 3 + c * 2) % 6]
        }
    }

    private fun vision(
        colors: Array<Array<TileColor>> = baseColors(),
        gate: ValidationResult = ValidationResult.Pass,
        boardConf: Float = VisionThresholds.MIN_BOARD_CONFIDENCE,
        gridConf: Float = VisionThresholds.MIN_GRID_CONFIDENCE,
    ): VisionResult {
        val cells = Array(7) { r -> Array(7) { c -> cell(colors[r][c]) } }
        val unk = colors.sumOf { row -> row.count { it == TileColor.UNKNOWN } }
        return VisionResult(
            board = VisionBoard(cells),
            grid = GridGeometry.evenSplit(ContentRoi(0, 0, 700, 700), confidence = gridConf),
            unknownCount = unk,
            confidence = boardConf,
            boardConfidence = boardConf,
            gridConfidence = gridConf,
            validation = gate,
            method = GridMethod.EVEN_SPLIT,
        )
    }

    private fun goodMove(
        confidence: Float = 0.95f,
        uncertain: Boolean = false,
        ev: Float = 42f,
    ) = MoveEvaluation(
        move = Move(0, 0, 0, 1),
        totalScore = ev, matchScore = 30f, cascadeScore = 5f, specialScore = 0f,
        starScore = 0f, boosterScore = 0f, futureScore = 2f, riskPenalty = 0f,
        expectedValue = ev, confidence = confidence, uncertain = uncertain,
        reasons = listOf("test"),
    )

    private fun okCtx(
        vision: VisionResult = vision(),
        move: MoveEvaluation? = goodMove(),
        inputEnabled: Boolean = true,
        executorReady: Boolean = true,
        a11yConnected: Boolean = true,
        captureOk: Boolean = true,
        hasFrame: Boolean = true,
        frameAgeMs: Long = 100L,
        frameSequenceDecision: FrameSequenceGate.Decision? = null,
    ) = GestureFailSafe.Context(
        vision = vision,
        move = move,
        inputEnabled = inputEnabled,
        executorReady = executorReady,
        a11yConnected = a11yConnected,
        captureOk = captureOk,
        hasFrame = hasFrame,
        frameAgeMs = frameAgeMs,
        frameSequenceDecision = frameSequenceDecision,
    )

    private fun assertBlocked(ctx: GestureFailSafe.Context, hint: String) {
        val r = GestureFailSafe.evaluate(ctx)
        assertThat(r.allow).isFalse()
        assertThat(r.reason.lowercase()).contains(hint.lowercase())
    }

    @Test fun visionHold_blocks() =
        assertBlocked(okCtx(vision = vision(gate = ValidationResult.Hold("HOLD: test"))), "hold")

    @Test fun unknownCountAboveOne_blocks() {
        val colors = baseColors()
        colors[0][0] = TileColor.UNKNOWN
        colors[1][1] = TileColor.UNKNOWN
        assertBlocked(okCtx(vision = vision(colors = colors)), "unknown")
    }

    @Test fun gridConfBelow098_blocks() =
        assertBlocked(okCtx(vision = vision(gridConf = 0.979f)), "grid")

    @Test fun boardConfBelow095_blocks() =
        assertBlocked(okCtx(vision = vision(boardConf = 0.949f)), "board")

    @Test fun staleFrame_blocks() =
        assertBlocked(okCtx(frameAgeMs = GestureFailSafe.MAX_FRAME_AGE_MS + 1), "stale")

    @Test fun noFreshFrame_blocks() =
        assertBlocked(okCtx(hasFrame = false), "fresh")

    @Test fun captureError_blocks() =
        assertBlocked(okCtx(captureOk = false), "capture")

    @Test fun a11yNotConnected_blocks() =
        assertBlocked(okCtx(a11yConnected = false), "not ready")

    @Test fun inputReadyFalse_blocks() =
        assertBlocked(okCtx(executorReady = false), "not ready")

    @Test fun uncertainLowConfidence_blocks() =
        assertBlocked(okCtx(move = goodMove(confidence = 0.40f, uncertain = true)), "uncertain")

    @Test fun noValidMove_blocks() =
        assertBlocked(okCtx(move = null), "move")

    @Test fun sameFrameAfterGesture_blocks() {
        val decision = FrameSequenceGate.Decision(
            FrameSequenceGate.Verdict.REJECT_SAME,
            FrameSequenceGate.HOLD_SAME_FRAME,
            allow = false,
        )
        assertBlocked(okCtx(frameSequenceDecision = decision), "same")
    }

    @Test fun happyPath_allows() {
        val r = GestureFailSafe.evaluate(okCtx())
        assertThat(r.allow).isTrue()
    }

    @Test fun cascadeUncertain_withAdequateConfidence_stillAllowed() {
        // 9301892: cascade-uncertain metadata must not hard-block when conf ≥ MIN.
        val r = GestureFailSafe.evaluate(okCtx(move = goodMove(confidence = 0.90f, uncertain = true)))
        assertThat(r.allow).isTrue()
    }

    @Test fun thresholds_notLoosened() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }
}
