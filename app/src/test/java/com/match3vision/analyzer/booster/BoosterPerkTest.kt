package com.match3vision.analyzer.booster

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BoosterPerkTest {
    @Test fun detector_empty_unknown() {
        assertThat(BoosterDetector().detectFromScreenLabels(emptyList()).unknown).isTrue()
    }

    @Test fun detector_label() {
        val d = BoosterDetector().detectFromScreenLabels(listOf("BOOSTER:GENERIC_BOMB"))
        assertThat(d.unknown).isFalse()
        assertThat(d.boosterId).isEqualTo("GENERIC_BOMB")
    }

    @Test fun database_unknownId() {
        assertThat(BoosterDatabase().get("NOPE").known).isFalse()
    }

    @Test fun perk_unknown() {
        val (score, unk) = PerkEvaluator().evaluate(PerkDetector.Detection(null, true, "x"))
        assertThat(unk).isTrue()
        assertThat(score).isEqualTo(0f)
    }
}
