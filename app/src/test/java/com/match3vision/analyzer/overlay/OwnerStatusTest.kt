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
        assertThat(OwnerStatus.hu("STOP — 60 gesture safety cap"))
            .isEqualTo("Elértem a biztonsági lépéshatárt – leálltam")
        assertThat(OwnerStatus.hu("STOP — no moves left"))
            .isEqualTo("Nincs több lépés – leálltam")
        assertThat(OwnerStatus.hu("STOP — booster needs a target"))
            .isEqualTo("A booster célpontot kér – leálltam")
        assertThat(OwnerStatus.hu("STOP — no legal move"))
            .isEqualTo("Nincs szabályos lépés – leálltam")
        assertThat(OwnerStatus.hu("STOP — turn not readable"))
            .isEqualTo("Nem látom, kié a kör – leálltam")
        assertThat(OwnerStatus.hu("STOP — 300s session limit"))
            .isEqualTo("Lejárt az 5 perc – leálltam")
        assertThat(OwnerStatus.hu("STOP — 900s session limit"))
            .isEqualTo("Lejárt a 15 perc – leálltam")
        assertThat(OwnerStatus.hu("Kisegítő szolgáltatás nincs bekapcsolva"))
            .isEqualTo("Engedélyezd a Kisegítő lehetőségeket")
        assertThat(OwnerStatus.hu("CAPTURE: OFF — CaptureService még nem él"))
            .isEqualTo("Képernyőrögzítés engedélyezése szükséges")
    }
}
