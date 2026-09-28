# Vision Parity — Android vs Python V3.1

**Status:** `REFERENCE_PENDING`  
**Date:** 2026-09-28 (Europe/Vienna)

## Flags

| Flag | Value |
|------|-------|
| `REAL_FRAME_AVAILABLE` | **YES** (`real_frames/pvp_board.jpg`) |
| `REALISTIC_FIXTURE_AVAILABLE` | **YES** |
| `PYTHON_REFERENCE_AVAILABLE` | **NO** |
| `PARITY_VERIFIED` | **NO** |
| `VISION_REAL_WORLD_VALIDATED` | **NO** (partial real-frame evidence only) |

## Purpose

Compare Android `VisionResult` fields to a Python Match-3 Vision V3.1 reference dump for the **same real input frame**. Metrics are **never blended into a single score** — each dimension is reported separately.

**Do not** treat REALISTIC_SYNTHETIC ↔ SYNTHETIC_UNIT agreement as “parity verified”.
**Do not** invent a Python V3.1 dump from detector output or human GT.

## Reference availability

**Real Match Masters board capture is present** (`app/src/test/resources/real_frames/pvp_board.jpg` + secondaries).  
**No real Python V3.1 output fixtures are present in this repository.**

The scaffold at `app/src/main/assets/data/vision/parity/pvp_board_reference.json`
(and `data/vision/parity/pvp_board_reference.json`) therefore still carries:

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

Comparator unit tests continue to cover `REFERENCE_PENDING` behavior and synthetic READY
comparisons (**synthetic only — not real V3.1; not PARITY_VERIFIED**).

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

## Export schema (`VisionResultExporter`)

Required top-level fields:

- `imageWidth`, `imageHeight`
- `letterboxRoi` `{left,top,right,bottom}`
- `roiOffset` `{dx,dy}`
- `gridMethod` (`PROJECTION` | `EVEN_SPLIT`)
- `gridConfidence`
- `xBoundaries` (8 floats), `yBoundaries` (8 floats)
- `cellBoxes` (49 objects)
- `cells` (49 objects)
- `unknownCount`
- `gate` (`PASS` | `HOLD`)
- `boardConfidence`, `confidence` (not used as a blended parity score)

## Safety

Parity tooling is analyzer-only. It does not execute input or alter Decision AI behavior.

## PASS / HOLD gates (unchanged)

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

BoardFinder projection confidence uses `* 1.5f` as **scoring calibration** so clean gutter variance maps to ≥ 0.98; this does **not** lower the gate.
