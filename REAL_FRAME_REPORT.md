# REAL_FRAME_REPORT

**Date:** 2026-09-25 (Europe/Vienna)  
**Branch work:** `vision/real-frame-robustness` → `main`  
**Scope:** Analyzer-only vision robustness. No AccessibilityService / touch injection / auto-play / DecisionEngine.

## Real frame search result

**Status: `REAL_FRAME_MISSING`**

`pvp_board.jpg` does **not** exist in:

- this private repo (`kovdacs/MatchMastersBot-Private`)
- match3-vision-ai trees (prior parent verification)
- Google Drive (exact name + keyword search empty)
- Gmail

No fake real frame or fake Python V3.1 dump was invented.

## Python V3.1 parity

**Status: `REFERENCE_PENDING`**

- `data/vision/parity/pvp_board_reference.json` — null fields, `REFERENCE_PENDING`
- `docs/VISION_PARITY.md` updated to state missing dump + missing jpg
- Comparator tests for PENDING status retained

## Pipeline path

`Frame IntArray` → `BoardFinder` → 7×7 crops → `OcclusionDetector` → `ColorDetector` → `ShapeDetector` → `SpecialDetector` → `ColorShapeReconciler` → `VisionValidator` (PASS/HOLD).

REAL_FRAME harness: `RealFrameLoader` (ImageIO → ARGB) → `VisionPipeline.analyze` when `app/src/test/resources/real_frames/pvp_board.jpg` is present; otherwise JUnit `Assume` skip.

## Numeric results on real frame

| Field | Value |
|-------|-------|
| gridConfidence | **N/A** (REAL_FRAME_MISSING) |
| boardConfidence | **N/A** |
| unknownCount | **N/A** |
| PASS/HOLD | **N/A** |

## PASS / HOLD integrity

Gates **unchanged**:

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

BoardFinder `*1.5f` is scoring calibration only (documented + regression-tested), not a gate change. No forced-PASS / threshold-bypass hacks found or added.

## Fixes
5. **ShapeDetector** — clamp fallback `circularity * 0.8f` confidence to [0,1] (discrete masks can yield circularity > 1; CI evidence conf≈2.51).

1. **OcclusionDetector** — detect partial dark (≥50%) and gray/white/washed-blue UI overlays (≥50%) as occluded; textured banner path retained; solid R/O remain clear (regression tests).
2. **REAL_FRAME infra** — `RealFrameLoader`, `RealFrameVisionTest` (Assume skip when missing), resources README.
3. **VisionDiagnostics** — shared 7×7 UNKNOWN map + per-cell dump on AssertionError (REAL_FRAME + cleanBoard failure path).
4. **Tests** — ColorDetector, ColorShapeReconciler, ShapeDetectorRealistic, Occlusion expanded, BoardFinderRobustness, BoardConfidence, VisionValidator integrity.

## Limitations

- No real Match Masters frame available → no end-to-end real-device numeric gate proof.
- Shape “realistic” tests are synthetic IntArray crops (noise/blur/shade), not captures.
- Parity READY comparison blocked until a real V3.1 dump lands.
- JVM unit tests only in CI (no on-device instrumentation in this package).

## Test list (new / strengthened)

- `RealFrameVisionTest`
- `RealFrameLoader` (+ ImageIO path)
- `VisionDiagnostics`
- `ColorDetectorTest`
- `ColorShapeReconcilerTest`
- `ShapeDetectorRealisticTest`
- `OcclusionAndReconcileTest` (partial 50–70%, UI overlays, R/O clear)
- `BoardFinderRobustnessTest` (jitter, noise, jpeg-quant, letterbox, scale, shear, `*1.5f` formula)
- `BoardConfidenceTest`
- `VisionValidatorTest` integrity cases
- `VisionPipelineTest` failure-path diagnostics

## Next milestone

Check in a real `pvp_board.jpg` + Python V3.1 dump → set reference `READY` → run REAL_FRAME asserts and parity comparator on the same frame.
