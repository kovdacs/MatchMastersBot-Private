# Státusz — 0.24.0 Live Pipeline

**Dátum:** 2026-09-28 (Europe/Vienna)

## Mit csináltunk

- Élő debug státusz (P0): FRAME, BOARD ROI, GRID/BOARD CONF, UNKNOWN, PASS/HOLD, MOVE COUNT, SELECTED MOVE, A11Y, INPUT READY, LAST DISPATCH, VERIFY, FIRST BLOCK.
- VERIFY: board changed → SUCCESS / FAILED (nincs vak újrapróbálás).
- Folyamatos ciklus harness 1/5/10/20 (szimulált) + a11y disconnect→PAUSE→reconnect.
- Speciális küszöb 0.62 + szigorúbb BOMB/ARROW/LIGHTNING (kevesebb téves special).
- Koordináta audit dokumentum + unit tesztek.
- Élő telefon bizonyítás útmutató (Tier E — te futtatod).

## Mit nem

- Nincs élő telefon bizonyíték → **LIVE PHONE / FIRST TOUCH / VERIFY / CONTINUOUS / 5–20+ = FAIL** amíg nincs Tier E.
- Mushroom/+3 továbbra is cropokat vár (UNKNOWN-safe).
