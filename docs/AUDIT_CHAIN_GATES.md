# Audit chain — START → … → dispatch → feedback

**Version:** 0.23.0-audit-pack  
**Date:** 2026-09-28 (Europe/Vienna)  
**Gates unchanged:** `MIN_GRID_CONFIDENCE=0.98`, `MIN_BOARD_CONFIDENCE=0.95`, `MAX_UNKNOWN_COUNT=1`

## Chain (each gate fail-closed → no `dispatchGesture`)

| # | Stage | Gate | Fail → |
|---|-------|------|--------|
| 1 | START | Overlay + a11y **CONNECTED** + MediaProjection + bubble session | stay IDLE / HIBA |
| 2 | RUNNING | `AutoPlayController.onStartRequested` + `StartupReadinessGate.canEnterRunning` | PAUSED / STOP reason |
| 3 | CAPTURE | `CaptureService` / `ScreenCaptureManager` frame present | HOLD no frame |
| 4 | FRAME GATE | `AnalysisFrameGate` accept live (bubble FUT / background) | freeze last board |
| 5 | FRAME SEQ | `FrameSequenceGate` — after gesture only **NEW** (SAME/OLD forbidden) | HOLD / failsafe pause |
| 6 | STALE | `GestureFailSafe` max frame age 3000 ms | HOLD stale |
| 7 | VISION | `VisionPipeline` → `VisionValidator` PASS | VISION HOLD (show unk/grid/board/…) |
| 8 | MOVE | `MoveAnalysisEngine` top move exists | HOLD no move |
| 9 | INPUT READY | switch ENABLED **and** AccessibilityService ready | HOLD channel not ready |
| 10 | PRE-GESTURE | `GestureFailSafe` / `AutomaticInputEngine.evaluateGate` | HOLD |
| 11 | DISPATCH | `InputGestureExecutor.dispatch` | HOLD dispatch failed |
| 12 | VERIFY | `InputFeedbackVerifier` board changed + vision valid | STOP / failsafe |

## Explicit non-gesture conditions (unit-tested)

Vision HOLD · unk>1 · gridConf<0.98 · boardConf<0.95 · stale frame · no fresh frame ·
capture error · a11y disconnected · INPUT READY false · uncertain+low conf · no valid Move ·
SAME/OLD post-gesture frame · verification failure.

**Note:** cascade-`uncertain` with `confidence ≥ MIN_MOVE_CONFIDENCE (0.50)` remains allowed
(9301892). Blind/low-confidence uncertain is blocked (`HOLD_MOVE_UNCERTAIN`).

## EVEN_SPLIT @ 0.72

`BoardFinder` fallback `EVEN_SPLIT` confidence **0.72** is **below** `MIN_GRID=0.98` →
validator **HOLD**. It cannot PASS. Improve ROI/projection/gutters; do not raise fallback conf.
