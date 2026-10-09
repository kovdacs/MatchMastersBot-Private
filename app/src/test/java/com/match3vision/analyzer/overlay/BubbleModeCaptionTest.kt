package com.match3vision.analyzer.overlay

import com.google.common.truth.Truth.assertThat
import com.match3vision.analyzer.input.FiveMoveArm
import org.junit.Test

class BubbleModeCaptionTest {

    @Test
    fun runningSession_tellsTheOwnerNotToTouch_andShowsTheMoveCount() {
        val text = BubbleModeCaption.collapsedStatus(
            fiveActive = true,
            fiveLabel = "5 LÉPÉS TESZT: 1/5",
            chipNotice = "",
            fallback = "ELEMZÉS",
        )
        assertThat(text).isEqualTo("Ne érintsd a képernyőt\n5 LÉPÉS TESZT: 1/5")
        assertThat(text).contains(FiveMoveArm.DO_NOT_TOUCH)
    }

    @Test
    fun idleChip_keepsTheRefusalNotice() {
        val text = BubbleModeCaption.collapsedStatus(
            fiveActive = false,
            fiveLabel = "5 LÉPÉS TESZT: 0/5",
            chipNotice = FiveMoveArm.STOPPED,
            fallback = "ELEMZÉS",
        )
        assertThat(text).isEqualTo(FiveMoveArm.STOPPED)
        assertThat(text).contains("Indítás")
    }
}
