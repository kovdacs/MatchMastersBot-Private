# Vision Test Matrix

**Date:** 2026-09-25 (Europe/Vienna)  
**Scope:** Analyzer-only. Gates unchanged: MIN_GRID=0.98, MIN_BOARD=0.95, MAX_UNKNOWN=1.  
**Flags:** REAL_FRAME_AVAILABLE=NO · REALISTIC_FIXTURE_AVAILABLE=YES · PYTHON_REFERENCE_AVAILABLE=NO · PARITY_VERIFIED=NO · VISION_REAL_WORLD_VALIDATED=NO

| Test | Input type | Expected | Actual | Result |
|------|------------|----------|--------|--------|
| RealFrameVisionTest.realFrame_pvpBoard_pipelineDiagnostics_whenPresent | REAL_FRAME (pvp_board.jpg) | 7×7 + diagnostics when present; Assume-skip if missing | Assume-skip (REAL_FRAME_MISSING) | SKIP (documented) |
| RealFrameVisionTest.realFrame_harness_reportsMissingClearly | REAL_FRAME absence | Document REAL_FRAME_MISSING | Prints missing + Assume | SKIP (documented) |
| RealisticSyntheticVisionTest.realisticSynthetic_alwaysRuns_pipelineDiagnostics | REALISTIC_SYNTHETIC | 7×7, ≥1 unknown from authored occlusion, color match ≥70% on VERIFIED cells; dump numerics | Filled by CI logs | RUN (always) |
| RealisticSyntheticVisionTest.realisticSynthetic_groundTruth_fromConstructionNotDetector | REALISTIC_SYNTHETIC GT | GT from construction params; (3,5) occluded UNKNOWN; shape UNVERIFIED | Authored | PASS (intent) |
| RealisticSyntheticVisionTest.realisticSynthetic_distinctFromCleanLetterboxedBoard | REALISTIC vs SYNTHETIC_UNIT | Distinct geometry/pixels from cleanBoard | Distinct | PASS (intent) |
| RealisticSyntheticVisionTest.realisticSynthetic_failurePath_dumpsUnknownMap | REALISTIC_SYNTHETIC | VisionDiagnostics 7×7 map | Map present | PASS (intent) |
| VisionPipelineTest.cleanBoard_pipelineProduces7x7AndPreferPassOrHoldMessage | SYNTHETIC_UNIT cleanBoard | PASS, unknowns≤1, gridConf≥0.98 | CI green historically | PASS |
| VisionPipelineTest.occludedCell_inPipeline_isUnknown | SYNTHETIC_UNIT | Cell (0,0) unknown+occluded | — | PASS (intent) |
| VisionPipelineTest.manyUnknowns_gateHold | SYNTHETIC_UNIT | HOLD when unknowns>1 | — | PASS (intent) |
| VisionPipelineTest.fallbackEvenSplit_whenNoGutters | SYNTHETIC_UNIT | EVEN_SPLIT + HOLD | — | PASS (intent) |
| BoardFinderRobustnessTest.cleanFixture_gridConfidenceAtLeastMinGate | SYNTHETIC_UNIT | gridConf≥0.98 PROJECTION | — | PASS (intent) |
| BoardFinderRobustnessTest.* (jitter/noise/jpeg/letterbox/scale/shear) | SYNTHETIC_UNIT degraded | Valid grid; conf floors documented; may HOLD | Printed in CI | DOCUMENTED |
| BoardFinderRobustnessTest.multiPerturbationMatrix_documentsGridBoardGate | SYNTHETIC + REALISTIC_SYNTHETIC | Table gridConf/boardConf/PASS\|HOLD; gates unchanged | Printed in CI | DOCUMENTED |
| BoardFinderRobustnessTest.relVarToConfidenceFormula_documents1_5fCalibration | Formula | *1.5f calibration; gate 0.98 untouched | — | PASS (intent) |
| GridConfidenceCalibrationTest.calibrationTable_documentsRelVarToGate | Formula table | relVar→conf→PASS/HOLD; no inflation | Printed table | DOCUMENTED |
| BoardConfidenceTest.idealizedCleanBoard_boardConfidencePassEligible | SYNTHETIC_UNIT | PASS eligible | — | PASS (intent) |
| BoardConfidenceTest.evenSplitManyUnknowns_gateHold_despiteAnyBoardConf | SYNTHETIC_UNIT | HOLD via grid gate | — | PASS (intent) |
| OcclusionAndReconcileTest (dark/banner/partial 50–70%/UI/textured/R/O clear) | Cell crops | FP/FN/UNKNOWN documented; R/O clear | — | PASS (intent) |
| ColorDetectorTest solids + shaded/AA/brightness/jpeg | Cell crops | Correct hue buckets; degraded still classified | — | PASS (intent) |
| ShapeDetectorRealisticTest + confidence clamp | Photorealistic-synthetic crops | conf∈[0,1]; soft shape labels | — | PASS (intent) |
| ColorShapeReconcilerTest | Synthetic / grey board | No fake PASS by hiding UNKNOWN | — | PASS (intent) |
| SpecialDetectorTest normal/arrow/lightning/bomb/uncertain/partial | Cell crops | Conservative NONE below 0.55 | Printed | DOCUMENTED |
| VisionValidatorTest integrity + threeIndependentHoldModes_noBypass | Numeric gates | Three independent HOLD modes; no bypass | — | PASS (intent) |
| VisionParityComparatorTest REFERENCE_PENDING | Parity JSON scaffold | PENDING handled; no READY self-mirror | — | PASS (intent) |

## Harness separation

| Mode | Class | When it runs |
|------|-------|--------------|
| REAL_FRAME | `RealFrameVisionTest` | Only if `real_frames/pvp_board.jpg` present |
| REALISTIC_SYNTHETIC | `RealisticSyntheticVisionTest` | Always |
| SYNTHETIC_UNIT | `VisionPipelineTest` / `SyntheticFrames.letterboxedBoard` | Always |

## Honesty

- REALISTIC_SYNTHETIC PASS ≠ real-world validation.
- No self-mirroring synthetic↔synthetic as “parity verified”.
- `pvp_board_reference.json` remains `REFERENCE_PENDING`.
