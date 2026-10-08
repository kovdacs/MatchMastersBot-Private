# Státusz — 0.24.2 Runtime Diagnostics

**Dátum:** 2026-10-08

## Mit csináltunk

- A buborék a mért állapotot írja: AUTO, CAPTURE, FRAME (idő, méret, frissesség), VISION PASS/HOLD + indok, jelölt lépések, A11Y, INPUT READY/BLOCKED + indok, GESTURE, DISPATCH, VERIFY, első BLOKK.
- A `dispatchGesture` siker nem VERIFY SUCCESS. VERIFY SUCCESS csak új, friss, ellenőrizhető, megváltozott táblán.
- Elavult / hiányzó / SAME frame nem döntés. Hibás koordináta dispatch előtt áll. Egy sikertelen dispatch nem pörög újra.
- Indításnál, ha a CaptureService még nincs ott, rövid várakozás, utána egyértelmű CAPTURE: OFF — nem „nyomd meg az INDÍTÁS-t” újra.
- 1/5/10/20 szimuláció továbbra is sikeres, SIMULATION felirattal.

## Mit nem

- Élő telefon nincs tesztelve. **LIVE PHONE: NOT TESTED. FIRST REAL AUTOMATIC TOUCH: NOT PROVEN.**
- A látásküszöbök változatlanok (0.98 / 0.95 / unknown ≤ 1).
