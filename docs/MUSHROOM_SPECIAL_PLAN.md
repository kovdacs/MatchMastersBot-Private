# Special: Purple Mushroom +3 — recognition plan

**Status:** appearance identified on REAL_FRAME GT; model **not** invented yet.  
**Location:** `pvp_board.jpg` cell **(4,1)** 0-based — “Purple Mushroom +3”.  
**Current:** `SpecialType` has no `MUSHROOM` / `PLUS3`; GT marks color/shape **UNVERIFIED**.

## Appearance (from human GT + frame)

- Purple / magenta organic “mushroom” sprite (not a standard R/B/Y/G/P/O gem).
- “+3” badge overlay (score modifier), bright text/icon on tile.
- Occupies one 7×7 cell inside playfield ROI.

## Labeled crops required (do not ship blind types)

| # | Crop | Source | Label |
|---|------|--------|-------|
| 1 | Full cell `(4,1)` | `pvp_board.jpg` | `MUSHROOM_PLUS3` |
| 2 | Center-disk body only `(4,1)` | same | `MUSHROOM_BODY` |
| 3 | +3 badge ROI (corner of cell) | same | `PLUS3_BADGE` |
| 4–N | ≥3 more mushroom/+3 cells | other real frames or live `LiveCellDiagnostics` dumps | `MUSHROOM_PLUS3` |

Each crop package should include: PNG/JPEG crop, mean RGB/HSV/luma, human label, frame id, cell `(r,c)`.

## After crops exist

1. Add `SpecialType.MUSHROOM_PLUS3` (or `SCORE_MUSHROOM`).
2. `SpecialDetector`: bright “+”/digit lobe + purple body mask; require `SPECIAL_MIN_CONFIDENCE`.
3. Reconciler / match policy: explicit decision whether mushroom counts as board-unknown.
4. `MoveEvaluator`: +3 is scoring metadata — not a clear-special until rules confirmed.

## Non-goals (0.23.1)

- No ad-hoc mushroom recognition.
- No threshold loosen.
- No guessed combo tables.

## UNKNOWN-safe policy (0.24.0)

Until labeled crops exist: cells that look like mushroom/+3 must remain
`TileColor.UNKNOWN` / `SpecialType.NONE` (or UNKNOWN via reconciler) — **never** invent
`MUSHROOM_PLUS3`. False BOMB/ARROW/LIGHTNING reduced via `SPECIAL_MIN_CONFIDENCE=0.62`
and stricter heuristics; mushroom still out of scope without crops.

