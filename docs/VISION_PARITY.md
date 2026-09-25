# Vision Parity — Android vs Python V3.1

**Status:** `REFERENCE_PENDING`  
**Date:** 2026-09-25 (Europe/Vienna)

## Purpose

Compare Android `VisionResult` fields to a Python Match-3 Vision V3.1 reference dump for the same input frame. Metrics are **never blended into a single score** — each dimension is reported separately.

## Reference availability

**No real Python V3.1 output fixtures are present in this repository.**  
**No real Match Masters board capture (`pvp_board.jpg`) is present.**

Searches (private repo, match3-vision-ai trees, Google Drive exact + keyword, Gmail) found neither the JPEG nor a V3.1 dump. Do **not** invent READY board data or a fake dump.

The scaffold at `app/src/main/assets/data/vision/parity/pvp_board_reference.json` (and `data/vision/parity/pvp_board_reference.json`) therefore carries:

```json
"status": "REFERENCE_PENDING"
```

with null/empty field values.

When a real dump **and** matching frame are added later:

1. Place `pvp_board.jpg` under `app/src/test/resources/real_frames/`.
2. Replace the scaffold JSON (keep schema keys).
3. Set `"status": "READY"`.
4. Re-run `VisionParityComparator` against Android exports from `VisionResultExporter`.
5. Enable full assertions in `RealFrameVisionTest` (currently Assume-skips when missing).

Comparator unit tests continue to cover `REFERENCE_PENDING` behavior and synthetic READY comparisons (synthetic only — not real V3.1).

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
- `roiOffset` `{dx,dy}` (board ROI origin relative to letterbox, or absolute board left/top)
- `gridMethod` (`PROJECTION` | `EVEN_SPLIT`)
- `gridConfidence`
- `xBoundaries` (8 floats), `yBoundaries` (8 floats)
- `cellBoxes` (49 objects: `row,col,left,top,right,bottom`)
- `cells` (49 objects: `row,col,color,shape,special,occlusion,confidence,isUnknown`)
- `unknownCount`
- `gate` (`PASS` | `HOLD` + optional `holdReason`)
- `boardConfidence`, `confidence` (Android-side; not used as a blended parity score)

## Safety

Parity tooling is analyzer-only. It does not execute input or alter Decision AI behavior.

## PASS / HOLD gates (unchanged)

- `MIN_GRID_CONFIDENCE = 0.98`
- `MIN_BOARD_CONFIDENCE = 0.95`
- `MAX_UNKNOWN_COUNT = 1`

BoardFinder projection confidence uses `* 1.5f` as **scoring calibration** so clean gutter variance maps to ≥ 0.98; this does **not** lower the gate.
