# Final report — 0.23.1-vision-stab

**Date:** 2026-09-28 (Europe/Vienna)  
**Tip:** `7e75c88` (feature `ebae234` + export golden refresh)  
**CI:** https://github.com/kovdacs/MatchMastersBot-Private/actions/runs/36466586679 — **SUCCESS**  
**APK:** `Match3Analyzer-0.23.1-vision-stab-7e75c88.apk` — **24 956 383** bytes (~23.8 MiB); artifact zip ~8.3 MiB  
**Unit tests:** BUILD SUCCESSFUL in 1m 48s (`testDebugUnitTest`)  
**assembleDebug:** BUILD SUCCESSFUL in 38s  

## PASS/HOLD

**Unchanged:** `MIN_GRID_CONFIDENCE=0.98`, `MIN_BOARD_CONFIDENCE=0.95`, `MAX_UNKNOWN_COUNT=1`.

## Real-frame result (`pvp_board.jpg`) — Tier D

| Metric | BEFORE (0.23.0) | AFTER (0.23.1) |
|--------|-----------------|----------------|
| gate | PASS | **PASS** |
| gridConfidence | 0.9872 | **0.9872** |
| boardConfidence | 1.0000 | **1.0000** |
| unknownCount | 0 | **0** |
| soft-GT color | ≈23/41 (**0.56**) | **41/41 (1.000)** |
| boardRoi | LTRB(20,1206,1060,2246) | same (geometry OK) |

### Soft-GT confusion matrix (AFTER)

```
GT\det   R  O  Y  G  B  P  UNKNOWN
R        7  0  0  0  0  0  0
O        0  7  0  0  0  0  0
Y        0  0  6  0  0  0  0
G        0  0  0  6  0  0  0
B        0  0  0  0  8  0  0
P        0  0  0  0  0  7  0
```

**Most common recognition errors (BEFORE):** blue playfield bleed → false `B` (and reconciler `B/STAR`).  
**AFTER:** no soft-GT color mismatches on VERIFIED cells.

## What shipped

| Area | Change |
|------|--------|
| Color | Center-disk (r=0.28) RGB+HSV+brightness + circular median + weighted vote |
| Shape sampling | `SHAPE_INSET_FRAC=0.18` (heuristics unchanged) |
| Diagnostics | `UnknownReason`: color\|shape\|special\|occlusion\|geometry/grid |
| Tests | Soft confusion matrix; center-weighted unit tests; export golden refresh |
| Input | **untouched** |

## Explicit live-phone answer

**Tier E not proven.** Do **not** claim live phone Move + `dispatchGesture` works.

## Mushroom/+3 — crops still required

No ad-hoc type. Need labeled crops (see `docs/MUSHROOM_SPECIAL_PLAN.md`):

1. Full cell `(4,1)` from `pvp_board.jpg`
2. Center body crop `(4,1)`
3. +3 badge corner crop
4. ≥3 more mushroom/+3 instances from other frames / live dumps

## Next concrete Vision task

1. Collect mushroom/+3 labeled crops → then `SpecialType.MUSHROOM_PLUS3`.  
2. Reduce false specials (`BOMB`/`TWO_WAY_ARROW` on normal gems).  
3. Live-phone Tier E capture with analyzer UI off the board (HOLD diag).
