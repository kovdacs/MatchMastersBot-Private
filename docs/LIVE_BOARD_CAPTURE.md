# Live board capture — self-UI / overlay HOLD

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.19.3-live-board`

## Symptom (on-device one-step smoke)

```
VALIDATE gate=Hold reason=HOLD: board confidence 0,000 < 0,950
boardConf=0,000 gridConf=0,9900 unk=42
contentRoi ≈ LTRB(0,88,1080,2400)
```

Capture works (1080×2400, frames flowing). REAL_FRAME `pvp_board.jpg` still PASSes
(gridConf≈0.9872 boardConf≈0.9696 unk=1). Gates unchanged
(MIN_GRID=0.98 MIN_BOARD=0.95 MAX_UNKNOWN=1).

## Root cause

1. **boardConf=0 is a consequence of unk≃42**, not a separate formula bug.  
   Penalty path: `mean + methodBonus − unk×0.08` → with mean≈0 and unk=42 clamps to **0**.
2. **Nearly all cells UNKNOWN** while **gridConf stays high** means BoardFinder still
   locked a regular 7×7 gutter grid, but cell *content* is not gem-like.
3. **Primary live cause:** MediaProjection captures the **composed display**, including
   this app’s analyzer UI when it is in the foreground. Returning to the analyzer to tap
   *Futtatás* overwrote the last Match Masters frame with Material gray/white panels
   (status-bar letterbox often `LTRB(0,88,…)`). Occlusion / color then mark cells UNKNOWN.
4. Floating game overlays (READY/GO, FX) produce the same *class* of failure (see
   secondary REAL_FRAME showdown/activate — high gridConf, boardConf=0, elevated unk).

### Android / MediaProjection note

MediaProjection + `VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR` mirrors what the user sees.
It does **not** reliably capture “under” TYPE_APPLICATION_OVERLAY / Activity UI.
Prefer analyzing a frame taken while our panel does **not** cover the playfield
(split-screen / PiP, or freeze the last board frame from when the Activity was paused).

## Fix (0.19.3-live-board)

| Change | Role |
|--------|------|
| `AnalysisFrameGate` | While analyzer Activity resumed → **freeze** analysis bitmap; accept live frames while paused (Match Masters visible). Smoke post-wait may force-accept (split-screen still required). |
| Explicit RGBA→ARGB pack | Avoid brittle `copyPixelsFromBuffer` channel layout (wrong channels → high gridConf, all-unknown cells). |
| Vision diagnostics | `occSummary`, `boardConfPath`, `suspectOverlayOrSelfUi` when unk≥20 & gridConf≥0.98. |
| Validator order | grid → **unknown** → board so HOLD reason names unk when that drives the failure. |

Gates / REAL_FRAME PASS asserts **not** loosened.

## Operator workflow

1. Start capture in the analyzer.
2. Switch to Match Masters (full board visible) — frames accepted.
3. Return to analyzer — last board frame **frozen** (UI shows FAGYASZTVA).
4. Or keep **split-screen / PiP** so the board stays visible under MediaProjection.
5. Run one-step smoke on the frozen board frame.
6. After swipe, keep the board visible (split-screen) during WAIT for post-verify.

## What we did *not* change

- MIN_GRID / MIN_BOARD / MAX_UNKNOWN constants
- BoardFinder playfield snap / projection outlier logic that already PASSes REAL_FRAME
