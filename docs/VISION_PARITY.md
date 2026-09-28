# Vision Parity — Android vs Python V3.1

**Status:** `REFERENCE_PENDING`  
**Date:** 2026-09-28 (Europe/Vienna)  
**Tip context:** REAL_FRAME PASS + Android export golden on `pvp_board.jpg` (gridConf 0.9872, boardConf 0.9696, unk 1) — CI run 36416924508 / export `33a876d`; hunt tip `df89ece`.

## Flags

| Flag | Value |
|------|-------|
| `REAL_FRAME_AVAILABLE` | **YES** (`real_frames/pvp_board.jpg`) |
| `REALISTIC_FIXTURE_AVAILABLE` | **YES** |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `REFERENCE_PENDING` | **YES** (scaffold only — no real dump) |
| `VISION_REAL_WORLD_VALIDATED` | **NO** (partial real-frame evidence only) |

## Purpose

Compare Android `VisionResult` fields to a Python Match-3 Vision V3.1 reference dump for the **same real input frame**. Metrics are **never blended into a single score** — each dimension is reported separately.

**Do not** treat REALISTIC_SYNTHETIC ↔ SYNTHETIC_UNIT agreement as “parity verified”.  
**Do not** invent a Python V3.1 dump from detector output or human GT.

## Reference hunt / SEARCH_LOG (exhaustive, 2026-09-28 CEST)

Tip at hunt start: `df89ece` (main; REAL_FRAME PASS + Android export `33a876d`).  
**Do not invent** READY dumps / fake VERIFIED. Thresholds/ROI/detectors untouched this round.

### Commands / scopes executed

| # | Scope / command | Result |
|---|-----------------|--------|
| 1 | Working tree: `data/vision/parity/`, `app/src/main/assets/data/vision/parity/`, `app/src/test/resources/vision/parity/`, docs, tests | Scaffold JSON only (`status: REFERENCE_PENDING`, empty `cells`/`boundaries`) |
| 2 | Keywords: `V3.1`, `v3_1`, `python`, `parity`, `pvp_board`, `ground truth`, `match3 vision`, `REFERENCE_PENDING`, `PARITY_VERIFIED` | Docs + comparator scaffold + HUMAN_VISUAL soft GT notes; **no Python dump** |
| 3 | File types in tree + `git rev-list --all --objects`: `*.py`, `*.ipynb`, `*.pkl`, `*.npy`, `*.pt`, `*.onnx`, `*.h5` | **None** ever added (32 commits, all objects) |
| 4 | Branches / tags / remote refs | Remote: `main` only (`df89ece`). Local stale `vision/real-frame-robustness` (merged lineage). **No tags**. No Python artifacts on either tip |
| 5 | Git history: `git log --all`, deleted-file summary, every historical `pvp_board_reference.json` (`9636c07` → tip) | Always `"status": "REFERENCE_PENDING"`; **never** `"READY"` with filled cells |
| 6 | `git log -p -- '*.json'` for `"status": "READY"` Python dumps | Only ANDROID_EXPORT / HUMAN_VISUAL / REFERENCE_PENDING / synthetic construction GT — **no V3.1 READY** |
| 7 | CI: `gh run list` analyzer-ci (30+ runs); `gh api .../actions/artifacts` | **Unique artifact name:** `app-debug-apk` only. No python-dump / test-result zip artifacts |
| 8 | `gh run download 36416924508 -n app-debug-apk`; unzip `assets/data/vision/parity/pvp_board_reference.json` | Still `REFERENCE_PENDING` scaffold (505 bytes) inside APK |
| 9 | Related repos: `gh repo list kovdacs` | Only `MatchMastersBot-Private` + `grok-github-build-test` (no Match Masters / vision dumps) |
| 10 | `gh api users/kovdacs/gists` | **0 gists** |
| 11 | `gh search code --owner=kovdacs` for V3.1 / v3_1 / pythonVersion / pvp_board_reference | No additional hits beyond this private repo docs/scaffold |
| 12 | PRs / issues / releases | **None** |
| 13 | External URL scan in docs/status markdown (`gist`, `colab`, `drive`, `dropbox`, `huggingface`, `s3`, `pastebin`) | **No** linked dump URLs |
| 14 | Android export golden `data/vision/real_frames/pvp_board_android_export.json` | `status: ANDROID_EXPORT` — explicitly **not** Python V3.1; left untouched |
| 15 | `human_ground_truth.json` | `HUMAN_VISUAL` soft GT — provenance forbids treating as Python V3.1 |

### Finding

`PYTHON_REFERENCE_AVAILABLE=NO` → remain **`REFERENCE_PENDING`**, **`PARITY_VERIFIED=NO`**.  
No 49-cell Android↔V3.1 parity test can be enabled without inventing data.  
REAL_FRAME PASS gate + Android export regression stay green; no detector/threshold/ROI changes this round.

## Reference availability

**Real Match Masters board capture is present** (`app/src/test/resources/real_frames/pvp_board.jpg` + secondaries).  
**No real Python V3.1 output fixtures are present in this repository.**

Scaffold locations (identical pending schema):

- `app/src/main/assets/data/vision/parity/pvp_board_reference.json`
- `data/vision/parity/pvp_board_reference.json`
- `app/src/test/resources/vision/parity/pvp_board_reference.json` (JVM classpath copy for scaffold tests)

```json
"status": "REFERENCE_PENDING"
```

with null/empty field values. **Do not invent READY board data.**

When a real dump for the **same** frame is added later:

1. Keep `pvp_board.jpg` under `app/src/test/resources/real_frames/`.
2. Replace the scaffold JSON (keep schema keys) with the Python V3.1 export.
3. Set `"status": "READY"`.
4. Re-run `VisionParityComparator` against Android exports from `VisionResultExporter`.
5. Only then consider `PARITY_VERIFIED=YES`.

Human-authored `human_ground_truth.json` is **HUMAN_VISUAL** soft GT for diagnostics — not a Python parity reference.

Comparator unit tests cover `REFERENCE_PENDING` behavior and synthetic READY comparisons (**synthetic only — not real V3.1; not PARITY_VERIFIED**).

## Android pipeline stages (implemented)

Order in `VisionPipeline.analyze`:

```
Frame IntArray
  → BoardFinder          (ROI snap + PROJECTION / EVEN_SPLIT gutters)
  → OcclusionDetector    (per-cell; short-circuit → UNKNOWN if occluded)
  → ColorDetector        (HSV → TileColor)
  → ShapeDetector        (contour / circularity → TileShape)
  → SpecialDetector      (bomb / lightning / arrow overlays)
  → ColorShapeReconciler (color↔shape consistency; special pass-through)
  → VisionValidator      (PASS / HOLD: MIN_GRID / MIN_BOARD / MAX_UNKNOWN)
```

## Stage map vs Python V3.1

Python V3.1 source/dump is **absent**, so algorithmic parity cannot be verified. Mapping below is **intent / naming only** (Android side documented; Python side unknown).

| Stage | Android | Python V3.1 | Match / gap / diff |
|-------|---------|-------------|--------------------|
| BoardFinder | `BoardFinder` — separator_square ROI, projection gutters, EVEN_SPLIT fallback, conf `*1.5f` scoring calib | *unknown — no dump* | **GAP:** no ROI/boundary truth to compare. Intentional match: 7×7 + 8 gutters schema. |
| Occlusion | `OcclusionDetector` — documented “V3.1-style” dark/banner/partial_dark; no interpolation | *unknown* | **INTENT MATCH (name only):** dark/banner → UNKNOWN. **GAP:** thresholds/algorithms unverified. |
| Color | `ColorDetector` — HSV heuristics → `TileColor` | *unknown* | **GAP:** no per-cell color dump. |
| Shape | `ShapeDetector` — contour approx / circularity → `TileShape` | *unknown* | **GAP:** no per-cell shape dump. |
| Special | `SpecialDetector` — BOMB / LIGHTNING / TWO_WAY_ARROW; floor 0.55 → NONE | *unknown* | **GAP:** mushroom special not in `SpecialType`. No Python special labels. |
| Reconcile | `ColorShapeReconciler` — expected shape per color; orange△ accept path | *unknown* | **GAP:** reconcile rules not cross-checked. |
| Validation | `VisionValidator` — PASS iff grid≥0.98, board≥0.95, unk≤1 | *unknown* | **GAP:** gate semantics may differ; Android gates **unchanged**. |

### Summary

| Category | Detail |
|----------|--------|
| **Matches** | Stage *order* and export schema dimensions (roi, boundaries, cell_boxes, color, shape, special, occlusion, unknown_count, gate) align with a typical V3.1 Match-3 vision stack; OcclusionDetector claims V3.1-style behavior by design. |
| **Gaps** | No Python code, notebook, config, or READY dump anywhere in repo/history → every stage unverified numerically. |
| **Diffs** | Cannot assert diffs without a reference. Known Android-only notes (not Python diffs): mushroom unmodeled; HUMAN soft GT ≠ Python; boardConf high-path calib / projection `*1.5f` are Android scoring — not gate changes. |

## REALISTIC_SYNTHETIC (not parity)

`RealisticSyntheticFixture` + `RealisticSyntheticGroundTruth` exercise robustness with
**authored construction GT**. That is detector robustness, not Android↔Python parity.

## Comparison dimensions (separate metrics)

| Metric key | Android source | Notes |
|------------|----------------|-------|
| `roi` | letterbox / content ROI LTRB | Pixel coords in frame space |
| `boundaries` | `xBoundaries`, `yBoundaries` (len 8) | Absolute max / mean abs delta |
| `cell_boxes` | 49 `CellGeometry` LTRB | Per-cell and mean IoU / abs edge delta |
| `color` | per-cell `TileColor` | Match rate; UNKNOWN-aware |
| `shape` | per-cell `TileShape` | Match rate |
| `special` | per-cell `SpecialType` | Match rate |
| `occlusion` | per-cell `occluded` | Match rate |
| `unknown_count` | `VisionResult.unknownCount` | Absolute difference |
| `gate` | PASS / HOLD | Exact string equality |


## Android REAL_FRAME export (not parity)

Checked-in Android-only export for `pvp_board.jpg`:

- `data/vision/real_frames/pvp_board_android_export.json`
- `app/src/test/resources/real_frames/pvp_board_android_export.json` (JVM classpath golden)

Produced/validated by `VisionResultExporter` + `RealFrameExportTest` (runs live pipeline, compares key fields to golden, asserts schema).

| Flag | Still |
|------|-------|
| `REFERENCE_PENDING` | **YES** |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |

Do **not** treat `status: ANDROID_EXPORT` as Python `READY`. Scaffold `pvp_board_reference.json` remains `REFERENCE_PENDING`.

## Export schema (`VisionResultExporter`)

Required top-level fields:

- `status` (`ANDROID_EXPORT` for pipeline output; never invent Python `READY` here)
- `imageWidth`, `imageHeight`
- `letterboxRoi` `{left,top,right,bottom}`
- `boardRoi` `{left,top,right,bottom}`
- `roiOffset` `{dx,dy}`
- `gridMethod` (`PROJECTION` | `EVEN_SPLIT`)
- `gridConfidence`, `boardConfidence`, `confidence`
- `xBoundaries` (8 floats), `yBoundaries` (8 floats)
- `cellBoxes` (49 objects: row/col/LTRB + `centerX`/`centerY`)
- `cells` (49 objects: color/shape/special/occlusion/confidence/isUnknown/`finalTile` + centers)
- `unknownCount`
- `gate` / `validation` (`PASS` | `HOLD`)

## REAL_FRAME regression gate

Primary `pvp_board.jpg` must remain **PASS** in CI (`RealFrameVisionTest` hard-asserts `ValidationResult.Pass`).  
Do **not** weaken PASS/HOLD thresholds, BoardFinder ROI/snap, or the working REAL_FRAME pipeline for parity work.

## Safety

Parity tooling is analyzer-only. It does not execute input or alter Decision AI behavior.

## PASS / HOLD gates (unchanged)

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

BoardFinder projection confidence uses `* 1.5f` as **scoring calibration** so clean gutter variance maps to ≥ 0.98; this does **not** lower the gate.
