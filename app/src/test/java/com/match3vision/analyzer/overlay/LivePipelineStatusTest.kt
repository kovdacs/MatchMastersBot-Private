package com.match3vision.analyzer.overlay

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.AutoPlayController
import com.match3vision.analyzer.input.BotLoopOutcome
import com.match3vision.analyzer.input.StartupReadinessGate
import com.match3vision.analyzer.vision.VisionThresholds
import org.junit.Test

class LivePipelineStatusTest {

    @Test
    fun firstBlock_idleBeforeStart() {
        val b = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.IDLE,
            a11yConnected = true,
            captureOn = true,
            hasFrame = true,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = 10L,
            visionPass = true,
            visionHoldReason = null,
            gridConf = 0.99f,
            boardConf = 0.99f,
            unknownCount = 0,
            hasSelectedMove = true,
            inputReady = true,
            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
            verifyStatus = "NONE",
        )
        assertThat(b).contains("IDLE")
    }

    @Test
    fun firstBlock_a11yBeforeVision() {
        val b = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.RUNNING,
            a11yConnected = false,
            captureOn = true,
            hasFrame = true,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = 10L,
            visionPass = false,
            visionHoldReason = "HOLD — unknown",
            gridConf = 0.5f,
            boardConf = 0.5f,
            unknownCount = 9,
            hasSelectedMove = false,
            inputReady = false,
            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
            verifyStatus = "NONE",
        )
        assertThat(b).contains("ACCESSIBILITY")
    }

    @Test
    fun firstBlock_visionHoldBeforeMove() {
        val b = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.RUNNING,
            a11yConnected = true,
            captureOn = true,
            hasFrame = true,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = 10L,
            visionPass = false,
            visionHoldReason = "HOLD: unknownCount=3",
            gridConf = 0.99f,
            boardConf = 0.99f,
            unknownCount = 3,
            hasSelectedMove = false,
            inputReady = true,
            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
            verifyStatus = "NONE",
        )
        assertThat(b).contains("HOLD")
    }

    @Test
    fun firstBlock_gridGateNeverLoosened() {
        val b = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.RUNNING,
            a11yConnected = true,
            captureOn = true,
            hasFrame = true,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = 10L,
            visionPass = true,
            visionHoldReason = null,
            gridConf = 0.97f,
            boardConf = 0.99f,
            unknownCount = 0,
            hasSelectedMove = true,
            inputReady = true,
            lastDispatch = StartupReadinessGate.LastDispatch.NONE,
            verifyStatus = "NONE",
        )
        assertThat(b).contains("gridConf")
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
    }

    @Test
    fun firstBlock_verifyFailed() {
        val b = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.RUNNING,
            a11yConnected = true,
            captureOn = true,
            hasFrame = true,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = 10L,
            visionPass = true,
            visionHoldReason = null,
            gridConf = 0.99f,
            boardConf = 0.99f,
            unknownCount = 0,
            hasSelectedMove = true,
            inputReady = true,
            lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
            verifyStatus = "FAILED",
            cycleReason = "board unchanged after input — no blind retry",
        )
        assertThat(b).contains("VERIFY")
        assertThat(b).contains("FAILED")
    }

    @Test
    fun firstBlock_nullWhenClearPath() {
        val b = LivePipelineStatus.firstBlockingReason(
            mode = AutoPlayController.Mode.RUNNING,
            a11yConnected = true,
            captureOn = true,
            hasFrame = true,
            frameSequenceAllow = true,
            frameSequenceReason = null,
            frameAgeMs = 10L,
            visionPass = true,
            visionHoldReason = null,
            gridConf = 0.99f,
            boardConf = 0.99f,
            unknownCount = 0,
            hasSelectedMove = true,
            inputReady = true,
            lastDispatch = StartupReadinessGate.LastDispatch.SUCCESS,
            verifyStatus = "SUCCESS",
            outcome = BotLoopOutcome.CONTINUE,
        )
        assertThat(b).isNull()
    }

    @Test
    fun bubbleLines_containMandatoryP0Fields() {
        val s = LivePipelineStatus(
            mode = "RUNNING",
            phase = "LÁTÁS OK",
            frame = "received",
            boardRoi = "LTRB(20,1206,1060,2246)",
            gridConf = 0.987f,
            boardConf = 1.0f,
            unknownCount = 0,
            visionGate = "PASS",
            moveCount = 3,
            selectedMove = "selected 0,0→0,1",
            a11y = "CONNECTED",
            inputReady = "YES",
            lastDispatch = "SUCCESS",
            verifyStatus = "SUCCESS",
            frameSequence = "seq=5/ALLOW_NEW",
            frameAgeMs = 40L,
            captureStatus = "ON",
            firstBlock = null,
        )
        val full = s.bubbleLines(compact = false)
        for (key in listOf(
            "FRAME:", "BOARD ROI:", "GRID CONF:", "BOARD CONF:", "UNKNOWN:",
            "PASS/HOLD:", "MOVE COUNT:", "SELECTED MOVE:", "A11Y:",
            "INPUT READY:", "LAST DISPATCH:", "VERIFY:", "FIRST BLOCK:",
        )) {
            assertThat(full).contains(key)
        }
        val compact = s.bubbleLines(compact = true)
        assertThat(compact).contains("MOVE #3")
        assertThat(compact).contains("DISPATCH: SUCCESS")
    }

    @Test
    fun gates_neverLoosened() {
        assertThat(VisionThresholds.MIN_GRID_CONFIDENCE).isEqualTo(0.98f)
        assertThat(VisionThresholds.MIN_BOARD_CONFIDENCE).isEqualTo(0.95f)
        assertThat(VisionThresholds.MAX_UNKNOWN_COUNT).isEqualTo(1)
    }
}
