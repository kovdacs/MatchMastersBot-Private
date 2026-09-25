package com.match3vision.analyzer.gamemode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GameModeDetectorTest {
    private val d = GameModeDetector()

    @Test fun empty_unknown() {
        assertThat(d.detect(emptyList()).mode).isEqualTo(GameMode.UNKNOWN_GAME_MODE)
    }

    @Test fun tournament() {
        assertThat(d.detect(listOf("Tournament Cup")).mode).isEqualTo(GameMode.TOURNAMENT)
    }

    @Test fun doesNotAssumeNormal() {
        assertThat(d.detect(listOf("Something Odd")).mode).isEqualTo(GameMode.UNKNOWN_GAME_MODE)
    }

    @Test fun boosterRelated() {
        assertThat(d.detect(listOf("Booster Draft")).mode).isEqualTo(GameMode.BOOSTER_RELATED)
    }
}
