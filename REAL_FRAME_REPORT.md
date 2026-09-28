# REAL_FRAME_REPORT

**Date:** 2026-09-28 (Europe/Vienna)
**Branch:** `main`  
**Commit:** `7b56734` (docs tip; vision PASS calib on `e0fc877`)
**CI:** analyzer-ci **SUCCESS** on `e0fc877` — run **36414665548** (PASS numerics); docs tip also green on **36415154332**
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

One early-match frame + occluded secondaries ≠ full real-world validation. Primary now locks square playfield ROI + PROJECTION with gate **PASS** (gridConf 0.9872, boardConf 0.9696, unk=1). Soft GT color still ~55%. No Python V3.1 dump. One early-match frame ≠ full real-world validation — flag stays **NO**.

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

## Numeric results (CI run 36414665548)

### Real frame (`pvp_board.jpg`) — PRIMARY

| Field | Value |
|-------|-------|
| frame | **1080×2400** |
| boardRoi (BoardFinder) | **LTRB(20,1206,1060,2246)** → dims **1040×1040** (`separator_square`) |
| gridMethod | **PROJECTION** |
| gridConfidence | **0.9872** (projRelVarX=0.0033, projRelVarY=0.0052) |
| boardConfidence | **0.9696** |
| unknownCount | **1** |
| PASS/HOLD | **PASS** |
| softGt colorMatch | **22/40** (verifiableGT=41, rate=0.550) |

Diagnosis: abs-max projection locked Xg6 on a gem-edge (−31px) and Yg6 on a toolbar energy spike; spacing variance kept gridConf at 0.9665. Outlier re-pick toward ideal period fixed gutters (Xg6 −3, Yg6 −1). Inner-70% `partial_dark` cleared false occlusion on sparse gems. BoardConf high-path mean floor 0.55 + lighter unk penalty so typical JPEG cell means clear MIN_BOARD without lowering 0.95.

ROI snap / MIN_GRID=0.98 / MIN_BOARD=0.95 / MAX_UNKNOWN=1 / ACTIVATE_FX fixtures **untouched**.

### Secondary frames — soft diagnostics only

| Frame | Result |
|-------|--------|
| showdown overlay | **HOLD** gridConf=0.9836 boardConf=0.0000 unknowns=16 PROJECTION |
| activate FX | **HOLD** gridConf=0.9892 boardConf=0.0000 unknowns=14 PROJECTION (no longer AIOOBE) |
| mid volume | **PASS** gridConf=0.9900 boardConf=1.0000 unknowns=0 PROJECTION |

Secondary ran=3 errored=0 of 3.

### REALISTIC_SYNTHETIC (canonical fixture) — same CI run

| Field | Value |
|-------|-------|
| gridConfidence | **0.9900** (PROJECTION) |
| boardConfidence | **0.0000** (14 unknowns → penalty) |
| unknownCount | **14** |
| colorMatch (compared known) | **35/35** (verifiableGT=48) |
| PASS/HOLD | **HOLD** (board confidence < 0.95)


**HOLD/PASS on realistic synthetic ≠ VISION_REAL_WORLD_VALIDATED.**

## PASS / HOLD integrity

Gates **unchanged**:

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

Gates unchanged (MIN_GRID=0.98, MIN_BOARD=0.95, MAX_UNKNOWN=1). Production changes are projection peak outlier re-pick, inner `partial_dark`, O+TRIANGLE accept, and boardConf high-path calibration (not gate constants).

## Android REAL_FRAME export (this milestone)

| Item | Value |
|------|-------|
| Export path | `data/vision/real_frames/pvp_board_android_export.json` (+ classpath copy under `app/src/test/resources/real_frames/`) |
| Producer | `VisionResultExporter` + `RealFrameExportTest` (pipeline → structured JSON) |
| Contents | boardRoi, 8× x/yBoundaries, 49 cellBoxes (LTRB + centerX/Y), 49 cells (color/shape/special/occlusion/confidence/isUnknown/finalTile/centers), gridConfidence, boardConfidence, unknownCount, validation/gate **PASS** |
| Status field | `ANDROID_EXPORT` — **not** a Python V3.1 dump |

Regression: `RealFrameExportTest` fails if primary no longer PASS, key numerics drift (gridConf≈0.9872, boardConf≈0.9696, unk=1), cell labels drift, or export schema keys go missing.

**Android export only.** `REFERENCE_PENDING=YES`; `PYTHON_REFERENCE_AVAILABLE=NO`; `PARITY_VERIFIED` remains **NO** (do not invent READY / fake V3.1).

## Parity

| Flag | Value |
|------|-------|
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `REFERENCE_PENDING` | **YES** |

Full hunt (tree + git history + branches/tags + `*.py`/`*.ipynb` blobs): **no** Python V3.1 dump/truth/notebook. Scaffold `pvp_board_reference.json` stays `REFERENCE_PENDING`. See `docs/VISION_PARITY.md` for Android stage map vs Python (matches / gaps / diffs). Do not invent READY dumps.

Primary REAL_FRAME **PASS** is a mandatory CI regression (`RealFrameVisionTest` asserts `ValidationResult.Pass`); gates/ROI/snap untouched.

Android REAL_FRAME export checked in (`pvp_board_android_export.json` + `RealFrameExportTest`). This is Android pipeline output only — still **not** Python parity (`PARITY_VERIFIED=NO`).

## Limitations

- Primary may have bottom row cropped by Android screenshot toolbar (row6 GT UNVERIFIED).
- Soft GT color match still ~55% on primary (HUMAN_VISUAL; not parity).
- Secondaries intentionally occluded (overlay / FX / volume); soft diagnostics only (HOLD OK).
- Mushroom special not modeled in `SpecialType`.
- No Python V3.1 dump → no Android↔Python parity (`REFERENCE_PENDING`).
- No JDK on box — CI Temurin 17 runs unit tests + assembleDebug.

## Next milestone

1. Cleaner full 7×7 capture without system screenshot overlays; improve BoardFinder ROI for tall MM UI (without loosening PASS/HOLD gates).
2. Real Python V3.1 dump for the same frame → set reference READY → parity comparator (do not invent).
3. Only after READY dump: reconsider `PARITY_VERIFIED` / `VISION_REAL_WORLD_VALIDATED=YES`.
4. Do **not** add input automation / DecisionEngine / AccessibilityService.
