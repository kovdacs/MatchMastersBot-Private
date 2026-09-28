# REAL_FRAME_REPORT

**Date:** 2026-09-28 (Europe/Vienna)  
**Branch:** `main`  
**CI:** pending fill after analyzer-ci on this commit  
**APK:** pending fill  
**Scope:** Analyzer-only vision robustness. No AccessibilityService / touch injection / auto-play / DecisionEngine.

## Status flags (evidence-based)

| Flag | Value |
|------|-------|
| `REAL_FRAME_AVAILABLE` | **YES** (`real_frames/pvp_board.jpg` + secondaries) |
| `REALISTIC_FIXTURE_AVAILABLE` | **YES** (`RealisticSyntheticFixture`) |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `VISION_REAL_WORLD_VALIDATED` | **NO** (partial evidence only — see below) |

One real early-match frame + occluded secondaries ≠ full real-world validation. No Python V3.1 dump. Possible bottom-row crop from Android screenshot toolbar. Treat as **PARTIAL evidence**, flag stays **NO** until cleaner full 7×7 + parity dump.

## 1. Reference recovery

**Status: `REAL_FRAME_AVAILABLE=YES` / Python still `REFERENCE_PENDING`**

Checked in (2026-09-28):

| File | Role |
|------|------|
| `app/src/test/resources/real_frames/pvp_board.jpg` | **PRIMARY** — early PvP, Time Left 103, 0–0, mushroom +3 @ R5C2 (1-based); 1080×2400 |
| `pvp_board_showdown_overlay.jpg` | Secondary — READY? GO! / SHOWDOWN overlay |
| `pvp_board_activate_fx.jpg` | Secondary — ACTIVATE + particle FX |
| `pvp_board_mid_volume.jpg` | Secondary — volume slider occlusion |
| `human_ground_truth.json` | HUMAN_VISUAL GT (not detector-derived) |

**Not a board:** PERKS MENU (`98dd72…` / archive `01_perks_menu.jpg`) — never used as primary.

**Do not invent** READY Python parity data or V3.1 dumps. Scaffold `pvp_board_reference.json` remains `status: REFERENCE_PENDING`.

## 2–4. Harness

| Piece | Location |
|-------|----------|
| Harness REAL_FRAME | `RealFrameVisionTest` + `RealFrameLoader` — primary + soft secondaries |
| Human GT | `RealFrameHumanGroundTruth` + `human_ground_truth.json` (HUMAN_VISUAL) |
| Harness REALISTIC_SYNTHETIC | `RealisticSyntheticVisionTest` — **always runs**; not real MM |
| Harness SYNTHETIC_UNIT | `SyntheticFrames.letterboxedBoard` / `VisionPipelineTest` |

## Pipeline path

`Frame IntArray` → `BoardFinder` → 7×7 crops → `OcclusionDetector` → `ColorDetector` →
`ShapeDetector` → `SpecialDetector` → `ColorShapeReconciler` → `VisionValidator` (PASS/HOLD).

## Numeric results

### Real frame (`pvp_board.jpg`) — PRIMARY

| Field | Value |
|-------|-------|
| gridConfidence | **PENDING_CI** |
| boardConfidence | **PENDING_CI** |
| unknownCount | **PENDING_CI** |
| PASS/HOLD | **PENDING_CI** |
| board dims (BoardFinder ROI) | **PENDING_CI** |
| softGt colorMatch | **PENDING_CI** |

Caveats: Android screenshot toolbar may clip bottom UI / last board row. Pipeline still emits 7×7; GT row 6 (0-based) = UNVERIFIED. Mushroom +3 not in `SpecialType` enum → soft UNKNOWN.

### Secondary frames — soft diagnostics only

| Frame | Expected | Value |
|-------|----------|-------|
| showdown overlay | HOLD / high unknowns OK | **PENDING_CI** |
| activate FX | HOLD / high unknowns OK | **PENDING_CI** |
| mid volume | HOLD / high unknowns OK | **PENDING_CI** |

### REALISTIC_SYNTHETIC (canonical fixture) — prior CI run 36146083770

| Field | Value |
|-------|-------|
| gridConfidence | **0.9900** (PROJECTION) |
| boardConfidence | **0.0000** (14 unknowns → penalty) |
| unknownCount | **14** |
| colorMatch (compared known) | **35/35** (verifiableGT=48) |
| PASS/HOLD | **HOLD** (board confidence < 0.95) |

**HOLD/PASS on realistic synthetic ≠ VISION_REAL_WORLD_VALIDATED.**

## PASS / HOLD integrity

Gates **unchanged**:

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

No bypass. Prefer documenting HOLD/unknowns on real frames over loosening gates or hacking detectors.

## Parity

`REFERENCE_PENDING` retained. See `docs/VISION_PARITY.md`. No invented V3.1 dump. `PARITY_VERIFIED=NO`.

## Limitations

- Primary may have bottom row cropped by Android screenshot toolbar.
- Secondaries intentionally occluded (overlay / FX / volume) — expect HOLD.
- Mushroom special not modeled in `SpecialType`.
- Orange inverted triangles vs reconciler O→HEX expectation.
- No Python V3.1 dump → no Android↔Python parity.
- No JDK on box — CI Temurin 17 runs unit tests + assembleDebug.

## Next milestone

1. Cleaner full 7×7 capture without system screenshot overlays.
2. Real Python V3.1 dump for the same frame → set reference READY → parity comparator.
3. Only then reconsider `PARITY_VERIFIED` / `VISION_REAL_WORLD_VALIDATED=YES`.
4. Do **not** add input automation / DecisionEngine / AccessibilityService.
