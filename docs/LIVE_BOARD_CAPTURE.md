# Live board capture — self-UI / overlay HOLD + live gridConf + live cells

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.21.2-live-cells`

## Symptoms

### A) Self-UI / overlay (0.19.3-live-board)

```
VALIDATE gate=Hold reason=HOLD: board confidence 0,000 < 0,950
boardConf=0,000 gridConf=0,9900 unk=42
contentRoi ≈ LTRB(0,88,1080,2400)
```

### B) Clear board, bubble running (0.21.1-live-grid)

```
On-device auto-bubble HOLD: grid confidence 0.972 < 0.980
húzások=0. Capture OK, bubble running. Board visible.
```

### C) Clear board, unk=2 (0.21.2-live-cells)

```
HOLD: unknownCount 2 > 1 (Decision AI blocked)
grid/board otherwise OK. Match3 Auto fut · 0 húzás
Sometimes: Elemző FAGYASZTVA / Fogadott=0 while bubble RUNNING
(large debug overlay + bubble covering capture)
```

REAL_FRAME `pvp_board.jpg` PASSes (gridConf≈0.9872, boardConf=1.0, unk=0). Gates unchanged
(MIN_GRID=0.98 MIN_BOARD=0.95 MAX_UNKNOWN=1).

## Root causes

1. **boardConf=0** from unk≃42 when MediaProjection captures analyzer UI / overlays.
2. **gridConf≈0.972** from mild boardRoi side mis-snap (~−13px left vs golden 20).
3. **unk=2** from high-conf color↔shape near-misses (purple square / mushroom misread as STAR)
   plus analyzer Activity UI frozen at 0 frames while bubble FUT (large Compose panels cover board).

## Fixes

| Version | Change |
|---------|--------|
| 0.19.3-live-board | `AnalysisFrameGate` freeze, RGBA→ARGB pack, suspect diagnostics |
| 0.21.1-live-grid | Soft gutter re-pick + ROI micro-nudge **only when** first-pass gridConf < 0.98; center-inset separator fallback |
| 0.21.2-live-cells | ColorShapeReconciler dominance margin (trust stronger channel when |Δconf|≥0.08); bubble FUT always accepts live frames; moveTaskToBack + compact UI hide large overlays during FUT; never freeze forever at 0 frames with bubble-only overlay |

Gates / AUTO-bubble UX / input-default-off **not** loosened.

## Operator workflow

1. INDÍTÁS in analyzer → grant perms → bubble appears (does **not** auto-swipe). Analyzer backgrounds so Match Masters can fill the screen.
2. Open Match Masters (board visible); bubble top-end stays clear of playfield.
3. Bubble **INDÍTÁS** → Vision→Move→swipe loop. Large analyzer UI stays compact/hidden during FUT.
4. Hold/Pause/STOP as needed.

## APK naming

CI uploads `Match3Analyzer-<versionName>-<shortsha>.apk` (not overwriting `app-debug.apk`).
