# Soft color ~55% — diagnostic report (REAL_FRAME)

**Date:** 2026-09-28 (Europe/Vienna)  
**Evidence tier:** D — real Match Masters frame (`pvp_board.jpg`) in CI harness  
**Gates:** unchanged (PASS still achieved on primary with unk=0, gridConf≈0.987, boardConf=1.0)

## Symptom

Human VERIFIED color soft-GT agreement historically ~**55%** (`softGt colorMatch ≈ 22/40`)
while structural PASS holds. Wrong color ≠ unknownCount spike on primary.

## Investigation method

`SoftColorDiagnosticsTest` + `LiveCellDiagnostics`:
per-cell mean RGB / HSV / luma on BoardFinder cell boxes vs HUMAN_VISUAL GT.

## Likely causes (ordered)

1. **Highlight / specular + shadow** inside gems → HSV value/saturation extremes excluded
   (`S≥0.25`, `V≥0.18`) leave a thin hue vote; center vs full-area sampling differs.
2. **JPEG chroma bleed / AA** across gutters → neighbor hue votes pollute edge pixels.
3. **Orange inverted triangle** GT shape UNVERIFIED — hue sits on Y/O boundary (`h≈15–40`);
   detector may flip O↔Y while shape path expects HEX.
4. **Purple Mushroom +3** at (4,1): not a normal gem; `SpecialType` has no MUSHROOM;
   GT marks color/shape UNVERIFIED — overlay skews purple/white.
5. **Background bleed** if cell crop inset too aggressive or too loose (prior inset experiments
   traded unknowns vs color purity).

## What we did **not** do

- Did **not** lower `MIN_GRID` / `MIN_BOARD` / `MAX_UNKNOWN`.
- Did **not** invent new SpecialType values without appearance evidence beyond GT note.

## Next concrete Vision task

1. Add **center-weighted** color sampling (inner 50% disk) vs full-cell; A/B on soft-GT rate
   without touching PASS gates.
2. Per-hue confusion matrix from `SoftColorDiagnosticsTest` CI stdout → tune only
   `ColorDetector.hueToColor` boundaries with REAL_FRAME evidence.
3. Mushroom plan: see `docs/MUSHROOM_SPECIAL_PLAN.md`.
