package com.match3vision.analyzer.overlay

/**
 * One short Hungarian sentence for the chip next to START and STOP.
 * The English HOLD/STOP text stays in the export.
 */
object OwnerStatus {
    fun hu(reason: String): String {
        val text = reason.trim()
        if (text.isEmpty()) return "Várok egy stabil táblát"
        val key = text.lowercase()
        return when {
            key.contains("opponent") -> "Az ellenfél köre van – leálltam"
            key.contains("unrecognized pvp") -> "Nem ismerem fel a kört – leálltam"
            key.contains("auto-calibration: board change") -> "A lépés nem a várt helyen történt – leálltam"
            key.contains("auto-calibration: screen") -> "A képernyő mérete nem egyezik – leálltam"
            key.contains("10 moves verified") || key.contains("10 moves complete") -> "Kész: 10 lépés megtörtént"
            key.contains("gesture safety") -> "Elértem a biztonsági lépéshatárt – leálltam"
            key.contains("no moves left") -> "Nincs több lépés – leálltam"
            key.contains("moves spent") -> "Elfogytak a lépések"
            key.contains("booster needs a target") -> "A booster célpontot kér – leálltam"
            key.contains("settle wait") -> "A tábla nem állt meg – leálltam"
            key.contains("menu or popup") -> "Menü vagy felugró ablak – leálltam"
            key.contains("unknown hud") -> "Nem ismerem a képernyőt – leálltam"
            key.contains("no legal") -> "Nincs szabályos lépés – leálltam"
            key.contains("turn not readable") -> "Nem látom, kié a kör – leálltam"
            key.contains("1800s") -> "Lejárt a 30 perc – leálltam"
            key.contains("900s") -> "Lejárt a 15 perc – leálltam"
            key.contains("600s") -> "Lejárt a 10 perc – leálltam"
            key.contains("300s") -> "Lejárt az 5 perc – leálltam"
            key.contains("120s") -> "Lejárt a 2 perc – leálltam"
            key.contains("user interference") -> "Hozzáértél a képernyőhöz – leálltam"
            key.contains("board unchanged") ||
                key.contains("gesture callback") ||
                key.contains("not created") ||
                key.contains("move was not dispatched") -> "A lépés nem sikerült – leálltam"
            key.contains("accessibility") || key.contains("kisegítő") ->
                "Engedélyezd a Kisegítő lehetőségeket"
            key.contains("capture") || key.contains("projection") || key.contains("képernyőrögzítés") ->
                "Képernyőrögzítés engedélyezése szükséges"
            key.contains("overlay") || key.contains("roi implausible") || key.contains("felirat") ->
                "Egy felirat takarja a táblát, várok…"
            key.contains("time left") || key.contains("low-time") || key.contains("kevés az idő") ->
                "Kevés az idő, gyorsan lépek"
            key.contains("vision") || key.contains("not pass") ||
                key.contains("tábla nem") || key.contains("nem látom") ->
                "Nem látom a táblát – nyisd meg a játékot"
            key.contains("auto: move-1") || key.contains("move-1 verified") ->
                "Kalibráció kész, folytatom"
            key.contains("self-check") || key.contains("teszt érintés") ->
                "Nincs mentett kalibráció – az első lépéssel mérem"
            key.contains("foreground") || key.contains("our app") -> "Az elemző van elöl – nyisd meg a játékot"
            key.contains("stop pressed") || key == "stop" -> "Leállítottam"
            hasHungarian(text) -> text
            key.contains("stop") -> "Leálltam"
            else -> "Várok egy stabil táblát"
        }
    }

    private fun hasHungarian(text: String): Boolean =
        text.any { it in "áéíóöőúüűÁÉÍÓÖŐÚÜŰ" }
}
