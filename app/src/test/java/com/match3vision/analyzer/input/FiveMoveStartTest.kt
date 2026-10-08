package com.match3vision.analyzer.input

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FiveMoveStartTest {

    @Test
    fun freshLimit_usesTheLargerOfThreeSecondsAndThreeCadences() {
        assertThat(FiveMoveStart.freshLimitMs(0L)).isEqualTo(3_000L)
        assertThat(FiveMoveStart.freshLimitMs(800L)).isEqualTo(3_000L)
        assertThat(FiveMoveStart.freshLimitMs(1_200L)).isEqualTo(3_600L)
    }

    @Test
    fun oneFailingFrame_keepsWaiting_untilTheSeekExpires() {
        val seek = FiveMoveSeek()
        seek.begin(10_000L)
        val waiting = seek.offer(10_400L, sample(nowMs = 10_400L, visionPass = false, roiDetail = "ROI IMPLAUSIBLE"))
        assertThat(waiting).isInstanceOf(FiveMoveStart.Offer.Waiting::class.java)
        val chip = (waiting as FiveMoveStart.Offer.Waiting).report.chip
        assertThat(chip).isEqualTo("Tábla nem látszik: ROI IMPLAUSIBLE")
        assertThat(waiting.report.export).contains("fiveMoveSession")
        assertThat(waiting.report.export).contains("startMs=10000")

        val stale = seek.offer(
            12_000L,
            sample(nowMs = 12_000L, frameAgeMs = 3_500L, visionPass = true, roiPlausible = true),
        )
        assertThat(stale).isInstanceOf(FiveMoveStart.Offer.Waiting::class.java)
        assertThat((stale as FiveMoveStart.Offer.Waiting).report.chip).isEqualTo("Képkocka régi: 3500 ms")

        val expired = seek.offer(15_000L, sample(nowMs = 15_000L, visionPass = false))
        assertThat(expired).isInstanceOf(FiveMoveStart.Offer.Expired::class.java)
        assertThat(seek.isActive).isFalse()
    }

    @Test
    fun passingFrame_insideTheSeek_isReady() {
        val seek = FiveMoveSeek()
        seek.begin(1_000L)
        val ready = seek.offer(2_000L, sample(nowMs = 2_000L))
        assertThat(ready).isInstanceOf(FiveMoveStart.Offer.Ready::class.java)
        val report = (ready as FiveMoveStart.Offer.Ready).report
        assertThat(report.ready).isTrue()
        assertThat(report.chip).isEmpty()
        assertThat(report.export).contains("refusal=none")
        assertThat(report.export).contains("frame seq=7")
    }

    private fun sample(
        nowMs: Long,
        visionPass: Boolean = true,
        roiPlausible: Boolean = visionPass,
        roiDetail: String = if (roiPlausible) "yes" else "ROI IMPLAUSIBLE",
        frameAgeMs: Long = 200L,
    ) = FiveMoveStart.Sample(
        nowMs = nowMs,
        frameSequence = 7L,
        frameAgeMs = frameAgeMs,
        cadenceMedianMs = 400L,
        a11yConnected = true,
        selfCheckMeasured = true,
        overlayCollapsed = true,
        overlayOutsideRoi = true,
        visionPass = visionPass,
        unknownCount = if (visionPass) 0 else 2,
        roiPlausible = roiPlausible,
        roiDetail = roiDetail,
        ownUi = false,
        calibration = false,
        msSinceCollapse = FiveMoveSession.MIN_POST_COLLAPSE_MS,
    )
}
