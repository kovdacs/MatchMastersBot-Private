package com.match3vision.analyzer.overlay

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OwnerStatusTest {
    @Test
    fun everyOwnerExample_isOneHungarianSentence() {
        assertThat(OwnerStatus.hu("HOLD — vision gates are not PASS"))
            .isEqualTo("Nem látom a táblát – nyisd meg a játékot")
        assertThat(OwnerStatus.hu("HOLD — overlay intersects ROI"))
            .isEqualTo("Egy felirat takarja a táblát, várok…")
        assertThat(OwnerStatus.hu("STOP — Opponent's Turn"))
            .isEqualTo("Az ellenfél köre van – leálltam")
        assertThat(OwnerStatus.hu("skipped-low-time"))
            .isEqualTo("Kevés az idő, gyorsan lépek")
        assertThat(OwnerStatus.hu("STOP — board unchanged after move 1"))
            .isEqualTo("A lépés nem sikerült – leálltam")
        assertThat(OwnerStatus.hu("STOP — 10 moves verified"))
            .isEqualTo("Kész: 10 lépés megtörtént")
        assertThat(OwnerStatus.hu("Kisegítő szolgáltatás nincs bekapcsolva"))
            .isEqualTo("Engedélyezd a Kisegítő lehetőségeket")
        assertThat(OwnerStatus.hu("CAPTURE: OFF — CaptureService még nem él"))
            .isEqualTo("Képernyőrögzítés engedélyezése szükséges")
    }
}
