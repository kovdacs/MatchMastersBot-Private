# Live board capture — self-UI / overlay HOLD + live gridConf

**Date:** 2026-09-28 (Europe/Vienna)  
**Version:** `0.21.1-live-grid`

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

REAL_FRAME `pvp_board.jpg` still PASSes (gridConf≈0.9872). Gates unchanged
(MIN_GRID=0.98 MIN_BOARD=0.95 MAX_UNKNOWN=1).

## Root causes

1. **boardConf=0** from unk≃42 when MediaProjection captures analyzer UI / overlays.
2. **gridConf≈0.972** from mild boardRoi side mis-snap (~−13px left vs golden 20) while
   the 7×7 is otherwise clear — first-pass gutter absolute-max leaves soft-offset peaks
   inside the 0.12 hard band; spacing variance sum ≈0.0187 → conf 0.972.

## Fixes

| Version | Change |
|---------|--------|
| 0.19.3-live-board | `AnalysisFrameGate` freeze, RGBA→ARGB pack, suspect diagnostics |
| 0.21.1-live-grid | Soft gutter re-pick + ROI micro-nudge **only when** first-pass gridConf < 0.98; center-inset separator fallback if full-width sep fails (bubble/overlay on top UI). Clean REAL_FRAME first-pass untouched (golden 0.9872). |

Gates / AUTO-bubble UX / input-default-off **not** loosened.

## Operator workflow

1. INDÍTÁS in analyzer → grant perms → bubble appears (does **not** auto-swipe).
2. Open Match Masters (board visible); bubble top-end stays clear of playfield.
3. Bubble **INDÍTÁS** → Vision→Move→swipe loop. Input stays off until this tap.
4. Hold/Pause/STOP as needed.

## APK naming

CI uploads `Match3Analyzer-<versionName>-<shortsha>.apk` (not overwriting `app-debug.apk`).
