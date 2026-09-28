# REAL_FRAME_REPORT

**Date:** 2026-09-28 (Europe/Vienna)  
**Branch:** `main`  
**Commit:** `0c5b707` (harness) + docs follow-up  
**CI:** analyzer-ci **SUCCESS** on `0c5b707` — run **36407224555**  
**APK:** app-debug.apk artifact uploaded (**24,624,329** bytes on disk ≈ 23.5 MiB; artifact zip ≈ 8.6 MiB)  
**Scope:** Analyzer-only vision robustness. No AccessibilityService / touch injection / auto-play / DecisionEngine.

## Status flags (evidence-based)

| Flag | Value |
|------|-------|
| `REAL_FRAME_AVAILABLE` | **YES** (`real_frames/pvp_board.jpg` + secondaries) |
| `REALISTIC_FIXTURE_AVAILABLE` | **YES** (`RealisticSyntheticFixture`) |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `VISION_REAL_WORLD_VALIDATED` | **NO** (partial evidence only — see below) |

One early-match frame + occluded secondaries ≠ full real-world validation. BoardFinder fell back to EVEN_SPLIT on full-ish ROI (0,88,1080,2400) — not a tight 7×7 board crop. No Python V3.1 dump. Possible bottom-row crop from Android screenshot toolbar. Soft GT color match only 12/41. Treat as **PARTIAL evidence**; flag stays **NO**.

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

## Numeric results (CI run 36407224555)

### Real frame (`pvp_board.jpg`) — PRIMARY

| Field | Value |
|-------|-------|
| frame | **1080×2400** |
| boardRoi (BoardFinder) | **LTRB(0,88,1080,2400)** → dims **1080×2312** (not a tight board crop) |
| gridMethod | **EVEN_SPLIT** (projection rejected: `no_peaks` / projRelVarY=0.1409) |
| gridConfidence | **0.7200** |
| boardConfidence | **0.4812** |
| unknownCount | **1** |
| PASS/HOLD | **HOLD** (grid confidence 0.720 < 0.980) |
| softGt colorMatch | **12/41** (verifiableGT=41, rate=0.293) |
| mushroom (4,1) detector | color=B shape=STAR special=NONE (GT: unmapped Mushroom +3) |
| row6 detector | mostly P/B; last cell UNKNOWN — GT UNVERIFIED (toolbar crop) |

Caveats: Android screenshot toolbar may clip bottom UI / last board row. Pipeline still emits 7×7 cells over a too-tall ROI. Mushroom +3 not in `SpecialType` enum.

### Secondary frames — soft diagnostics only

| Frame | Result |
|-------|--------|
| showdown overlay | **HOLD** gridConf=0.7200 boardConf=0.3154 unknowns=3 EVEN_SPLIT |
| activate FX | **ERROR** `ArrayIndexOutOfBoundsException` (Index -1 / length 50120) — soft-documented, CI not failed |
| mid volume | **HOLD** gridConf=0.7200 boardConf=0.5781 unknowns=0 EVEN_SPLIT |

Secondary ran=2 errored=1 of 3.

### REALISTIC_SYNTHETIC (canonical fixture) — same CI run

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

No bypass. Prefer documenting HOLD/unknowns / FX exceptions on real frames over loosening gates or hacking detectors. **No production detector changes** in this milestone.

## Parity

`REFERENCE_PENDING` retained. See `docs/VISION_PARITY.md`. No invented V3.1 dump. `PARITY_VERIFIED=NO`.

## Limitations

- BoardFinder did not lock a tight board ROI on these captures (full-width EVEN_SPLIT fallback) → low gridConf → HOLD; soft GT colors poor.
- Primary may have bottom row cropped by Android screenshot toolbar.
- Secondaries intentionally occluded (overlay / FX / volume); ACTIVATE_FX currently crashes cell analysis (AIOOBE) — documented soft.
- Mushroom special not modeled in `SpecialType`.
- Orange inverted triangles vs reconciler O→HEX expectation.
- No Python V3.1 dump → no Android↔Python parity.
- No JDK on box — CI Temurin 17 runs unit tests + assembleDebug.

## Next milestone

1. Cleaner full 7×7 capture without system screenshot overlays; improve BoardFinder ROI for tall MM UI (without loosening PASS/HOLD gates).
2. Investigate ACTIVATE_FX AIOOBE (Index -1) with a targeted unit fixture — only if proven safe.
3. Real Python V3.1 dump for the same frame → set reference READY → parity comparator.
4. Only then reconsider `PARITY_VERIFIED` / `VISION_REAL_WORLD_VALIDATED=YES`.
5. Do **not** add input automation / DecisionEngine / AccessibilityService.
