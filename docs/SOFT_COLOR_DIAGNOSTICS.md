# Soft color diagnostics — Vision Stabilization 0.23.1

**Date:** 2026-09-28 (Europe/Vienna)  
**Evidence tier:** D — real Match Masters frame (`pvp_board.jpg`) in CI harness  
**Gates:** **unchanged** (`gridConfidence≥0.98`, `boardConfidence≥0.95`, `unknownCount≤1`)

## BEFORE (0.23.0 / android export)

| Metric | Value |
|--------|-------|
| soft-GT color match | **≈23/41 (~56%)** |
| gridConfidence | 0.9872 |
| boardConfidence | 1.0 |
| unknownCount | 0 |
| PASS/HOLD | PASS |

Root cause: full-cell HSV hue vote polluted by **blue playfield / gutters / JPEG AA**.
Wrong colors often reconciled as `B/STAR` (shape matched the polluted color).

## AFTER (0.23.1-vision-stab)

| Change | Detail |
|--------|--------|
| Center-weighted color | Ellipse radius `0.28` of cell; RGB+HSV+brightness gates; circular median hue + weighted vote |
| Shape sampling | `SHAPE_INSET_FRAC=0.18` rectangular inset (heuristics unchanged) |
| UNKNOWN taxonomy | `color \| shape \| special \| occlusion \| geometry/grid` via `UnknownReason` |

Target: soft-GT color **≥75%** on VERIFIED cells (CI soft assert); structural PASS retained.

See CI stdout from `SoftColorDiagnosticsTest` for the live confusion matrix and
`SOFT_COLOR_BEFORE_AFTER` line.

## What we did **not** do

- Did **not** lower `MIN_GRID` / `MIN_BOARD` / `MAX_UNKNOWN`.
- Did **not** invent `SpecialType.MUSHROOM` / `PLUS3` without labeled crops.
- Did **not** touch Input / Accessibility / `dispatchGesture`.

## Mushroom / +3 — labeled crops still required

See `docs/MUSHROOM_SPECIAL_PLAN.md`. Exact crops needed before any special type:

1. `(4,1)` body crop from `pvp_board.jpg` (center + full cell)
2. `(4,1)` **+3 badge** crop (corner overlay)
3. ≥3 additional mushroom/+3 instances from other real frames / live diag dumps
4. HSV/RGB/luma stats JSON per crop + human label `MUSHROOM_PLUS3`
