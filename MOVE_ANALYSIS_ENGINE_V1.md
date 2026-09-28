# MOVE ANALYSIS ENGINE V1

**Date:** 2026-09-28 (Europe/Vienna)  
**Repo:** `kovdacs/MatchMastersBot-Private`  
**Mode:** Analyzer only — read-only ranking, never actuates.

## Gate (fail-closed)

Move analysis runs **only** when:

| Check | Threshold |
|-------|-----------|
| Vision validation | PASS |
| `gridConfidence` | ≥ `VisionThresholds.MIN_GRID_CONFIDENCE` (0.98) |
| `boardConfidence` | ≥ `VisionThresholds.MIN_BOARD_CONFIDENCE` (0.95) |
| `unknownCount` | ≤ `VisionThresholds.MAX_UNKNOWN_COUNT` (1) |

Else → **HOLD — Decision AI blocked**, empty TOP-5.

## Pipeline

```
VisionResult / GameState
  → MoveAnalysisEngine (gate)
  → LegalMoveGenerator (4-dir adjacent, no wrap; UNKNOWN excluded)
  → MoveSimulator / MoveResolver / CascadeEngine (match → special leave-behind → clear → gravity)
  → MoveEvaluator (match / cascade / special / stars / booster / extraMove / future / opponent / risk / EV)
  → TOP-5 by EV/score
```

Wired optionally from `AnalysisOrchestrator` after Vision PASS + SafetyGate (display only).

## MoveEvaluation V1 fields

`move`, `score`, `stars`, `booster`, `cascade`, `special`, `extraMove`, `future`, `opponent`, `risk`, `EV`, `confidence`, `WHY`  
(plus internal: matchCount, maxMatchSize, concurrentMatches, specialCreated, …).

## Safety

- No AccessibilityService / tap / swipe / touch injection / auto-play.
- Existing display-only `DecisionEngine` retained; orchestrator V1 path uses `MoveAnalysisEngine`.
- Vision thresholds / ROI unchanged. V3.1 parity remains `REFERENCE_PENDING`.
- No hardcoded best-move for `pvp_board.jpg`.

## Tests

`MoveAnalysisEngineTest`: 3/4/5-match, multi simultaneous, special create, no valid move, UNKNOWN/HOLD gate, TOP-5 ordering, V1 field aliases.  
`AnalysisOrchestratorTest`: HOLD blocks; PASS runs read-only TOP-5.
