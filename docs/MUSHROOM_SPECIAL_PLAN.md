# Special: Purple Mushroom +3 — recognition plan

**Status:** appearance identified on REAL_FRAME GT; model **not** invented yet.  
**Location:** `pvp_board.jpg` cell **(4,1)** 0-based — note: “Purple Mushroom +3”.  
**Current:** `SpecialType` has no `MUSHROOM` / `PLUS3`; cell remains UNVERIFIED / often UNKNOWN or purple misread.

## Appearance (from human GT + frame)

- Purple / magenta organic “mushroom” sprite (not a standard R/B/Y/G/P/O gem).
- “+3” badge overlay (score modifier), bright text/icon on tile.
- Occupies one 7×7 cell inside playfield ROI.

## Plan (do not ship blind types)

1. **Capture crops** of (4,1) from primary + any future live diag dumps (RGB/HSV/luma).
2. Add `SpecialType.MUSHROOM_PLUS3` (or `SCORE_MUSHROOM`) **only after** ≥N labeled crops.
3. `SpecialDetector`: bright “+”/digit lobe + purple body mask; require
   `SPECIAL_MIN_CONFIDENCE` — else NONE.
4. `ColorShapeReconciler`: if special=MUSHROOM → color may be UNKNOWN without counting as
   board-unknown **only if** policy approved (default: still unknown for match logic).
5. `MoveEvaluator`: +3 is scoring metadata — do **not** treat as clear-special (bomb/lightning)
   until rules are confirmed from gameplay.

## Non-goals this pass

- No threshold loosen.
- No guessed combo tables for mushroom.
