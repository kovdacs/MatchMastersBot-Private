# Vision Parity — Android vs Python V3.1

**Status:** `REFERENCE_PENDING`  
**Date:** 2026-09-21 (Europe/Vienna)

## Purpose

Compare Android `VisionResult` fields to a Python Match-3 Vision V3.1 reference dump for the same input frame. Metrics are **never blended into a single score** — each dimension is reported separately.

## Reference availability

No real Python V3.1 output fixtures are present in this repository. The scaffold at
`app/src/main/assets/data/vision/parity/pvp_board_reference.json` therefore carries:

```json
"status": "REFERENCE_PENDING"
```

with null/empty field values. **Do not invent board data or expected numeric dumps.**

When a real dump is added later:

1. Replace the scaffold JSON (keep schema keys).
2. Set `"status": "READY"`.
3. Re-run `VisionParityComparator` against Android exports from `VisionResultExporter`.

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
