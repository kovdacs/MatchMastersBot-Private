# REAL_FRAME_REPORT

**Date:** 2026-09-25 (Europe/Vienna)  
**Branch:** `main`  
**Scope:** Analyzer-only vision robustness. No AccessibilityService / touch injection / auto-play / DecisionEngine.

## Status flags (evidence-based)

| Flag | Value |
|------|-------|
| `REAL_FRAME_AVAILABLE` | **NO** |
| `REALISTIC_FIXTURE_AVAILABLE` | **YES** (`RealisticSyntheticFixture`) |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `VISION_REAL_WORLD_VALIDATED` | **NO** |

Realistic-synthetic PASS is **not** real-world validation.

## 1. Reference recovery

**Status: `REAL_FRAME_MISSING` / `REFERENCE_PENDING`**

Searched (git history + trees):

- Private repo `kovdacs/MatchMastersBot-Private` — **no** `pvp_board.jpg` in any commit/object
- Git history objects for `pvp_board.jpg` / Match Masters board captures — **empty**
- Only launcher mipmaps + `pvp_board_reference.json` (`REFERENCE_PENDING`, null fields)
- Prior parent verification: match3-vision-ai / Google Drive / Gmail — no real MM frame

**Do not invent** READY parity data, fake real screenshots, or fake Python V3.1 dumps.

Scaffold: `data/vision/parity/pvp_board_reference.json` and
`app/src/main/assets/data/vision/parity/pvp_board_reference.json` remain
`status: REFERENCE_PENDING` with nulls.

## 2–4. REALISTIC_SYNTHETIC fixture + GT + harness

| Piece | Location |
|-------|----------|
| Fixture builder | `RealisticSyntheticFixture.kt` (AA, noise, JPEG-quant, scale/gutter jitter, letterbox offset, tile shading, brightness/contrast, partial occlusion) |
| Ground truth | `RealisticSyntheticGroundTruth.kt` + `test/resources/realistic_synthetic/canonical_ground_truth.json` |
| GT provenance | **From construction parameters (what was drawn)** — never from VisionPipeline detector output |
| Harness REAL_FRAME | `RealFrameVisionTest` — Assume-skip if `pvp_board.jpg` missing |
| Harness REALISTIC_SYNTHETIC | `RealisticSyntheticVisionTest` — **always runs** |
| Harness SYNTHETIC_UNIT | `SyntheticFrames.letterboxedBoard` / `VisionPipelineTest` — clean unit board (separate) |

## Pipeline path

`Frame IntArray` → `BoardFinder` → 7×7 crops → `OcclusionDetector` → `ColorDetector` →
`ShapeDetector` → `SpecialDetector` → `ColorShapeReconciler` → `VisionValidator` (PASS/HOLD).

## Numeric results

### Real frame (`pvp_board.jpg`)

| Field | Value |
|-------|-------|
| gridConfidence | **N/A** (`REAL_FRAME_AVAILABLE=NO`) |
| boardConfidence | **N/A** |
| unknownCount | **N/A** |
| PASS/HOLD | **N/A** |

### REALISTIC_SYNTHETIC (canonical fixture)

Numerics are printed by `RealisticSyntheticVisionTest` / BoardFinder matrix in CI logs
(`gridConf`, `boardConf`, `unknowns`, `gate`). Authored occlusion at (3,5) ⇒ ≥1 UNKNOWN.
**PASS here ≠ VISION_REAL_WORLD_VALIDATED.**

## PASS / HOLD integrity

Gates **unchanged**:

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

Three independent HOLD modes proven in `VisionValidatorTest.threeIndependentHoldModes_noBypass`
(grid / board / unknownCount). No bypass. Failure dumps use `VisionDiagnostics` 7×7 UNKNOWN map.

BoardFinder `*1.5f` is scoring calibration only (see `GridConfidenceCalibrationTest`), not a gate change.

## Robustness / audits (5–13)

- BoardFinder multi-perturbation matrix (`BoardFinderRobustnessTest`)
- GridConfidence calibration table (`GridConfidenceCalibrationTest`)
- Occlusion dark/warm/cool/white/grey/partial/textured/R+O clear (`OcclusionAndReconcileTest`)
- ColorDetector shading/AA/brightness/compression (`ColorDetectorTest`)
- ShapeDetector + confidence clamp (`ShapeDetectorRealisticTest`)
- ColorShapeReconciler no fake PASS (`ColorShapeReconcilerTest`)
- SpecialDetector conservative (`SpecialDetectorTest`)

## Parity (14)

`REFERENCE_PENDING` retained. See `docs/VISION_PARITY.md`. No synthetic↔synthetic self-mirror as verified parity.

## Test matrix (15)

`docs/VISION_TEST_MATRIX.md`

## Limitations

- No real Match Masters frame → no end-to-end real-device gate proof.
- REALISTIC_SYNTHETIC is synthetic IntArray (noise/AA/shade/quant), not a capture.
- Shape GT marks silhouette as UNVERIFIED for solid shaded fills (reconciler may fill expected pair).
- Parity READY blocked until real V3.1 dump + matching frame land.
- No JDK on box — CI Temurin 17 runs unit tests + assembleDebug.

## Next milestone

Check in real `pvp_board.jpg` + Python V3.1 dump → set reference `READY` → enable REAL_FRAME asserts + parity comparator on the same frame → only then consider `PARITY_VERIFIED` / `VISION_REAL_WORLD_VALIDATED`.
