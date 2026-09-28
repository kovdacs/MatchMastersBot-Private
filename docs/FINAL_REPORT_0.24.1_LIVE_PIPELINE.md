# Final report — 0.24.1-live-pipeline

**Date:** 2026-09-28 (Europe/Vienna)  
**Tip:** `e96fadc` (feature `9d54451` + CI golden/RestartCycle fix)  
**CI:** https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/36468136011 — **SUCCESS**  
**APK:** `Match3Analyzer-0.24.1-live-pipeline-e96fadc.apk` — **24 972 775** bytes (~23.8 MiB); artifact zip ~8.3 MiB  
**Unit tests:** BUILD SUCCESSFUL in 2m 6s (`testDebugUnitTest`)  
**assembleDebug:** BUILD SUCCESSFUL in 41s  

Local copy: `/workspace/MatchMastersBot-Private/dist/Match3Analyzer-0.24.1-live-pipeline-e96fadc.apk`

## PASS/HOLD

**Unchanged:** `MIN_GRID_CONFIDENCE=0.98`, `MIN_BOARD_CONFIDENCE=0.95`, `MAX_UNKNOWN_COUNT=1`.  
`SPECIAL_MIN_CONFIDENCE` **0.55 → 0.62** (false-special reduction; not a PASS gate).

## Explicit live-phone answer

**Tier E not proven.** Do **not** claim live phone Move + `dispatchGesture` on Match Masters works.  
Operator proof steps: `docs/LIVE_PHONE_TOUCH_PROOF.md`.

## P0 / P1 / P2 / P3

| Area | Status |
|------|--------|
| P0 live debug status + FIRST BLOCK | **Solved (code+unit)** — bubble compact HU |
| P0 VERIFY SUCCESS/FAILED + no blind retry | **Solved (code+unit harness)** |
| P0 continuous cycle | **Solved simulated 1/5/10/20** — live phone FAIL |
| P0 touch path documented | **Solved docs** — physical MM touch FAIL until user |
| P1 a11y connected-only + reconnect | **Solved (unit)** |
| P1 coordinate mapping audit | **Solved (docs+unit)** |
| P1 specials / mushroom | **Partial** — fewer false specials; mushroom UNKNOWN-safe, crops still required |
| P1 move engine | **No change** (no proven real bugs) |
| P2 bitmap/heartbeat/compact bubble | **Partial** — safer getPixels + heartbeat + compact status |
| P2 long-run harness docs | **Solved simulated** |
| P3 dead code / full docs cleanup | **Remaining** (README/PROJECT_STATUS aligned; dead-code pass deferred) |

## Live verdict (honest)

| Gate | Result |
|------|--------|
| LIVE PHONE | **FAIL** |
| FIRST TOUCH (MM board) | **FAIL** |
| VERIFY (on device) | **FAIL** |
| CONTINUOUS (on device) | **FAIL** |
| 5 / 10 / 20+ moves (on device) | **FAIL** |

Simulated continuous 1/5/10/20: **PASS** (Tier B). TESZT ÉRINTÉS path coded; needs user evidence.

## Remaining blockers

1. Operator must install APK + capture Tier E evidence (bubble PASS→DISPATCH→VERIFY SUCCESS on live MM).  
2. Live Vision may still HOLD (unk/grid) on some boards — do not loosen gates.  
3. Mushroom/+3 needs labeled crops.  
4. Residual false BOMB/ARROW on some gems (reduced 17→10 specials on pvp_board golden).
