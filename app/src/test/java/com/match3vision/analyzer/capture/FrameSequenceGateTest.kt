package com.match3vision.analyzer.capture

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameSequenceGateTest {

    private fun id(seq: Long, identity: Long, ts: Long = 1_000L + seq) =
        FrameSequenceGate.FrameId(seq, identity, ts)

    @Test
    fun afterGesture_SAME_forbidden_NEW_allowed() {
        val gate = FrameSequenceGate()
        val f1 = id(1, 100)
        assertThat(gate.evaluate(f1).allow).isTrue()
        gate.markGestureDispatched(f1)

        val same = gate.evaluate(f1)
        assertThat(same.allow).isFalse()
        assertThat(same.verdict).isEqualTo(FrameSequenceGate.Verdict.REJECT_SAME)
        assertThat(same.reason).contains("SAME")

        val newerSameId = id(2, 100, ts = 2_000L)
        assertThat(gate.evaluate(newerSameId).allow).isFalse()

        val neu = id(2, 200, ts = 2_000L)
        val ok = gate.evaluate(neu)
        assertThat(ok.allow).isTrue()
        assertThat(ok.verdict).isEqualTo(FrameSequenceGate.Verdict.ALLOW_NEW)
    }

    @Test
    fun afterGesture_OLD_forbidden() {
        val gate = FrameSequenceGate()
        val f2 = id(2, 200)
        gate.evaluate(f2)
        gate.markGestureDispatched(f2)
        val old = gate.evaluate(id(1, 150, ts = 500L))
        assertThat(old.allow).isFalse()
        assertThat(old.verdict).isEqualTo(FrameSequenceGate.Verdict.REJECT_OLD)
    }

    @Test
    fun afterGesture_missingFrame_forbidden() {
        val gate = FrameSequenceGate()
        gate.markGestureDispatched(id(1, 10))
        val miss = gate.evaluate(null)
        assertThat(miss.allow).isFalse()
        assertThat(miss.verdict).isEqualTo(FrameSequenceGate.Verdict.REJECT_MISSING)
    }

    @Test
    fun reset_clearsRequireNew() {
        val gate = FrameSequenceGate()
        gate.markGestureDispatched(id(1, 1))
        assertThat(gate.requireNewAfterGesture).isTrue()
        gate.reset()
        assertThat(gate.requireNewAfterGesture).isFalse()
        assertThat(gate.evaluate(id(1, 1)).allow).isTrue()
    }
}
