# Coordinate mapping audit (0.24.0)

**Date:** 2026-09-28 (Europe/Vienna)  
**Gates unchanged:** grid≥0.98 board≥0.95 unk≤1

## Pipeline (Move → cell → screen → GestureDescription → dispatchGesture)

| Step | Component | Coordinate space |
|------|-----------|------------------|
| 1 | `MoveAnalysisEngine` | Board cell indices `(r,c)` 0..6 |
| 2 | `TouchCoordinateMapper.toGesture` | `GridGeometry.cellBox` **frame px** centers |
| 3 | `GestureSpec` | `startX/Y`, `endX/Y` in **screen/frame px** |
| 4 | `MatchMastersAccessibilityService.dispatchGesture` | `Path` → `GestureDescription.StrokeDescription` |
| 5 | Framework | `AccessibilityService.dispatchGesture` |
| 6 | Verify | `InputFeedbackVerifier` — board contentHash changed (frame diff via Vision) |

## Mapping rules (no hardcoded pvp_board pixels)

- Centers come **only** from recognized `GridGeometry` (PROJECTION or EVEN_SPLIT of `boardRoi`).
- `boardRoi` is in the same pixel space as the MediaProjection `CaptureFrame` bitmap.
- Letterbox `ContentRoi` is applied in `BoardFinder` before grid fit — mapper does **not** re-offset.
- **Density / dp:** not applied. Gestures use raw capture pixels (1:1 with physical screen when MediaProjection matches display size).
- **Status / nav bars:** outside `boardRoi` by design; if bars intrude into ROI, Vision HOLD / wrong centers — keep bubble off playfield (top-end default).
- **Bubble overlay:** `FLAG_NOT_TOUCHABLE` during TESZT ÉRINTÉS / should not cover board; auto-play minimizes analyzer Activity.

## Unit coverage

`TouchCoordinateMapperAuditTest` — even-split ROI centers, letterboxed ROI, non-monotonic reject, no device literals.

## Live phone proof

Requires Tier E (operator). See `docs/LIVE_PHONE_TOUCH_PROOF.md`.
